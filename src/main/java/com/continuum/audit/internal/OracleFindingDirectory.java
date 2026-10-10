package com.continuum.audit.internal;

import com.continuum.audit.api.FindingDirectory;
import com.continuum.audit.api.FindingView;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Lecture des constats (plan §6), <strong>dialecte Oracle</strong> (D4). Même périmètre que
 * {@link JdbcFindingDirectory} ({@code finding.read}, §2.1.6), mais spécifique à Oracle : portée
 * {@code UNIT_TREE} via la table de fermeture {@code org_unit_closure}, UUID {@code RAW(16)},
 * booléens {@code NUMBER(1)}, pagination {@code OFFSET/FETCH}.
 */
public class OracleFindingDirectory implements FindingDirectory {

    private static final String VISIBLE = """
            EXISTS (
              SELECT 1 FROM assignment a
              JOIN position p        ON p.id = a.position_id AND p.active = 1
              JOIN position_role pr  ON pr.position_id = p.id
              JOIN role_feature rf   ON rf.role_id = pr.role_id AND rf.feature_code = 'finding.read'
              JOIN platform.feature ft ON ft.code = rf.feature_code AND ft.deprecated = 0
              JOIN platform.tenant_module tm
                                     ON tm.tenant_id = a.tenant_id AND tm.module = ft.module AND tm.enabled = 1
              WHERE a.tenant_id = :tenant AND a.user_id = :user
                AND a.valid_from <= CURRENT_DATE AND (a.valid_to IS NULL OR a.valid_to >= CURRENT_DATE)
                AND (   p.scope = 'TENANT'
                     OR (p.scope = 'UNIT'      AND a.org_unit_id = fd.org_unit_id)
                     OR (p.scope = 'UNIT_TREE' AND EXISTS (
                            SELECT 1 FROM org_unit_closure c
                            WHERE c.ancestor_id = a.org_unit_id AND c.descendant_id = fd.org_unit_id)))
                AND (NOT EXISTS (SELECT 1 FROM assignment_feature_restriction r WHERE r.assignment_id = a.id)
                     OR EXISTS  (SELECT 1 FROM assignment_feature_restriction r
                                 WHERE r.assignment_id = a.id AND r.feature_code = 'finding.read'))
            )
            """;

    private static final String FROM_WHERE = """
            FROM finding fd
            LEFT JOIN mission mi ON mi.id = fd.mission_id
            WHERE fd.tenant_id = :tenant
              AND (:q IS NULL OR LOWER(fd.reference) LIKE LOWER(:q) OR LOWER(fd.title) LIKE LOWER(:q))
              AND (:source IS NULL OR fd.source = :source)
              AND (:crit IS NULL OR fd.criticality_code = :crit)
              AND """ + " " + VISIBLE;

    private static final String SELECT = """
            SELECT fd.id, fd.reference, fd.title, fd.status_code, fd.status_label,
                   fd.criticality_code, fd.criticality_label, fd.source, fd.mission_id, mi.title AS mission_title,
                   fd.created_at, fd.version, fd.fact, fd.criterion, fd.cause, fd.consequence
            """ + FROM_WHERE + """
            ORDER BY fd.reference
            OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY
            """;

    private static final String COUNT = "SELECT count(*) " + FROM_WHERE;

    private static final String BY_MISSION = """
            SELECT fd.id, fd.reference, fd.title, fd.status_code, fd.status_label,
                   fd.criticality_code, fd.criticality_label, fd.source, fd.mission_id, mi.title AS mission_title,
                   fd.created_at, fd.version, fd.fact, fd.criterion, fd.cause, fd.consequence
            FROM finding fd
            LEFT JOIN mission mi ON mi.id = fd.mission_id
            WHERE fd.tenant_id = :tenant AND fd.mission_id = :mission
              AND """ + " " + VISIBLE + """
            ORDER BY fd.reference
            """;

    private static final RowMapper<FindingView> MAPPER = (rs, i) -> new FindingView(
            OracleMissionDirectory.uuid(rs.getBytes("id")),
            rs.getString("reference"),
            rs.getString("title"),
            rs.getString("status_code"),
            rs.getString("status_label"),
            rs.getString("criticality_code"),
            rs.getString("criticality_label"),
            rs.getString("source"),
            OracleMissionDirectory.uuid(rs.getBytes("mission_id")),
            rs.getString("mission_title"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getInt("version"),
            rs.getString("fact"),
            rs.getString("criterion"),
            rs.getString("cause"),
            rs.getString("consequence"));

    private final NamedParameterJdbcTemplate jdbc;

    public OracleFindingDirectory(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public Page searchVisible(UUID tenantId, UUID userId, String query, String source, String criticality,
            int page, int size) {
        String like = (query == null || query.isBlank()) ? null : "%" + query.strip() + "%";
        // Type SQL explicite : Oracle ne peut pas inférer le type d'un bind null (ORA-17004).
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", OracleMissionDirectory.raw(tenantId))
                .addValue("user", OracleMissionDirectory.raw(userId))
                .addValue("q", like, java.sql.Types.VARCHAR)
                .addValue("source", blankToNull(source), java.sql.Types.VARCHAR)
                .addValue("crit", blankToNull(criticality), java.sql.Types.VARCHAR)
                .addValue("size", size).addValue("offset", (long) page * size);
        List<FindingView> items = jdbc.query(SELECT, params, MAPPER);
        Integer total = jdbc.queryForObject(COUNT, params, Integer.class);
        return new Page(items, total == null ? 0 : total);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    @Override
    public List<FindingView> listByMission(UUID tenantId, UUID userId, UUID missionId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", OracleMissionDirectory.raw(tenantId))
                .addValue("user", OracleMissionDirectory.raw(userId))
                .addValue("mission", OracleMissionDirectory.raw(missionId));
        return jdbc.query(BY_MISSION, params, MAPPER);
    }

    @Override
    public java.util.Optional<FindingView> findVisible(UUID tenantId, UUID userId, UUID missionId, UUID findingId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", OracleMissionDirectory.raw(tenantId))
                .addValue("user", OracleMissionDirectory.raw(userId))
                .addValue("mission", OracleMissionDirectory.raw(missionId))
                .addValue("finding", OracleMissionDirectory.raw(findingId));
        return jdbc.query(BY_MISSION.replace("fd.mission_id = :mission", "fd.mission_id = :mission AND fd.id = :finding"),
                params, MAPPER).stream().findFirst();
    }
}
