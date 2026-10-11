package com.continuum.iam.internal;

import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.iam.api.SegregationOfDutiesService;
import com.continuum.iam.api.SodViolation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Implémentation Oracle de {@link SegregationOfDutiesService} (plan §2.1.8, §2.2, D4), à parité avec
 * PostgreSQL. Spécifique au dialecte : portée {@code UNIT_TREE} via la <strong>table de fermeture</strong>
 * {@code org_unit_closure}, booléens {@code NUMBER(1)}, UUID {@code RAW(16)}.
 */
public class OracleSegregationOfDutiesService implements SegregationOfDutiesService {

    private static final String STATIC_CONFLICTS_SQL = """
            WITH held AS (
              SELECT DISTINCT rf.feature_code AS code
              FROM assignment a
              JOIN position p          ON p.id = a.position_id AND p.active = 1
              JOIN position_role pr    ON pr.position_id = p.id
              JOIN role_feature rf     ON rf.role_id = pr.role_id
              JOIN platform.feature f  ON f.code = rf.feature_code AND f.deprecated = 0
              JOIN platform.tenant_module tm
                                       ON tm.tenant_id = a.tenant_id AND tm.module = f.module AND tm.enabled = 1
              WHERE a.tenant_id = :tenant
                AND a.user_id   = :user
                AND a.valid_from <= CURRENT_DATE AND (a.valid_to IS NULL OR a.valid_to >= CURRENT_DATE)
                AND (   p.scope = 'TENANT'
                     OR (p.scope = 'UNIT'      AND a.org_unit_id = :unit)
                     OR (p.scope = 'UNIT_TREE' AND EXISTS (
                            SELECT 1 FROM org_unit_closure c
                            WHERE c.ancestor_id = a.org_unit_id AND c.descendant_id = :unit)))
                AND (NOT EXISTS (SELECT 1 FROM assignment_feature_restriction r WHERE r.assignment_id = a.id)
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

    private static final String DYNAMIC_RULES_SQL = """
            SELECT feature_a, feature_b, severity
            FROM sod_rule
            WHERE tenant_id = :tenant AND type = 'DYNAMIC' AND :feature IN (feature_a, feature_b)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditTrailService auditTrail;

    public OracleSegregationOfDutiesService(DataSource dataSource, AuditTrailService auditTrail) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.auditTrail = auditTrail;
    }

    @Override
    public List<SodViolation> staticConflicts(UUID tenantId, UUID userId, UUID unitId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", OracleAuthorizationService.raw(tenantId))
                .addValue("user", OracleAuthorizationService.raw(userId))
                .addValue("unit", OracleAuthorizationService.raw(unitId));
        return jdbc.query(STATIC_CONFLICTS_SQL, params, (rs, i) -> new SodViolation(
                rs.getString("feature_a"),
                rs.getString("feature_b"),
                SodViolation.Severity.valueOf(rs.getString("severity"))));
    }

    @Override
    public List<SodViolation> dynamicConflicts(
            UUID tenantId, UUID userId, String featureCode, String objectType, String objectId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", OracleAuthorizationService.raw(tenantId))
                .addValue("feature", featureCode);
        List<SodViolation> dynamicRules = jdbc.query(DYNAMIC_RULES_SQL, params, (rs, i) -> new SodViolation(
                rs.getString("feature_a"),
                rs.getString("feature_b"),
                SodViolation.Severity.valueOf(rs.getString("severity"))));

        List<SodViolation> violations = new ArrayList<>();
        for (SodViolation rule : dynamicRules) {
            String conflicting = featureCode.equals(rule.featureA()) ? rule.featureB() : rule.featureA();
            if (auditTrail.hasActed(tenantId, userId, conflicting, objectType, objectId)) {
                violations.add(rule);
            }
        }
        return violations;
    }
}
