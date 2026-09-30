package com.continuum.iam.internal;

import com.continuum.iam.api.AuthorizationService;
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
}
