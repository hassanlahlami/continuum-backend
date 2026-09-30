package com.continuum.iam.internal;

import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.ScopedFeature;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Implémentation PostgreSQL de {@link AuthorizationService} (plan §2.1.6, D4).
 *
 * <p>Repository <strong>spécifique au dialecte</strong> : la portée {@code UNIT_TREE} s'appuie sur
 * l'opérateur {@code ltree} {@code <@} (descendant de). Une implémentation Oracle équivalente
 * (table de fermeture {@code org_unit_closure}) suivra derrière la même interface.
 *
 * <p>Les tables d'entité ({@code assignment}, {@code position}, {@code org_unit}…) sont référencées
 * sans schéma : la connexion doit pointer sur le schéma de l'entité (§4.7). Le catalogue partagé est
 * qualifié {@code platform.*}.
 */
@Service
public class JdbcAuthorizationService implements AuthorizationService {

    private static final String CAN_SQL = """
            SELECT EXISTS (
              SELECT 1
              FROM assignment a
              JOIN position p          ON p.id = a.position_id AND p.active
              JOIN position_role pr    ON pr.position_id = p.id
              JOIN role_feature rf     ON rf.role_id = pr.role_id AND rf.feature_code = :feature
              JOIN platform.feature f  ON f.code = rf.feature_code AND NOT f.deprecated
              JOIN platform.tenant_module tm
                                       ON tm.tenant_id = a.tenant_id AND tm.module = f.module AND tm.enabled
              JOIN org_unit target     ON target.id = :unit
              LEFT JOIN org_unit held  ON held.id = a.org_unit_id
              WHERE a.tenant_id = :tenant
                AND a.user_id   = :user
                AND CURRENT_DATE >= a.valid_from
                AND (a.valid_to IS NULL OR CURRENT_DATE <= a.valid_to)
                AND (   p.scope = 'TENANT'
                     OR (p.scope = 'UNIT'      AND held.id = target.id)
                     OR (p.scope = 'UNIT_TREE' AND target.path <@ held.path))
                AND (NOT EXISTS (SELECT 1 FROM assignment_feature_restriction r
                                 WHERE r.assignment_id = a.id)
                     OR EXISTS  (SELECT 1 FROM assignment_feature_restriction r
                                 WHERE r.assignment_id = a.id AND r.feature_code = :feature))
            )
            """;

    private static final String CAN_ON_MISSION_SQL = """
            SELECT EXISTS (
              SELECT 1
              FROM mission_member mm
              JOIN role r              ON r.id = mm.role_id AND r.tenant_id = :tenant AND r.kind = 'MISSION'
              JOIN role_feature rf     ON rf.role_id = r.id AND rf.feature_code = :feature
              JOIN platform.feature f  ON f.code = rf.feature_code AND NOT f.deprecated
              JOIN platform.tenant_module tm
                                       ON tm.tenant_id = :tenant AND tm.module = f.module AND tm.enabled
              WHERE mm.user_id = :user
                AND mm.mission_id = :mission
                AND CURRENT_DATE >= mm.valid_from
                AND (mm.valid_to IS NULL OR CURRENT_DATE <= mm.valid_to)
            )
            """;

    private static final String EFFECTIVE_FEATURES_SQL = """
            SELECT DISTINCT rf.feature_code
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
            """;

