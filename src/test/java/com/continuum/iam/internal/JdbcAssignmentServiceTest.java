package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.audittrail.internal.JdbcAuditTrailService;
import com.continuum.iam.api.AssignPositionCommand;
import com.continuum.iam.api.AssignmentService;
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

/**
 * Vérifie l'écriture d'affectation (plan §2.1.3, §2.1.8) sur base réelle : création, journalisation
 * dans l'audit signé, et interdiction d'auto-octroi. Base via {@code -Dit.db.url}.
 */
class JdbcAssignmentServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID POSITION = UUID.fromString("d0000000-0000-0000-0000-000000000001");
    private static final UUID UNIT = UUID.fromString("b0000000-0000-0000-0000-000000000002");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

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
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");

        run(new FileSystemResource(pg.resolve("V202609300900__platform_feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("R__feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("V202609301000__entity_iam_structure.sql")));
        run(new FileSystemResource(pg.resolve("V202609301100__entity_audit_trail.sql")));
        run(new ClassPathResource("iam-authz-seed.sql")); // fournit la position CHEF_DIR et l'unité dir_audit
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    private static AssignmentService service() {
        AuditTrailService audit = new JdbcAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
        return new JdbcAssignmentService(dataSource, audit);
    }

    @Test
    void assignCreatesRowAndAuditEntry() {
        UUID id = service().assign(new AssignPositionCommand(
                TENANT, ACTOR, TARGET, POSITION, UNIT, "HOLDER", null));

        assertThat(id).isNotNull();
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM assignment WHERE user_id = ? AND position_id = ?",
                Integer.class, TARGET, POSITION);
        assertThat(rows).isEqualTo(1);

        Integer audits = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'admin.assignment.manage' AND object_id = ?",
                Integer.class, id.toString());
        assertThat(audits).isEqualTo(1);

        assertThat(new JdbcAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8))
                .verify(TENANT).intact()).isTrue();
    }

    @Test
    void selfGrantIsRejected() {
        assertThatThrownBy(() -> service().assign(new AssignPositionCommand(
                TENANT, ACTOR, ACTOR, POSITION, UNIT, "HOLDER", null)))
                .isInstanceOf(AssignmentService.SelfGrantNotAllowedException.class);
    }
}
