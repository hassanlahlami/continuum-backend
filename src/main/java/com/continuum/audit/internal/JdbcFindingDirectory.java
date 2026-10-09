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
 * Lecture des constats (plan §6), PostgreSQL. Le périmètre est appliqué dans la requête (§2.1.6) :
 * un constat n'est visible que si l'utilisateur détient {@code finding.read} sur son unité auditée
 * (portées {@code TENANT}/{@code UNIT}/{@code UNIT_TREE} via {@code ltree}), module {@code FINDING}
 * activé et restrictions de délégation respectées. Instancié par dialecte via
 * {@link AuditReadModelConfig}.
 */
public class JdbcFindingDirectory implements FindingDirectory {

    private static final String VISIBLE = """
            EXISTS (
              SELECT 1 FROM assignment a
              JOIN position p        ON p.id = a.position_id AND p.active
              JOIN position_role pr  ON pr.position_id = p.id
              JOIN role_feature rf   ON rf.role_id = pr.role_id AND rf.feature_code = 'finding.read'
              JOIN platform.feature ft ON ft.code = rf.feature_code AND NOT ft.deprecated
              JOIN platform.tenant_module tm
                                     ON tm.tenant_id = a.tenant_id AND tm.module = ft.module AND tm.enabled
              LEFT JOIN org_unit hu  ON hu.id = a.org_unit_id
              WHERE a.tenant_id = :tenant AND a.user_id = :user
                AND CURRENT_DATE >= a.valid_from AND (a.valid_to IS NULL OR CURRENT_DATE <= a.valid_to)
                AND (   p.scope = 'TENANT'
                     OR (p.scope = 'UNIT'      AND hu.id = fd.org_unit_id)
                     OR (p.scope = 'UNIT_TREE' AND fu.path <@ hu.path))
                AND (NOT EXISTS (SELECT 1 FROM assignment_feature_restriction r WHERE r.assignment_id = a.id)
                     OR EXISTS  (SELECT 1 FROM assignment_feature_restriction r
                                 WHERE r.assignment_id = a.id AND r.feature_code = 'finding.read'))
            )
            """;

    private static final String FROM_WHERE = """
            FROM finding fd
            LEFT JOIN org_unit fu ON fu.id = fd.org_unit_id
            LEFT JOIN mission mi  ON mi.id = fd.mission_id
            WHERE fd.tenant_id = :tenant
              AND (CAST(:q AS text) IS NULL OR fd.reference ILIKE CAST(:q AS text) OR fd.title ILIKE CAST(:q AS text))
              AND """ + " " + VISIBLE;

    private static final String SELECT = """
            SELECT fd.id, fd.reference, fd.title, fd.status_code, fd.status_label,
                   fd.criticality_code, fd.criticality_label, fd.source, fd.mission_id, mi.title AS mission_title,
                   fd.created_at, fd.version
            """ + FROM_WHERE + """
            ORDER BY fd.reference
            LIMIT :size OFFSET :offset
            """;

    private static final String COUNT = "SELECT count(*) " + FROM_WHERE;

    private static final RowMapper<FindingView> MAPPER = (rs, i) -> new FindingView(
            rs.getObject("id", UUID.class),
            rs.getString("reference"),
            rs.getString("title"),
            rs.getString("status_code"),
            rs.getString("status_label"),
            rs.getString("criticality_code"),
            rs.getString("criticality_label"),
            rs.getString("source"),
            rs.getObject("mission_id", UUID.class),
            rs.getString("mission_title"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getInt("version"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcFindingDirectory(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public Page searchVisible(UUID tenantId, UUID userId, String query, int page, int size) {
        String like = (query == null || query.isBlank()) ? null : "%" + query.strip() + "%";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", tenantId).addValue("user", userId).addValue("q", like)
                .addValue("size", size).addValue("offset", (long) page * size);
        List<FindingView> items = jdbc.query(SELECT, params, MAPPER);
        Integer total = jdbc.queryForObject(COUNT, params, Integer.class);
        return new Page(items, total == null ? 0 : total);
    }
}
