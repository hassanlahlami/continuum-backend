package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.audittrail.internal.OracleAuditTrailService;
import com.continuum.iam.api.CreateRoleCommand;
import com.continuum.iam.api.RoleService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie la création de rôle (plan §2.1.1) sur une <strong>vraie base Oracle</strong> (D4) :
 * rôle + {@code role_feature} + journalisation, avec {@code RAW(16)} et libellé {@code IS JSON}.
 *
 * <p>Activé seulement si {@code -Dit.oracle.url=jdbc:oracle:thin:@…} est fourni (schéma courant =
 * {@code PLATFORM}, où atterrissent les objets qualifiés {@code platform.*} et les tables d'entité).
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleRoleServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void applySchema() {
        String url = System.getProperty("it.oracle.url");
        Path oracle = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "oracle")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(oracle), "Scripts Oracle du migrateur introuvables : " + oracle);

        dataSource = new DriverManagerDataSource(
                url, System.getProperty("it.oracle.user"), System.getProperty("it.oracle.password"));
        dataSource.setDriverClassName("oracle.jdbc.OracleDriver");
        jdbc = new JdbcTemplate(dataSource);

        run(oracle.resolve("V202609300900__platform_feature_catalog.sql"));
        run(oracle.resolve("R__feature_catalog.sql"));
        run(oracle.resolve("V202609301000__entity_iam_structure.sql"));
        run(oracle.resolve("V202609301100__entity_audit_trail.sql"));
    }

    private static void run(Path script) {
        try (Connection c = dataSource.getConnection()) {
            Resource r = new FileSystemResource(script);
            ScriptUtils.executeSqlScript(c, new EncodedResource(r, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    private static RoleService service() {
        AuditTrailService audit = new OracleAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
        return new OracleRoleService(dataSource, audit);
    }

    @Test
    void createsRoleWithFeaturesAndAuditEntry() {
        UUID id = service().create(new CreateRoleCommand(
                TENANT, ACTOR, "AUDITEUR_TEST", "Auditeur de test", "ORGANISATIONAL",
                List.of("finding.read", "finding.write")));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM role WHERE id = ? AND tenant_id = ? AND kind = 'ORGANISATIONAL'",
                Integer.class, OracleRoleService.raw(id), OracleRoleService.raw(TENANT))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM role_feature WHERE role_id = ?", Integer.class,
                OracleRoleService.raw(id))).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT JSON_VALUE(label, '$.fr') FROM role WHERE id = ?", String.class,
                OracleRoleService.raw(id))).isEqualTo("Auditeur de test");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'admin.role.manage' AND object_id = ?",
                Integer.class, id.toString())).isEqualTo(1);
        assertThat(new OracleAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8))
                .verify(TENANT).intact()).isTrue();
    }
}
