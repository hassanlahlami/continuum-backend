package com.continuum.iam.internal;

import com.continuum.iam.api.SegregationOfDutiesService;
import com.continuum.iam.api.SodViolation;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Implémentation PostgreSQL de {@link SegregationOfDutiesService} (plan §2.1.8, §2.2).
 *
 * <p>Calcule les fonctionnalités effectivement détenues par l'utilisateur sur l'unité (même logique
 * que le moteur {@code can()}, §2.1.6), puis retourne les règles {@code sod_rule} de type
 * {@code STATIC} dont les deux fonctionnalités sont détenues simultanément.
 */
@Service
public class JdbcSegregationOfDutiesService implements SegregationOfDutiesService {

    private static final String STATIC_CONFLICTS_SQL = """
            WITH held AS (
              SELECT DISTINCT rf.feature_code AS code
              FROM assignment a
              JOIN position p          ON p.id = a.position_id AND p.active
              JOIN position_role pr    ON pr.position_id = p.id
              JOIN role_feature rf     ON rf.role_id = pr.role_id
              JOIN platform.feature f  ON f.code = rf.feature_code AND NOT f.deprecated
              JOIN platform.tenant_module tm
                                       ON tm.tenant_id = a.tenant_id AND tm.module = f.module AND tm.enabled
              JOIN org_unit target     ON target.id = :unit
              LEFT JOIN org_unit hu    ON hu.id = a.org_unit_id
              WHERE a.tenant_id = :tenant
                AND a.user_id   = :user
                AND CURRENT_DATE >= a.valid_from
                AND (a.valid_to IS NULL OR CURRENT_DATE <= a.valid_to)
                AND (   p.scope = 'TENANT'
                     OR (p.scope = 'UNIT'      AND hu.id = target.id)
                     OR (p.scope = 'UNIT_TREE' AND target.path <@ hu.path))
                AND (NOT EXISTS (SELECT 1 FROM assignment_feature_restriction r
                                 WHERE r.assignment_id = a.id)
                     OR EXISTS  (SELECT 1 FROM assignment_feature_restriction r
                                 WHERE r.assignment_id = a.id AND r.feature_code = rf.feature_code))
            )
            SELECT s.feature_a, s.feature_b, s.severity
            FROM sod_rule s
            WHERE s.tenant_id = :tenant
              AND s.type = 'STATIC'
              AND s.feature_a IN (SELECT code FROM held)
              AND s.feature_b IN (SELECT code FROM held)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSegregationOfDutiesService(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public List<SodViolation> staticConflicts(UUID tenantId, UUID userId, UUID unitId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("user", userId)
                .addValue("unit", unitId);
        return jdbc.query(STATIC_CONFLICTS_SQL, params, (rs, i) -> new SodViolation(
                rs.getString("feature_a"),
                rs.getString("feature_b"),
                SodViolation.Severity.valueOf(rs.getString("severity"))));
    }
}
