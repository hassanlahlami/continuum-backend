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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie {@link OracleAccountLoginStatusService} sur une vraie base Oracle (D4). Activé seulement si
 * {@code -Dit.oracle.url=jdbc:oracle:thin:@…} est fourni.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleAccountLoginStatusServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SUSPENDED = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID UNKNOWN_TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID NOBODY = UUID.fromString("00000000-0000-0000-0000-0000000000ee");

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void applySchemaAndSeed() {
        Path oracle = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "oracle")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(oracle), "Scripts Oracle du migrateur introuvables : " + oracle);

        dataSource = new DriverManagerDataSource(System.getProperty("it.oracle.url"),
                System.getProperty("it.oracle.user"), System.getProperty("it.oracle.password"));
        dataSource.setDriverClassName("oracle.jdbc.OracleDriver");

        run(script(oracle, "V202609300900__platform_feature_catalog.sql"));
        run(script(oracle, "R__feature_catalog.sql"));
        run(script(oracle, "V202609301000__entity_iam_structure.sql"));
        run(script(oracle, "V202609301200__platform_tenant.sql"));
        run(new ClassPathResource("iam-oracle-authz-seed.sql"));

        JdbcTemplate jt = new JdbcTemplate(dataSource);
        jt.update("INSERT INTO platform.tenant(id, code, label, active) VALUES"
                + " (HEXTORAW('00000000000000000000000000000001'), 'T1', 'Actif', 1)");
        jt.update("INSERT INTO platform.tenant(id, code, label, active) VALUES"
                + " (HEXTORAW('00000000000000000000000000000002'), 'T2', 'Suspendu', 0)");
    }

    /** Les scripts sont à plat ou rangés par schéma ({@code platform/}, {@code entity/}, ADR 0012). */
    private static Resource script(Path root, String name) {
        for (String dir : new String[] {"", "platform", "entity"}) {
            Path p = root.resolve(dir).resolve(name);
            if (Files.exists(p)) {
                return new FileSystemResource(p);
            }
        }
        throw new IllegalStateException("script Oracle introuvable : " + name);
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    private static Decision status(UUID tenant, UUID user) {
        return new OracleAccountLoginStatusService(dataSource).loginStatus(tenant, user);
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
    void deniesWhenTenantSuspended() {
        assertThat(status(SUSPENDED, USER)).isEqualTo(Decision.deny(Reason.TENANT_SUSPENDED));
    }

    @Test
    void deniesUnknownTenant() {
        assertThat(status(UNKNOWN_TENANT, USER)).isEqualTo(Decision.deny(Reason.UNKNOWN_ACCOUNT));
    }
}
