package com.continuum.audittrail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditRecord;
import com.continuum.audittrail.api.AuditVerification;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
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
    @Order(0)
    void exportReturnsAllEntriesInOrder() {
        List<AuditRecord> export = new JdbcAuditTrailService(dataSource, KEY).export(TENANT);
        assertThat(export).extracting(AuditRecord::seq, AuditRecord::action)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, "finding.write"),
                        org.assertj.core.groups.Tuple.tuple(2L, "finding.review"),
                        org.assertj.core.groups.Tuple.tuple(3L, "finding.validate"));
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

    /**
     * Course sur le {@code seq} : plusieurs threads écrivent simultanément pour le même tenant. La
     * contrainte {@code UNIQUE (tenant_id, seq)} + le réessai de {@link JdbcAuditTrailService#append}
     * doivent sérialiser les écritures — aucune perte, {@code seq} contigus de 1..N, chaîne intacte.
     * (Tenant dédié, indépendant des tests ordonnés ci-dessus.)
     */
    @Test
    @Order(4)
    void concurrentAppendsForSameTenantSerializeIntoAValidChain() throws Exception {
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000c0");
        JdbcAuditTrailService service = new JdbcAuditTrailService(dataSource, KEY);
        int threads = 12;
        int perThread = 10;
        int total = threads * perThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int threadNo = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    service.append(new AuditEntry(tenant, ACTOR, "ccm.run", "CONTROL",
                            "C-" + threadNo + "-" + i, "{\"t\":" + threadNo + ",\"i\":" + i + "}"));
                }
                return null;
            }));
        }
        start.countDown(); // top départ simultané
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS); // propage toute exception (y.c. course non récupérée)
        }
        pool.shutdown();

        AuditVerification result = service.verify(tenant);
        assertThat(result.intact()).isTrue();
        assertThat(result.count()).isEqualTo(total);
        assertThat(service.export(tenant)).extracting(AuditRecord::seq)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, total).boxed().toList());
    }
}