    private static final String WHO_CAN_SQL = """
            SELECT DISTINCT a.user_id
            FROM assignment a
            JOIN position p          ON p.id = a.position_id AND p.active
            JOIN position_role pr    ON pr.position_id = p.id
            JOIN role_feature rf     ON rf.role_id = pr.role_id AND rf.feature_code = :feature
            JOIN platform.feature f  ON f.code = rf.feature_code AND NOT f.deprecated
            JOIN platform.tenant_module tm
                                     ON tm.tenant_id = a.tenant_id AND tm.module = f.module AND tm.enabled
            JOIN org_unit target     ON target.id = :unit
            LEFT JOIN org_unit hu    ON hu.id = a.org_unit_id
            WHERE a.tenant_id = :tenant
              AND CURRENT_DATE >= a.valid_from
              AND (a.valid_to IS NULL OR CURRENT_DATE <= a.valid_to)
              AND (   p.scope = 'TENANT'
                   OR (p.scope = 'UNIT'      AND hu.id = target.id)
                   OR (p.scope = 'UNIT_TREE' AND target.path <@ hu.path))
              AND (NOT EXISTS (SELECT 1 FROM assignment_feature_restriction r
                               WHERE r.assignment_id = a.id)
                   OR EXISTS  (SELECT 1 FROM assignment_feature_restriction r
                               WHERE r.assignment_id = a.id AND r.feature_code = rf.feature_code))
            """;

    private static final String PERMISSIONS_SQL = """
            SELECT DISTINCT rf.feature_code AS code, p.scope AS scope_type, a.org_unit_id AS scope_id
            FROM assignment a
            JOIN position p          ON p.id = a.position_id AND p.active
            JOIN position_role pr    ON pr.position_id = p.id
            JOIN role_feature rf     ON rf.role_id = pr.role_id
            JOIN platform.feature f  ON f.code = rf.feature_code AND NOT f.deprecated
            JOIN platform.tenant_module tm
                                     ON tm.tenant_id = a.tenant_id AND tm.module = f.module AND tm.enabled
            WHERE a.tenant_id = :tenant AND a.user_id = :user
              AND CURRENT_DATE >= a.valid_from AND (a.valid_to IS NULL OR CURRENT_DATE <= a.valid_to)
            UNION
            SELECT DISTINCT rf.feature_code AS code, 'MISSION' AS scope_type, mm.mission_id AS scope_id
            FROM mission_member mm
            JOIN role r              ON r.id = mm.role_id AND r.tenant_id = :tenant AND r.kind = 'MISSION'
            JOIN role_feature rf     ON rf.role_id = r.id
            JOIN platform.feature f  ON f.code = rf.feature_code AND NOT f.deprecated
            JOIN platform.tenant_module tm
                                     ON tm.tenant_id = :tenant AND tm.module = f.module AND tm.enabled
            WHERE mm.user_id = :user
              AND CURRENT_DATE >= mm.valid_from AND (mm.valid_to IS NULL OR CURRENT_DATE <= mm.valid_to)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAuthorizationService(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public boolean can(UUID tenantId, UUID userId, String featureCode, UUID unitId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("user", userId)
                .addValue("feature", featureCode)
                .addValue("unit", unitId);
        return Boolean.TRUE.equals(jdbc.queryForObject(CAN_SQL, params, Boolean.class));
    }

    @Override
    public boolean canOnMission(UUID tenantId, UUID userId, String featureCode, UUID missionId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("user", userId)
                .addValue("feature", featureCode)
                .addValue("mission", missionId);
        return Boolean.TRUE.equals(jdbc.queryForObject(CAN_ON_MISSION_SQL, params, Boolean.class));
    }

    @Override
    public Set<String> effectiveFeatures(UUID tenantId, UUID userId, UUID unitId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("user", userId)
                .addValue("unit", unitId);
        return new LinkedHashSet<>(jdbc.queryForList(EFFECTIVE_FEATURES_SQL, params, String.class));
    }

    @Override
    public Set<UUID> whoCan(UUID tenantId, String featureCode, UUID unitId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("feature", featureCode)
                .addValue("unit", unitId);
        return new LinkedHashSet<>(jdbc.queryForList(WHO_CAN_SQL, params, UUID.class));
    }

    @Override
    public List<ScopedFeature> permissions(UUID tenantId, UUID userId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("user", userId);
        return jdbc.query(PERMISSIONS_SQL, params, (rs, i) -> new ScopedFeature(
                rs.getString("code"),
                rs.getString("scope_type"),
                rs.getObject("scope_id", UUID.class)));
    }
}
