package com.continuum.iam.internal;

import com.continuum.iam.api.SodRuleDirectory;
import com.continuum.iam.api.SodRuleView;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Implémentation PostgreSQL de {@link SodRuleDirectory} (plan §2.2) : lecture seule de la table
 * {@code sod_rule}, isolée par tenant. Tri déterministe (fonctionnalités puis type) pour une
 * restitution stable côté écran d'administration.
 */
@Service
public class JdbcSodRuleDirectory implements SodRuleDirectory {

    private static final String SQL = """
            SELECT id, feature_a, feature_b, type, severity
            FROM sod_rule
            WHERE tenant_id = :tenant
            ORDER BY feature_a, feature_b, type
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSodRuleDirectory(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public List<SodRuleView> listRules(UUID tenantId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tenant", tenantId);
        return jdbc.query(SQL, params, (rs, i) -> new SodRuleView(
                rs.getObject("id", UUID.class),
                rs.getString("feature_a"),
                rs.getString("feature_b"),
                rs.getString("type"),
                rs.getString("severity")));
    }
}
