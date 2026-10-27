package com.continuum.iam.internal;

import com.continuum.iam.api.SodRuleDirectory;
import com.continuum.iam.api.SodRuleView;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Implémentation (PostgreSQL ou Oracle, D4 : {@code continuum.db.dialect}) de {@link SodRuleDirectory} (plan §2.2) : lecture seule de la table
 * {@code sod_rule}, isolée par tenant. Tri déterministe (fonctionnalités puis type) pour une
 * restitution stable côté écran d'administration.
 */
@Service
public class JdbcSodRuleDirectory implements SodRuleDirectory {

    private static final String SQL = """
            SELECT id, feature_a, feature_b, type, severity, from_pack
            FROM sod_rule
            WHERE tenant_id = :tenant
            ORDER BY feature_a, feature_b, type
            """;

    private final NamedParameterJdbcTemplate jdbc;

    private final boolean oracle;

    public JdbcSodRuleDirectory(DataSource dataSource) {
        this(dataSource, "postgresql");
    }

    @Autowired
    public JdbcSodRuleDirectory(DataSource dataSource, @Value("${continuum.db.dialect:postgresql}") String dialect) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.oracle = "oracle".equalsIgnoreCase(dialect);
    }

    @Override
    public List<SodRuleView> listRules(UUID tenantId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tenant", oracle ? OracleAuthorizationService.raw(tenantId) : tenantId);
        return jdbc.query(SQL, params, (rs, i) -> new SodRuleView(
                oracle ? OracleAuthorizationService.uuid(rs.getBytes("id")) : rs.getObject("id", UUID.class),
                rs.getString("feature_a"),
                rs.getString("feature_b"),
                rs.getString("type"),
                rs.getString("severity"),
                rs.getBoolean("from_pack")));
    }
}
