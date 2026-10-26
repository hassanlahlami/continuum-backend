package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.iam.api.AccountLoginStatusService.Decision;
import com.continuum.iam.api.AccountLoginStatusService.Reason;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Vérifie {@link JdbcAccountLoginStatusService} sur base réelle. Base via {@code -Dit.db.url}. */
class JdbcAccountLoginStatusServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SUSPENDED = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID UNKNOWN_TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID EXPIRED = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID NOBODY = UUID.fromString("00000000-0000-0000-0000-0000000000ee");

    private static DataSource dataSource;

    @BeforeAll
    static void applyAndSeed() {
        String url = System.getProperty("it.db.url");
        Assumptions.assumeTrue(url != null && !url.isBlank() && !url.startsWith("${"),
                "Base de test absente : lancer avec -Dit.db.url=… -Dit.db.user=… -Dit.db.password=…");
        Path pg = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "postgresql")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(pg), "Scripts du continuum-migrator introuvables : " + pg);

        dataSource = new DriverManagerDataSource(
                url, System.getProperty("it.db.user"), System.getProperty("it.db.password"));
        JdbcTemplate jt = new JdbcTemplate(dataSource);
        jt.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jt.execute("DROP SCHEMA public CASCADE");
        jt.execute("CREATE SCHEMA public");

        run(new FileSystemResource(pg.resolve("V202609300900__platform_feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("R__feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("V202609301000__entity_iam_structure.sql")));
        run(new FileSystemResource(pg.resolve("V202609301200__platform_tenant.sql")));
        run(new ClassPathResource("iam-authz-seed.sql"));

        jt.update("INSERT INTO platform.tenant(id, code, label, active) VALUES (?, 'T1', 'Actif', true)", TENANT);
        jt.update("INSERT INTO platform.tenant(id, code, label, active) VALUES (?, 'T2', 'Suspendu', false)", SUSPENDED);
        // Affectation echue hier, et affectation d'un tenant suspendu.
        jt.update("INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from, valid_to)"
                + " VALUES ('e0000000-0000-0000-0000-000000000002', ?, ?, 'd0000000-0000-0000-0000-000000000001',"
                + " 'b0000000-0000-0000-0000-000000000002', 'HOLDER', CURRENT_DATE - 30, CURRENT_DATE - 1)",
                TENANT, EXPIRED);
        jt.update("INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from)"
                + " VALUES ('e0000000-0000-0000-0000-000000000003', ?, ?, 'd0000000-0000-0000-0000-000000000001',"
                + " 'b0000000-0000-0000-0000-000000000002', 'HOLDER', CURRENT_DATE)", SUSPENDED, USER);
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    private static Decision status(UUID tenant, UUID user) {
        return new JdbcAccountLoginStatusService(dataSource).loginStatus(tenant, user);
    }

    @Test
    void allowsUserWithActiveAssignment() {
        assertThat(status(TENANT, USER)).isEqualTo(Decision.allow());
    }

    @Test
    void deniesUserWithoutAssignment() {
        assertThat(status(TENANT, NOBODY)).isEqualTo(Decision.deny(Reason.NO_ACTIVE_ASSIGNMENT));
    }

    @Test
    void deniesUserWithExpiredAssignment() {
        assertThat(status(TENANT, EXPIRED)).isEqualTo(Decision.deny(Reason.NO_ACTIVE_ASSIGNMENT));
    }

    @Test
    void deniesWhenTenantSuspended() {
        assertThat(status(SUSPENDED, USER)).isEqualTo(Decision.deny(Reason.TENANT_SUSPENDED));
    }

    @Test
    void deniesUnknownTenant() {
        assertThat(status(UNKNOWN_TENANT, USER)).isEqualTo(Decision.deny(Reason.UNKNOWN_ACCOUNT));
    }
}
