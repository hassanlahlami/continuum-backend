package com.continuum.iam.internal;

import com.continuum.iam.api.RoleDirectory;
import com.continuum.iam.api.RoleView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Implémentation PostgreSQL de {@link RoleDirectory} (plan §2.1.1) : lecture seule de {@code role},
 * {@code role_feature} et {@code position_role}, isolée par tenant, tri déterministe par code.
 */
@Service
public class JdbcRoleDirectory implements RoleDirectory {

    private static final String ROLES_SQL = """
            SELECT r.id, r.code, r.label::text AS label, r.kind, r.from_pack,
                   (SELECT COUNT(*) FROM position_role pr WHERE pr.role_id = r.id) AS positions_count
            FROM role r
            WHERE r.tenant_id = :tenant
            ORDER BY r.code
            """;

    private static final String FEATURES_SQL = """
            SELECT rf.role_id, rf.feature_code
            FROM role_feature rf
            JOIN role r ON r.id = rf.role_id
            WHERE r.tenant_id = :tenant
            ORDER BY rf.feature_code
            """;

    private static final TypeReference<Map<String, String>> LABEL_TYPE = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcRoleDirectory(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public List<RoleView> listRoles(UUID tenantId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tenant", tenantId);
        Map<UUID, List<String>> features = new LinkedHashMap<>();
        jdbc.query(FEATURES_SQL, params, rs -> {
            features.computeIfAbsent(rs.getObject("role_id", UUID.class), k -> new ArrayList<>())
                    .add(rs.getString("feature_code"));
        });
        return jdbc.query(ROLES_SQL, params, (rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            return new RoleView(id, rs.getString("code"), parseLabel(rs.getString("label")),
                    rs.getString("kind"), rs.getBoolean("from_pack"),
                    features.getOrDefault(id, List.of()), rs.getInt("positions_count"));
        });
    }

    private Map<String, String> parseLabel(String raw) {
        try {
            return json.readValue(raw, LABEL_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Libellé de rôle illisible : " + raw, e);
        }
    }
}
