package com.continuum.audit.internal;

import com.continuum.audit.api.MissionDirectory;
import com.continuum.audit.api.MissionView;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Lecture des missions (plan §6), PostgreSQL. Le périmètre est appliqué dans la requête (§2.1.6) :
 * une mission n'est visible que si l'utilisateur détient {@code mission.read} sur son unité
 * (portées {@code TENANT}/{@code UNIT}/{@code UNIT_TREE} via {@code ltree}), module {@code MISSION}
 * activé et restrictions de délégation respectées. Le front n'enforce rien : il affiche ce que le
 * serveur renvoie.
 */
@Service
public class JdbcMissionDirectory implements MissionDirectory {

    private static final String VISIBLE = """
            EXISTS (
              SELECT 1 FROM assignment a
              JOIN position p        ON p.id = a.position_id AND p.active
              JOIN position_role pr  ON pr.position_id = p.id
              JOIN role_feature rf   ON rf.role_id = pr.role_id AND rf.feature_code = 'mission.read'
              JOIN platform.feature f ON f.code = rf.feature_code AND NOT f.deprecated
              JOIN platform.tenant_module tm
                                     ON tm.tenant_id = a.tenant_id AND tm.module = f.module AND tm.enabled
              LEFT JOIN org_unit hu  ON hu.id = a.org_unit_id
              WHERE a.tenant_id = :tenant AND a.user_id = :user
                AND CURRENT_DATE >= a.valid_from AND (a.valid_to IS NULL OR CURRENT_DATE <= a.valid_to)
                AND (   p.scope = 'TENANT'
                     OR (p.scope = 'UNIT'      AND hu.id = m.org_unit_id)
                     OR (p.scope = 'UNIT_TREE' AND mu.path <@ hu.path))
                AND (NOT EXISTS (SELECT 1 FROM assignment_feature_restriction r WHERE r.assignment_id = a.id)
                     OR EXISTS  (SELECT 1 FROM assignment_feature_restriction r
                                 WHERE r.assignment_id = a.id AND r.feature_code = 'mission.read'))
            )
            """;

    private static final String SELECT = """
            SELECT m.id, m.reference, m.title, m.mission_type_code, m.mission_type_label,
                   m.status_code, m.status_label, m.phase_code, m.phase_label, m.progress_percent,
                   m.planned_start, m.planned_end, m.org_unit_id,
                   mu.code AS unit_code, mu.label ->> 'fr' AS unit_label, m.created_at, m.version
            FROM mission m
            LEFT JOIN org_unit mu ON mu.id = m.org_unit_id
            WHERE m.tenant_id = :tenant
              AND (CAST(:q AS text) IS NULL OR m.reference ILIKE CAST(:q AS text) OR m.title ILIKE CAST(:q AS text))
              AND """ + " " + VISIBLE + """
            ORDER BY m.reference
            LIMIT :size OFFSET :offset
            """;

    private static final String COUNT = """
            SELECT count(*)
            FROM mission m
            LEFT JOIN org_unit mu ON mu.id = m.org_unit_id
            WHERE m.tenant_id = :tenant
              AND (CAST(:q AS text) IS NULL OR m.reference ILIKE CAST(:q AS text) OR m.title ILIKE CAST(:q AS text))
              AND """ + " " + VISIBLE;

    private static final String BY_ID = """
            SELECT m.id, m.reference, m.title, m.mission_type_code, m.mission_type_label,
                   m.status_code, m.status_label, m.phase_code, m.phase_label, m.progress_percent,
                   m.planned_start, m.planned_end, m.org_unit_id,
                   mu.code AS unit_code, mu.label ->> 'fr' AS unit_label, m.created_at, m.version
            FROM mission m
            LEFT JOIN org_unit mu ON mu.id = m.org_unit_id
            WHERE m.tenant_id = :tenant AND m.id = :mission
              AND """ + " " + VISIBLE;

    private static final RowMapper<MissionView> MAPPER = (rs, i) -> new MissionView(
            rs.getObject("id", UUID.class),
            rs.getString("reference"),
            rs.getString("title"),
            rs.getString("mission_type_code"),
            rs.getString("mission_type_label"),
            rs.getString("status_code"),
            rs.getString("status_label"),
            rs.getString("phase_code"),
            rs.getString("phase_label"),
            rs.getObject("progress_percent", Integer.class),
            rs.getObject("planned_start", java.time.LocalDate.class),
            rs.getObject("planned_end", java.time.LocalDate.class),
            rs.getObject("org_unit_id", UUID.class),
            rs.getString("unit_code"),
            rs.getString("unit_label"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getInt("version"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcMissionDirectory(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public Page listVisible(UUID tenantId, UUID userId, String query, int page, int size) {
        MapSqlParameterSource params = baseParams(tenantId, userId, query)
                .addValue("size", size)
                .addValue("offset", (long) page * size);
        List<MissionView> items = jdbc.query(SELECT, params, MAPPER);
        Integer total = jdbc.queryForObject(COUNT, params, Integer.class);
        return new Page(items, total == null ? 0 : total);
    }

    @Override
    public Optional<MissionView> findVisible(UUID tenantId, UUID userId, UUID missionId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", tenantId).addValue("user", userId).addValue("mission", missionId);
        return jdbc.query(BY_ID, params, MAPPER).stream().findFirst();
    }

    private static MapSqlParameterSource baseParams(UUID tenantId, UUID userId, String query) {
        String like = (query == null || query.isBlank()) ? null : "%" + query.strip() + "%";
        return new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("user", userId)
                .addValue("q", like);
    }
}
