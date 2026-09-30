package com.continuum.audittrail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditVerification;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie {@link JdbcAuditTrailService} sur base réelle (plan §2.3) : chaînage, détection
 * d'altération, et dépendance à la clé HMAC. Base fournie via {@code -Dit.db.url} (sinon neutralisé).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JdbcAuditTrailServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final byte[] KEY = "continuum-test-hmac-key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WRONG_KEY = "another-hmac-key".getBytes(StandardCharsets.UTF_8);

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void applySchemaAndSeed() {
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

        run(new FileSystemResource(pg.resolve("V202609301100__entity_audit_trail.sql")));

        JdbcAuditTrailService service = new JdbcAuditTrailService(dataSource, KEY);
        long s1 = service.append(new AuditEntry(TENANT, ACTOR, "finding.write", "FINDING", "F-1", "{\"v\":1}"));
        long s2 = service.append(new AuditEntry(TENANT, ACTOR, "finding.review", "FINDING", "F-1", "{\"v\":2}"));
        long s3 = service.append(new AuditEntry(TENANT, ACTOR, "finding.validate", "FINDING", "F-1", "{\"v\":3}"));
        assertThat(new long[] {s1, s2, s3}).containsExactly(1L, 2L, 3L);
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("Échec d'application du script " + script, e);
        }
    }

    @Test
    @Order(1)
    void intactChainVerifiesSuccessfully() {
        AuditVerification result = new JdbcAuditTrailService(dataSource, KEY).verify(TENANT);
        assertThat(result.intact()).isTrue();
        assertThat(result.firstBrokenSeq()).isNull();
        assertThat(result.count()).isEqualTo(3);
    }

    @Test
    @Order(2)
    void wrongKeyFailsVerification() {
        AuditVerification result = new JdbcAuditTrailService(dataSource, WRONG_KEY).verify(TENANT);
        assertThat(result.intact()).isFalse();
        assertThat(result.firstBrokenSeq()).isEqualTo(1L);
    }

    @Test
    @Order(3)
    void tamperingIsDetectedAtTheAlteredEntry() {
        jdbc.update("UPDATE audit_event SET payload = ? WHERE tenant_id = ? AND seq = 2",
                "{\"v\":\"tampered\"}", TENANT);

        AuditVerification result = new JdbcAuditTrailService(dataSource, KEY).verify(TENANT);
        assertThat(result.intact()).isFalse();
        assertThat(result.firstBrokenSeq()).isEqualTo(2L);
    }
}
