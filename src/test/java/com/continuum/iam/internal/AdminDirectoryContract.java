package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.iam.api.AdminDirectory;
import com.continuum.iam.api.AdminDirectory.UserQuery;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Read models of the System Admin screens (users, positions, org tree), identical assertions on PostgreSQL and
 * Oracle: tenant isolation, filters, paging, nesting, holders/assignments counts, resulting features.
 */
abstract class AdminDirectoryContract {

    static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID T2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID U1 = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    static final UUID U2 = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    static final UUID U_OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
    static final UUID TYPE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    static final UUID CHILD = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    static final UUID POS_TOP = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    static final UUID POS_SUB = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    static final UUID POS_OFF = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    static final UUID ROLE = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    abstract JdbcTemplate platformJdbc();

    abstract JdbcTemplate entityJdbc();

    abstract boolean oracle();

    abstract AdminDirectory directory();

    /** Empties the tables the seed writes to (children first). */
    abstract void clean();

    private Object k(UUID id) {
        if (!oracle()) {
            return id;
        }
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .array();
    }

    private Object bool(boolean b) {
        return oracle() ? (Object) (b ? 1 : 0) : b;
    }

    private String json() {
        return oracle() ? "?" : "?::jsonb";
    }

    @BeforeEach
    void seed() {
        clean();
        JdbcTemplate p = platformJdbc();
        JdbcTemplate e = entityJdbc();
        p.update("INSERT INTO platform.tenant(id, code, label) VALUES (?, 'T1', 'T')", k(T1));
        p.update("INSERT INTO platform.tenant(id, code, label) VALUES (?, 'T2', 'T')", k(T2));
        p.update("INSERT INTO platform.feature(code, module, label_key) VALUES ('admin.user.read', 'ADMIN', 'k')");
        p.update("INSERT INTO platform.feature(code, module, label_key) VALUES ('admin.org.read', 'ADMIN', 'k')");
        user(U1, T1, "insp1.demo", "LOCAL", "ACTIVE");
        user(U2, T1, "zoe.ad", "AD", "LOCKED");
        user(U_OTHER, T2, "insp1.other", "LOCAL", "ACTIVE");
        e.update("INSERT INTO org_unit_type(id, tenant_id, code, label, depth) VALUES (?, ?, 'DIR', "
                + json() + ", 1)", k(TYPE), k(T1), "{\"fr\":\"Direction\",\"en\":\"Directorate\"}");
        e.update("INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label"
                + (oracle() ? "" : ", path") + ") VALUES (?, ?, ?, NULL, 'ROOT', " + json()
                + (oracle() ? "" : ", 'root'::ltree") + ")", k(ROOT), k(T1), k(TYPE),
                "{\"fr\":\"Racine\",\"en\":\"Root\"}");
        e.update("INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label"
                + (oracle() ? "" : ", path") + ") VALUES (?, ?, ?, ?, 'CHILD', " + json()
                + (oracle() ? "" : ", 'root.child'::ltree") + ")", k(CHILD), k(T1), k(TYPE), k(ROOT),
                "{\"fr\":\"Enfant\"}");
        e.update("INSERT INTO role(id, tenant_id, code, label, kind) VALUES (?, ?, 'AUDITOR', " + json()
                + ", 'ORGANISATIONAL')", k(ROLE), k(T1), "{\"fr\":\"Auditeur\"}");
        e.update("INSERT INTO role_feature(role_id, feature_code) VALUES (?, 'admin.user.read')", k(ROLE));
        e.update("INSERT INTO role_feature(role_id, feature_code) VALUES (?, 'admin.org.read')", k(ROLE));
        position(POS_TOP, "TOP", 10, null, true, null);
        position(POS_SUB, "SUB", 5, TYPE, true, POS_TOP);
        position(POS_OFF, "OFF", 1, null, false, null);
        e.update("INSERT INTO position_role(position_id, role_id) VALUES (?, ?)", k(POS_SUB), k(ROLE));
        e.update("INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from)"
                + " VALUES (?, ?, ?, ?, ?, 'HOLDER', CURRENT_DATE - 1)", k(UUID.randomUUID()), k(T1), k(U1),
                k(POS_SUB), k(CHILD));
    }

