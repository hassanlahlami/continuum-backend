package com.continuum.iam.internal;

import com.continuum.iam.api.AdminDirectory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link AdminDirectory} for PostgreSQL and <strong>Oracle</strong> (D4): the SQL is portable; only UUID binding
 * ({@code RAW(16)}), JSON label reading and paging syntax differ. i18n labels are parsed in Java.
 */
public class JdbcAdminDirectory implements AdminDirectory {

    private static final TypeReference<Map<String, String>> LABEL_TYPE = new TypeReference<>() { };
    private static final String ACTIVE_ASSIGNMENT = "(a.valid_from <= CURRENT_DATE"
            + " AND (a.valid_to IS NULL OR a.valid_to >= CURRENT_DATE))";
    private static final String USER_SELECT = "SELECT u.id, u.username, u.source, u.status, u.mfa_configured,"
            + " u.last_login, u.version FROM platform.user_account u";

    private record RawUnit(UUID id, UUID parent, String code, String label, UUID type) { }

    private final JdbcTemplate jdbc;
    private final boolean oracle;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcAdminDirectory(DataSource dataSource, boolean oracle) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.oracle = oracle;
    }

    @Override
    public UserPageView listUsers(UUID tenantId, UserQuery q) {
        StringBuilder where = new StringBuilder(" WHERE u.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(key(tenantId));
        if (q.text() != null && !q.text().isBlank()) {
            where.append(" AND LOWER(u.username) LIKE ?");
            args.add("%" + q.text().trim().toLowerCase() + "%");
        }
        if (q.source() != null) {
            where.append(" AND u.source = ?");
            args.add(q.source());
        }
        if (q.accountStatus() != null) {
            where.append(" AND u.status = ?");
            args.add(q.accountStatus());
        }
        if (q.positionId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM assignment a WHERE a.user_id = u.id AND a.position_id = ? AND ")
                    .append(ACTIVE_ASSIGNMENT).append(")");
            args.add(key(q.positionId()));
        }
        if (q.unitId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM assignment a WHERE a.user_id = u.id AND a.org_unit_id = ? AND ")
                    .append(ACTIVE_ASSIGNMENT).append(")");
            args.add(key(q.unitId()));
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM platform.user_account u" + where,
                Long.class, args.toArray());
        long offset = (long) q.page() * q.size();
        List<Object> pageArgs = new ArrayList<>(args);
        String paging;
        if (oracle) {
            paging = " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
            pageArgs.add(offset);
            pageArgs.add(q.size());
        } else {
            paging = " LIMIT ? OFFSET ?";
            pageArgs.add(q.size());
            pageArgs.add(offset);
        }
        List<UserRow> rows = jdbc.query(USER_SELECT + where + " ORDER BY u.username, u.id" + paging,
                this::userRow, pageArgs.toArray());
        return new UserPageView(rows, total == null ? 0 : total);
    }

    @Override
    public long countUsersWithoutAssignment(UUID tenantId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM platform.user_account u WHERE u.tenant_id = ?"
                + " AND u.status <> 'DISABLED' AND NOT EXISTS (SELECT 1 FROM assignment a WHERE a.user_id = u.id"
                + " AND " + ACTIVE_ASSIGNMENT + ")", Long.class, key(tenantId));
        return n == null ? 0 : n;
    }

    @Override
    public Optional<UserRow> findUser(UUID tenantId, UUID userId) {
        return jdbc.query(USER_SELECT + " WHERE u.tenant_id = ? AND u.id = ?", this::userRow,
                key(tenantId), key(userId)).stream().findFirst();
    }

    private UserRow userRow(ResultSet rs, int i) throws SQLException {
        var ts = rs.getTimestamp("last_login");
        return new UserRow(uuid(rs.getObject("id")), rs.getString("username"), rs.getString("source"),
                rs.getString("status"), rs.getBoolean("mfa_configured"), ts == null ? null : ts.toInstant(),
                rs.getLong("version"));
    }

    @Override
    public List<PositionRow> listPositions(UUID tenantId, boolean includeInactive) {
        Object tenant = key(tenantId);
        Map<UUID, List<String>> featuresByRole = new LinkedHashMap<>();
        jdbc.query("SELECT rf.role_id, rf.feature_code FROM role_feature rf JOIN role r ON r.id = rf.role_id"
                + " WHERE r.tenant_id = ? ORDER BY rf.feature_code", rs -> {
                    featuresByRole.computeIfAbsent(uuid(rs.getObject("role_id")), k -> new ArrayList<>())
                            .add(rs.getString("feature_code"));
                }, tenant);
        Map<UUID, List<RoleRef>> rolesByPosition = new LinkedHashMap<>();
        jdbc.query("SELECT pr.position_id, r.id, r.code, " + text("r.label") + " AS label, r.kind, r.from_pack"
                + " FROM position_role pr JOIN role r ON r.id = pr.role_id WHERE r.tenant_id = ? ORDER BY r.code",
                rs -> {
                    UUID roleId = uuid(rs.getObject("id"));
                    rolesByPosition.computeIfAbsent(uuid(rs.getObject("position_id")), k -> new ArrayList<>())
                            .add(new RoleRef(roleId, rs.getString("code"), label(rs.getString("label")),
                                    rs.getString("kind"), rs.getBoolean("from_pack"),
                                    featuresByRole.getOrDefault(roleId, List.of())));
                }, tenant);
        Map<UUID, Integer> holders = new LinkedHashMap<>();
        jdbc.query("SELECT a.position_id, COUNT(*) AS n FROM assignment a WHERE a.tenant_id = ? AND "
                + ACTIVE_ASSIGNMENT + " GROUP BY a.position_id", rs -> {
                    holders.put(uuid(rs.getObject("position_id")), rs.getInt("n"));
                }, tenant);
        String sql = "SELECT p.id, p.code, " + text("p.label") + " AS label, p.rank, p.unit_type_id,"
                + " " + text("t.label") + " AS type_label, p.scope, p.reports_to_id,"
                + " " + text("rp.label") + " AS reports_label, p.max_holders_per_unit, p.active"
                + " FROM position p LEFT JOIN org_unit_type t ON t.id = p.unit_type_id"
                + " LEFT JOIN position rp ON rp.id = p.reports_to_id WHERE p.tenant_id = ?"
                + (includeInactive ? "" : " AND p.active = " + (oracle ? "1" : "TRUE"))
                + " ORDER BY p.rank DESC, p.code";
        return jdbc.query(sql, (rs, i) -> {
            UUID id = uuid(rs.getObject("id"));
            List<RoleRef> roles = rolesByPosition.getOrDefault(id, List.of());
            List<String> features = roles.stream().flatMap(r -> r.featureCodes().stream()).distinct().sorted()
                    .toList();
            Object unitType = rs.getObject("unit_type_id");
            Object reportsTo = rs.getObject("reports_to_id");
            int max = rs.getInt("max_holders_per_unit");
            Integer maxHolders = rs.wasNull() ? null : max;
            return new PositionRow(id, rs.getString("code"), label(rs.getString("label")), rs.getInt("rank"),
                    unitType == null ? null : uuid(unitType), pick(rs.getString("type_label"), "fr"),
                    rs.getString("scope"), reportsTo == null ? null : uuid(reportsTo),
                    pick(rs.getString("reports_label"), "fr"), maxHolders,
                    rs.getBoolean("active"), roles, features, holders.getOrDefault(id, 0));
        }, tenant);
    }

    @Override
    public List<OrgUnitRow> orgTree(UUID tenantId, String locale) {
        Object tenant = key(tenantId);
        Map<UUID, String> typeLabels = new LinkedHashMap<>();
        jdbc.query("SELECT id, " + text("label") + " AS label FROM org_unit_type WHERE tenant_id = ?", rs -> {
            typeLabels.put(uuid(rs.getObject("id")), pick(rs.getString("label"), locale));
        }, tenant);
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        jdbc.query("SELECT a.org_unit_id, COUNT(*) AS n FROM assignment a WHERE a.tenant_id = ?"
                + " AND a.org_unit_id IS NOT NULL AND " + ACTIVE_ASSIGNMENT + " GROUP BY a.org_unit_id", rs -> {
                    counts.put(uuid(rs.getObject("org_unit_id")), rs.getInt("n"));
                }, tenant);
        List<RawUnit> raws = jdbc.query("SELECT id, parent_id, code, " + text("label") + " AS label, type_id"
                + " FROM org_unit WHERE tenant_id = ? ORDER BY code", (rs, i) -> {
                    Object parent = rs.getObject("parent_id");
                    return new RawUnit(uuid(rs.getObject("id")), parent == null ? null : uuid(parent),
                            rs.getString("code"), pick(rs.getString("label"), locale), uuid(rs.getObject("type_id")));
                }, tenant);
        Map<UUID, List<RawUnit>> byParent = new LinkedHashMap<>();
        for (RawUnit r : raws) {
            byParent.computeIfAbsent(r.parent(), k -> new ArrayList<>()).add(r);
        }
        return children(null, byParent, typeLabels, counts);
    }

    private static List<OrgUnitRow> children(UUID parent, Map<UUID, List<RawUnit>> byParent,
                                             Map<UUID, String> typeLabels, Map<UUID, Integer> counts) {
        List<OrgUnitRow> out = new ArrayList<>();
        for (RawUnit r : byParent.getOrDefault(parent, List.of())) {
            out.add(new OrgUnitRow(r.id(), r.code(), r.label(), r.type(), typeLabels.get(r.type()),
                    counts.getOrDefault(r.id(), 0), children(r.id(), byParent, typeLabels, counts)));
        }
        return out;
    }

    private String text(String column) {
        return oracle ? column : column + "::text";
    }

    private Map<String, String> label(String raw) {
        try {
            return json.readValue(raw, LABEL_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable i18n label: " + raw, e);
        }
    }

    private String pick(String raw, String locale) {
        if (raw == null) {
            return null;
        }
        Map<String, String> m = label(raw);
        String v = m.get(locale == null ? "fr" : locale);
        return v != null ? v : m.get("fr");
    }

    private Object key(UUID id) {
        if (!oracle) {
            return id;
        }
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(Object o) {
        if (o instanceof UUID u) {
            return u;
        }
        ByteBuffer bb = ByteBuffer.wrap((byte[]) o);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
