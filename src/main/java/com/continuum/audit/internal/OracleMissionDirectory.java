package com.continuum.audit.internal;

import com.continuum.audit.api.MissionDirectory;
import com.continuum.audit.api.MissionView;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Lecture des missions (plan §6), <strong>dialecte Oracle</strong> (D4). Même périmètre que
 * {@link JdbcMissionDirectory} (§2.1.6, {@code mission.read}) mais spécifique à Oracle : portée
 * {@code UNIT_TREE} via la <strong>table de fermeture</strong> {@code org_unit_closure} (au lieu de
 * {@code ltree}), UUID en {@code RAW(16)}, booléens {@code NUMBER(1)}, libellé via {@code JSON_VALUE},
 * pagination {@code OFFSET/FETCH}.
 */
public class OracleMissionDirectory implements MissionDirectory {

    private static final String VISIBLE = """
            EXISTS (
              SELECT 1 FROM assignment a
              JOIN position p        ON p.id = a.position_id AND p.active = 1
              JOIN position_role pr  ON pr.position_id = p.id
              JOIN role_feature rf   ON rf.role_id = pr.role_id AND rf.feature_code = 'mission.read'
              JOIN platform.feature f ON f.code = rf.feature_code AND f.deprecated = 0
              JOIN platform.tenant_module tm
                                     ON tm.tenant_id = a.tenant_id AND tm.module = f.module AND tm.enabled = 1
              WHERE a.tenant_id = :tenant AND a.user_id = :user
                AND a.valid_from <= CURRENT_DATE AND (a.valid_to IS NULL OR a.valid_to >= CURRENT_DATE)
                AND (   p.scope = 'TENANT'
                     OR (p.scope = 'UNIT'      AND a.org_unit_id = m.org_unit_id)
                     OR (p.scope = 'UNIT_TREE' AND EXISTS (
                            SELECT 1 FROM org_unit_closure c
                            WHERE c.ancestor_id = a.org_unit_id AND c.descendant_id = m.org_unit_id)))
                AND (NOT EXISTS (SELECT 1 FROM assignment_feature_restriction r WHERE r.assignment_id = a.id)
                     OR EXISTS  (SELECT 1 FROM assignment_feature_restriction r
                                 WHERE r.assignment_id = a.id AND r.feature_code = 'mission.read'))
            )
            """;

    private static final String COLUMNS = """
            m.id, m.reference, m.title, m.mission_type_code, m.mission_type_label,
            m.status_code, m.status_label, m.phase_code, m.phase_label, m.progress_percent,
            m.planned_start, m.planned_end, m.org_unit_id,
            mu.code AS unit_code, JSON_VALUE(mu.label, '$.fr') AS unit_label, m.created_at, m.version
            """;

    private static final String SELECT = "SELECT " + COLUMNS + """
            FROM mission m
            LEFT JOIN org_unit mu ON mu.id = m.org_unit_id
            WHERE m.tenant_id = :tenant
              AND (:q IS NULL OR LOWER(m.reference) LIKE LOWER(:q) OR LOWER(m.title) LIKE LOWER(:q))
              AND """ + " " + VISIBLE + """
            ORDER BY m.reference
            OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY
            """;

    private static final String COUNT = """
            SELECT count(*)
            FROM mission m
            LEFT JOIN org_unit mu ON mu.id = m.org_unit_id
            WHERE m.tenant_id = :tenant
              AND (:q IS NULL OR LOWER(m.reference) LIKE LOWER(:q) OR LOWER(m.title) LIKE LOWER(:q))
              AND """ + " " + VISIBLE;

    private static final String BY_ID = "SELECT " + COLUMNS + """
            FROM mission m
            LEFT JOIN org_unit mu ON mu.id = m.org_unit_id
            WHERE m.tenant_id = :tenant AND m.id = :mission
              AND """ + " " + VISIBLE;

    private static final RowMapper<MissionView> MAPPER = (rs, i) -> new MissionView(
            uuid(rs.getBytes("id")),
            rs.getString("reference"),
            rs.getString("title"),
            rs.getString("mission_type_code"),
            rs.getString("mission_type_label"),
            rs.getString("status_code"),
            rs.getString("status_label"),
            rs.getString("phase_code"),
            rs.getString("phase_label"),
            rs.getObject("progress_percent", Integer.class),
            rs.getObject("planned_start", LocalDate.class),
            rs.getObject("planned_end", LocalDate.class),
            uuid(rs.getBytes("org_unit_id")),
            rs.getString("unit_code"),
            rs.getString("unit_label"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getInt("version"));

    private final NamedParameterJdbcTemplate jdbc;

    public OracleMissionDirectory(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public Page listVisible(UUID tenantId, UUID userId, String query, int page, int size) {
        MapSqlParameterSource params = params(tenantId, userId, query)
                .addValue("size", size).addValue("offset", (long) page * size);
        List<MissionView> items = jdbc.query(SELECT, params, MAPPER);
        Integer total = jdbc.queryForObject(COUNT, params, Integer.class);
        return new Page(items, total == null ? 0 : total);
    }

    @Override
    public Optional<MissionView> findVisible(UUID tenantId, UUID userId, UUID missionId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", raw(tenantId)).addValue("user", raw(userId))
                .addValue("mission", raw(missionId));
        return jdbc.query(BY_ID, params, MAPPER).stream().findFirst();
    }

    private static MapSqlParameterSource params(UUID tenantId, UUID userId, String query) {
        String like = (query == null || query.isBlank()) ? null : "%" + query.strip() + "%";
        // Type SQL explicite : Oracle ne peut pas inférer le type d'un bind null (ORA-17004).
        return new MapSqlParameterSource()
                .addValue("tenant", raw(tenantId)).addValue("user", raw(userId))
                .addValue("q", like, java.sql.Types.VARCHAR);
    }

    static byte[] raw(UUID id) {
        if (id == null) {
            return null;
        }
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    static UUID uuid(byte[] raw) {
        if (raw == null) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