    private void user(UUID id, UUID tenant, String name, String source, String status) {
        platformJdbc().update("INSERT INTO platform.user_account(id, tenant_id, username, source, status)"
                + " VALUES (?, ?, ?, ?, ?)", k(id), k(tenant), name, source, status);
    }

    private void position(UUID id, String code, int rank, UUID type, boolean active, UUID reportsTo) {
        entityJdbc().update("INSERT INTO position(id, tenant_id, code, label, rank, unit_type_id, scope,"
                + " reports_to_id, max_holders_per_unit, active) VALUES (?, ?, ?, " + json()
                + ", ?, ?, 'UNIT', ?, 2, ?)",
                k(id), k(T1), code, "{\"fr\":\"" + code + " fr\"}", rank, type == null ? null : k(type),
                reportsTo == null ? null : k(reportsTo), bool(active));
    }

    @Test
    void usersAreTenantScopedPagedAndFiltered() {
        var all = directory().listUsers(T1, new UserQuery(null, null, null, null, null, 0, 25));
        assertThat(all.totalItems()).isEqualTo(2);
        assertThat(all.items()).extracting(AdminDirectory.UserRow::username).containsExactly("insp1.demo", "zoe.ad");

        var page2 = directory().listUsers(T1, new UserQuery(null, null, null, null, null, 1, 1));
        assertThat(page2.totalItems()).isEqualTo(2);
        assertThat(page2.items()).extracting(AdminDirectory.UserRow::username).containsExactly("zoe.ad");

        assertThat(directory().listUsers(T1, new UserQuery("ZOE", null, null, null, null, 0, 25)).items())
                .extracting(AdminDirectory.UserRow::username).containsExactly("zoe.ad");
        assertThat(directory().listUsers(T1, new UserQuery(null, null, "AD", "LOCKED", null, 0, 25)).items())
                .hasSize(1);
        assertThat(directory().listUsers(T1, new UserQuery(null, CHILD, null, null, null, 0, 25)).items())
                .extracting(AdminDirectory.UserRow::id).containsExactly(U1);
        assertThat(directory().listUsers(T1, new UserQuery(null, null, null, null, POS_SUB, 0, 25)).items())
                .extracting(AdminDirectory.UserRow::id).containsExactly(U1);
        assertThat(directory().listUsers(T1, new UserQuery(null, null, null, null, POS_TOP, 0, 25)).items())
                .isEmpty();
    }

    @Test
    void findUserIsTenantScoped() {
        assertThat(directory().findUser(T1, U1)).isPresent();
        assertThat(directory().findUser(T1, U_OTHER)).isEmpty();
    }

    @Test
    void positionsCarryRolesFeaturesHoldersAndLabels() {
        var active = directory().listPositions(T1, false);
        assertThat(active).extracting(AdminDirectory.PositionRow::code).containsExactly("TOP", "SUB");
        var sub = active.get(1);
        assertThat(sub.holdersCount()).isEqualTo(1);
        assertThat(sub.unitTypeLabel()).isEqualTo("Direction");
        assertThat(sub.reportsToId()).isEqualTo(POS_TOP);
        assertThat(sub.reportsToLabel()).isEqualTo("TOP fr");
        assertThat(sub.maxHoldersPerUnit()).isEqualTo(2);
        assertThat(sub.roles()).extracting(AdminDirectory.RoleRef::code).containsExactly("AUDITOR");
        assertThat(sub.resultingFeatures()).containsExactly("admin.org.read", "admin.user.read");
        assertThat(directory().listPositions(T1, true)).hasSize(3);
        assertThat(directory().listPositions(T2, true)).isEmpty();
    }

    @Test
    void orgTreeNestsChildrenWithLocaleAndCounts() {
        var tree = directory().orgTree(T1, "en");
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).label()).isEqualTo("Root");
        assertThat(tree.get(0).typeLabel()).isEqualTo("Directorate");
        assertThat(tree.get(0).children()).hasSize(1);
        var child = tree.get(0).children().get(0);
        assertThat(child.label()).isEqualTo("Enfant");
        assertThat(child.assignmentsCount()).isEqualTo(1);
        assertThat(directory().orgTree(T2, "fr")).isEmpty();
    }

    static List<String> entityTables() {
        return List.of("assignment", "position_role", "position", "role_feature", "role", "org_unit", "org_unit_type");
    }

    static List<String> platformTables() {
        return List.of("platform.user_account", "platform.tenant", "platform.feature");
    }
}
