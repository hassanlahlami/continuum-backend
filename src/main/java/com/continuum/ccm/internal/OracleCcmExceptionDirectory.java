package com.continuum.ccm.internal;

import com.continuum.ccm.api.CcmExceptionDirectory;
import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Lecture des exceptions CCM (plan §5.3) — <strong>dialecte Oracle</strong> (D4), à parité avec
 * {@link JdbcCcmExceptionDirectory}. Portée {@code ccm.read} via la table de fermeture
 * {@code org_unit_closure} ({@code UNIT_TREE}) ; unité effective = unité de l'exception ou racine du
 * tenant (exceptions population). UUID {@code RAW(16)}, label en JSON ({@code JSON_VALUE}), pagination
 * {@code OFFSET/FETCH}.
 */
public class OracleCcmExceptionDirectory implements CcmExceptionDirectory {

    private static final String FROM = """
            FROM ccm_exception ce
            LEFT JOIN org_unit ou ON ou.id = ce.org_unit_id
            LEFT JOIN org_unit eu ON eu.id = COALESCE(ce.org_unit_id,
                   (SELECT id FROM org_unit WHERE tenant_id = ce.tenant_id AND parent_id IS NULL
                    ORDER BY id FETCH FIRST 1 ROW ONLY))
            WHERE ce.tenant_id = :tenant
              AND EXISTS (
                SELECT 1 FROM assignment a
                JOIN position p         ON p.id = a.position_id AND p.active = 1
                JOIN position_role pr   ON pr.position_id = p.id
                JOIN role_feature rf    ON rf.role_id = pr.role_id AND rf.feature_code = 'ccm.read'
                JOIN platform.feature ft ON ft.code = rf.feature_code AND ft.deprecated = 0
                JOIN platform.tenant_module tm
                                        ON tm.tenant_id = a.tenant_id AND tm.module = ft.module AND tm.enabled = 1
                WHERE a.tenant_id = :tenant AND a.user_id = :user
                  AND a.valid_from <= CURRENT_DATE AND (a.valid_to IS NULL OR a.valid_to >= CURRENT_DATE)
                  AND (   p.scope = 'TENANT'
                       OR (p.scope = 'UNIT'      AND a.org_unit_id = eu.id)
                       OR (p.scope = 'UNIT_TREE' AND EXISTS (
                              SELECT 1 FROM org_unit_closure c
                              WHERE c.ancestor_id = a.org_unit_id AND c.descendant_id = eu.id)))
                  AND (NOT EXISTS (SELECT 1 FROM assignment_feature_restriction r WHERE r.assignment_id = a.id)
                       OR EXISTS  (SELECT 1 FROM assignment_feature_restriction r
                                   WHERE r.assignment_id = a.id AND r.feature_code = 'ccm.read'))
              )
            """;

    private static final String SELECT = """
            SELECT ce.id, ce.org_unit_id, ou.code AS unit_code, JSON_VALUE(ou.label, '$.fr') AS unit_label,
                   ce.reference, ce.control_code, ce.title, ce.status_code, ce.status_label,
                   ce.criticality_code, ce.criticality_label, ce.detected_at, ce.assignee_user_id,
                   ce.dismissal_justification, ce.finding_id, ce.run_id, ce.mission_id, ce.source_record
            """ + FROM;

    private static final RowMapper<Row> MAPPER = (rs, i) -> new Row(
            uuid(rs.getBytes("id")),
            uuid(rs.getBytes("org_unit_id")),
            rs.getString("unit_code"),
            rs.getString("unit_label"),
            rs.getString("reference"),
            rs.getString("control_code"),
            rs.getString("title"),
            rs.getString("status_code"),
            rs.getString("status_label"),
            rs.getString("criticality_code"),
            rs.getString("criticality_label"),
            rs.getObject("detected_at", OffsetDateTime.class),
            uuid(rs.getBytes("assignee_user_id")),
            rs.getString("dismissal_justification"),
            uuid(rs.getBytes("finding_id")),
            uuid(rs.getBytes("run_id")),
            uuid(rs.getBytes("mission_id")),
            rs.getString("source_record"));

    private final NamedParameterJdbcTemplate jdbc;

    public OracleCcmExceptionDirectory(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public Optional<Row> findVisible(UUID tenantId, UUID userId, UUID exceptionId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", raw(tenantId)).addValue("user", raw(userId))
                .addValue("exceptionId", raw(exceptionId));
        return jdbc.query(SELECT + " AND ce.id = :exceptionId", params, MAPPER).stream().findFirst();
    }

    @Override
    public Page search(UUID tenantId, UUID userId, Filter filter, int page, int size) {
        StringBuilder where = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", raw(tenantId)).addValue("user", raw(userId));
        if (filter.statusCodes() != null && !filter.statusCodes().isEmpty()) {
            where.append(" AND ce.status_code IN (:statuses)");
            params.addValue("statuses", filter.statusCodes());
        }
        if (filter.criticality() != null && !filter.criticality().isBlank()) {
            where.append(" AND ce.criticality_code = :crit");
            params.addValue("crit", filter.criticality());
        }
        if (filter.unitId() != null) {
            where.append(" AND ce.org_unit_id = :unit");
            params.addValue("unit", raw(filter.unitId()));
        }
        if (filter.assignedToMe()) {
            where.append(" AND ce.assignee_user_id = :user");
        }

        Integer total = jdbc.queryForObject("SELECT count(*) " + FROM + where, params, Integer.class);
        params.addValue("size", size).addValue("offset", (long) page * size);
        List<Row> items = jdbc.query(
                SELECT + where + " ORDER BY ce.detected_at DESC, ce.reference"
                        + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                params, MAPPER);
        return new Page(items, total == null ? 0 : total);
    }

    @Override
    public Stats overviewStats(UUID tenantId, UUID userId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", raw(tenantId)).addValue("user", raw(userId));
        Integer open = jdbc.queryForObject(
                "SELECT count(*) " + FROM + " AND ce.status_code NOT IN ('DISMISSED', 'CONVERTED')",
                params, Integer.class);
        Integer toTriage = jdbc.queryForObject(
                "SELECT count(*) " + FROM + " AND ce.status_code = 'NEW'", params, Integer.class);
        List<TrendPoint> trend = jdbc.query(
                "SELECT CAST(ce.detected_at AS DATE) AS d, count(*) AS c " + FROM
                        + " GROUP BY CAST(ce.detected_at AS DATE) ORDER BY d",
                params, (rs, i) -> new TrendPoint(rs.getDate("d").toLocalDate(), rs.getInt("c")));
        return new Stats(open == null ? 0 : open, toTriage == null ? 0 : toTriage, trend);
    }

    private static byte[] raw(UUID id) {
        if (id == null) {
            return null;
        }
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    private static UUID uuid(byte[] raw) {
        if (raw == null) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(raw);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
