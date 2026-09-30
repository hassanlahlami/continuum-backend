package com.continuum.iam.internal;

import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.ScopedFeature;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Implémentation Oracle de {@link AuthorizationService} (plan §2.1.6, D4).
 *
 * <p>Repository <strong>spécifique au dialecte</strong> : la portée {@code UNIT_TREE} s'appuie sur la
 * <strong>table de fermeture</strong> {@code org_unit_closure} (au lieu de {@code ltree} en
 * PostgreSQL) ; les booléens sont des {@code NUMBER(1)} et les UUID des {@code RAW(16)}.
 *
 * <p>Couvre {@link #can}, {@link #canOnMission} et les read models
 * ({@code effectiveFeatures}, {@code whoCan}, {@code permissions}), à parité avec PostgreSQL.
 */
public class OracleAuthorizationService implements AuthorizationService {

    private static final String CAN_SQL = """
            SELECT CASE WHEN EXISTS (
              SELECT 1
              FROM assignment a
              JOIN position p          ON p.id = a.position_id AND p.active = 1
              JOIN position_role pr    ON pr.position_id = p.id
              JOIN role_feature rf     ON rf.role_id = pr.role_id AND rf.feature_code = :feature
              JOIN platform.feature f  ON f.code = rf.feature_code AND f.deprecated = 0
              JOIN platform.tenant_module tm
                                       ON tm.tenant_id = a.tenant_id AND tm.module = f.module AND tm.enabled = 1
              WHERE a.tenant_id = :tenant AND a.user_id = :user
                AND a.valid_from <= CURRENT_DATE AND (a.valid_to IS NULL OR a.valid_to >= CURRENT_DATE)
                AND (   p.scope = 'TENANT'
                     OR (p.scope = 'UNIT'      AND a.org_unit_id = :unit)
                     OR (p.scope = 'UNIT_TREE' AND EXISTS (
                            SELECT 1 FROM org_unit_closure c
                            WHERE c.ancestor_id = a.org_unit_id AND c.descendant_id = :unit)))
                AND (NOT EXISTS (SELECT 1 FROM assignment_feature_restriction r WHERE r.assignment_id = a.id)
                     OR EXISTS  (SELECT 1 FROM assignment_feature_restriction r
                                 WHERE r.assignment_id = a.id AND r.feature_code = :feature))
            ) THEN 1 ELSE 0 END FROM dual
            """;

    private static final String CAN_ON_MISSION_SQL = """
            SELECT CASE WHEN EXISTS (
              SELECT 1
              FROM mission_member mm
              JOIN role r              ON r.id = mm.role_id AND r.tenant_id = :tenant AND r.kind = 'MISSION'
              JOIN role_feature rf     ON rf.role_id = r.id AND rf.feature_code = :feature
              JOIN platform.feature f  ON f.code = rf.feature_code AND f.deprecated = 0
              JOIN platform.tenant_module tm
                                       ON tm.tenant_id = :tenant AND tm.module = f.module AND tm.enabled = 1
              WHERE mm.user_id = :user AND mm.mission_id = :mission
                AND mm.valid_from <= CURRENT_DATE AND (mm.valid_to IS NULL OR mm.valid_to >= CURRENT_DATE)
            ) THEN 1 ELSE 0 END FROM dual
            """;

    private static final String EFFECTIVE_FEATURES_SQL = """
            SELECT DISTINCT rf.feature_code
            FROM assignment a
            JOIN position p          ON p.id = a.position_id AND p.active = 1
            JOIN position_role pr    ON pr.position_id = p.id
            JOIN role_feature rf     ON rf.role_id = pr.role_id
            JOIN platform.feature f  ON f.code = rf.feature_code AND f.deprecated = 0
            JOIN platform.tenant_module tm
                                     ON tm.tenant_id = a.tenant_id AND tm.module = f.module AND tm.enabled = 1
            WHERE a.tenant_id = :tenant AND a.user_id = :user
              AND a.valid_from <= CURRENT_DATE AND (a.valid_to IS NULL OR a.valid_to >= CURRENT_DATE)
              AND (   p.scope = 'TENANT'
                   OR (p.scope = 'UNIT'      AND a.org_unit_id = :unit)
                   OR (p.scope = 'UNIT_TREE' AND EXISTS (
                          SELECT 1 FROM org_unit_closure c
                          WHERE c.ancestor_id = a.org_unit_id AND c.descendant_id = :unit)))
              AND (NOT EXISTS (SELECT 1 FROM assignment_feature_restriction r WHERE r.assignment_id = a.id)
                   OR EXISTS  (SELECT 1 FROM assignment_feature_restriction r
                               WHERE r.assignment_id = a.id AND r.feature_code = rf.feature_code))
            """;

    private static final String WHO_CAN_SQL = """
            SELECT DISTINCT a.user_id
            FROM assignment a
            JOIN position p          ON p.id = a.position_id AND p.active = 1
            JOIN position_role pr    ON pr.position_id = p.id
            JOIN role_feature rf     ON rf.role_id = pr.role_id AND rf.feature_code = :feature
            JOIN platform.feature f  ON f.code = rf.feature_code AND f.deprecated = 0
            JOIN platform.tenant_module tm
                                     ON tm.tenant_id = a.tenant_id AND tm.module = f.module AND tm.enabled = 1
            WHERE a.tenant_id = :tenant
              AND a.valid_from <= CURRENT_DATE AND (a.valid_to IS NULL OR a.valid_to >= CURRENT_DATE)
              AND (   p.scope = 'TENANT'
                   OR (p.scope = 'UNIT'      AND a.org_unit_id = :unit)
                   OR (p.scope = 'UNIT_TREE' AND EXISTS (
                          SELECT 1 FROM org_unit_closure c
                          WHERE c.ancestor_id = a.org_unit_id AND c.descendant_id = :unit)))
              AND (NOT EXISTS (SELECT 1 FROM assignment_feature_restriction r WHERE r.assignment_id = a.id)
                   OR EXISTS  (SELECT 1 FROM assignment_feature_restriction r
                               WHERE r.assignment_id = a.id AND r.feature_code = rf.feature_code))
            """;

    private static final String PERMISSIONS_SQL = """
            SELECT DISTINCT rf.feature_code AS code, p.scope AS scope_type, a.org_unit_id AS scope_id
            FROM assignment a
            JOIN position p          ON p.id = a.position_id AND p.active = 1
            JOIN position_role pr    ON pr.position_id = p.id
            JOIN role_feature rf     ON rf.role_id = pr.role_id
            JOIN platform.feature f  ON f.code = rf.feature_code AND f.deprecated = 0
            JOIN platform.tenant_module tm
                                     ON tm.tenant_id = a.tenant_id AND tm.module = f.module AND tm.enabled = 1
            WHERE a.tenant_id = :tenant AND a.user_id = :user
              AND a.valid_from <= CURRENT_DATE AND (a.valid_to IS NULL OR a.valid_to >= CURRENT_DATE)
            UNION
            SELECT DISTINCT rf.feature_code AS code, 'MISSION' AS scope_type, mm.mission_id AS scope_id
            FROM mission_member mm
            JOIN role r              ON r.id = mm.role_id AND r.tenant_id = :tenant AND r.kind = 'MISSION'
            JOIN role_feature rf     ON rf.role_id = r.id
            JOIN platform.feature f  ON f.code = rf.feature_code AND f.deprecated = 0
            JOIN platform.tenant_module tm
                                     ON tm.tenant_id = :tenant AND tm.module = f.module AND tm.enabled = 1
            WHERE mm.user_id = :user
              AND mm.valid_from <= CURRENT_DATE AND (mm.valid_to IS NULL OR mm.valid_to >= CURRENT_DATE)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OracleAuthorizationService(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public boolean can(UUID tenantId, UUID userId, String featureCode, UUID unitId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", raw(tenantId))
                .addValue("user", raw(userId))
                .addValue("feature", featureCode)
                .addValue("unit", raw(unitId));
        return Integer.valueOf(1).equals(jdbc.queryForObject(CAN_SQL, params, Integer.class));
    }

    @Override
    public boolean canOnMission(UUID tenantId, UUID userId, String featureCode, UUID missionId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", raw(tenantId))
                .addValue("user", raw(userId))
                .addValue("feature", featureCode)
                .addValue("mission", raw(missionId));
        return Integer.valueOf(1).equals(jdbc.queryForObject(CAN_ON_MISSION_SQL, params, Integer.class));
    }

    @Override
    public Set<String> effectiveFeatures(UUID tenantId, UUID userId, UUID unitId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", raw(tenantId)).addValue("user", raw(userId)).addValue("unit", raw(unitId));
        return new LinkedHashSet<>(jdbc.queryForList(EFFECTIVE_FEATURES_SQL, params, String.class));
    }

    @Override
    public Set<UUID> whoCan(UUID tenantId, String featureCode, UUID unitId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", raw(tenantId)).addValue("feature", featureCode).addValue("unit", raw(unitId));
        return new LinkedHashSet<>(jdbc.query(WHO_CAN_SQL, params, (rs, i) -> uuid(rs.getBytes("user_id"))));
    }

    @Override
    public List<ScopedFeature> permissions(UUID tenantId, UUID userId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", raw(tenantId)).addValue("user", raw(userId));
        return jdbc.query(PERMISSIONS_SQL, params, (rs, i) -> new ScopedFeature(
                rs.getString("code"), rs.getString("scope_type"), uuid(rs.getBytes("scope_id"))));
    }

    /** RAW(16) → UUID (16 octets big-endian). */
    static UUID uuid(byte[] raw) {
        if (raw == null) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }

    /** UUID → RAW(16) (16 octets big-endian) pour la liaison JDBC Oracle. */
    static byte[] raw(UUID id) {
        if (id == null) {
            return null;
        }
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
