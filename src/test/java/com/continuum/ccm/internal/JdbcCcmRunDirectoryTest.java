package com.continuum.ccm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.ccm.api.CcmRunDirectory;
import com.continuum.ccm.api.CcmRunDirectory.LatestRun;
import com.continuum.ccm.api.CcmRunDirectory.RunRecord;
import com.continuum.ccm.api.CcmRunDirectory.Status;
import com.continuum.ccm.api.CcmRunDirectory.Trigger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie l'historique des exécutions CCM ({@code ccm_run}) sur base réelle (PostgreSQL) : enregistrement
 * d'un run (statut dérivé du problème) et lecture de la dernière exécution par contrôle. Base via
 * {@code -Dit.db.url}.
 */
class JdbcCcmRunDirectoryTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID LINK_TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static DataSource dataSource;
    private static CcmRunDirectory directory;

    @BeforeAll
    static void applySchema() {
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
        run(pg.resolve("V202609300900__platform_feature_catalog.sql"));
        run(pg.resolve("R__feature_catalog.sql"));
        run(pg.resolve("V202609301000__entity_iam_structure.sql"));
        run(pg.resolve("V202610091200__entity_mission.sql"));
        run(pg.resolve("V202610091300__entity_finding.sql"));
        run(pg.resolve("V202610091500__finding_frap.sql"));
        run(pg.resolve("V202610091600__ccm_control.sql"));
        run(pg.resolve("V202610121000__ccm_exception.sql"));
        run(pg.resolve("V202610141000__ccm_run.sql"));
        run(pg.resolve("V202611011000__ccm_exception_run_mission.sql"));
        directory = new JdbcCcmRunDirectory(dataSource);
    }

    private static void run(Path script) {
        Resource resource = new FileSystemResource(script);
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(resource, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    @Test
    void recordsRunsAndReportsLatestByControl() {
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(2);
        directory.record(new RunRecord(TENANT, "SEUIL-HORAIRE", Trigger.SCHEDULE,
                t0, t0.plusSeconds(10), 1000L, 2, "CBS", Map.of("thresholdAmount", 1_000_000), null));
        directory.record(new RunRecord(TENANT, "SEUIL-HORAIRE", Trigger.SCHEDULE,
                t0.plusHours(1), t0.plusHours(1).plusSeconds(5), 1200L, 0, "CBS", null, "connexion CBS perdue"));
        directory.record(new RunRecord(TENANT, "COMPTES-DORMANTS", Trigger.MANUAL,
                t0.plusMinutes(30), t0.plusMinutes(30).plusSeconds(3), null, 1, null, null, null));

        Map<String, LatestRun> latest = directory.latestByControl(TENANT);
        assertThat(latest).containsOnlyKeys("SEUIL-HORAIRE", "COMPTES-DORMANTS");
        // La dernière exécution de SEUIL-HORAIRE est l'échec (postérieur au succès).
        assertThat(latest.get("SEUIL-HORAIRE").status()).isEqualTo(Status.FAILED);
        assertThat(latest.get("SEUIL-HORAIRE").populationSize()).isEqualTo(1200L);
        // COMPTES-DORMANTS : un seul run, réussi, sans population.
        assertThat(latest.get("COMPTES-DORMANTS").status()).isEqualTo(Status.SUCCEEDED);
        assertThat(latest.get("COMPTES-DORMANTS").populationSize()).isNull();
        assertThat(latest.get("COMPTES-DORMANTS").exceptionsCount()).isEqualTo(1);
    }

    @Test
    void linksNewExceptionsToTheRunThatProducedThem() {
        JdbcTemplate jt = new JdbcTemplate(dataSource);
        jt.update("INSERT INTO ccm_exception (id, tenant_id, control_code, reference, title)"
                + " VALUES (gen_random_uuid(), ?, 'LINK-CTL', 'REF-LINK-1', 'x')", LINK_TENANT);
        jt.update("INSERT INTO ccm_exception (id, tenant_id, control_code, reference, title, detected_at)"
                + " VALUES (gen_random_uuid(), ?, 'LINK-CTL', 'REF-LINK-OLD', 'x', now() - interval '1 day')", LINK_TENANT);
        OffsetDateTime start = OffsetDateTime.now().minusMinutes(1);
        directory.record(new RunRecord(LINK_TENANT, "LINK-CTL", Trigger.SCHEDULE,
                start, OffsetDateTime.now(), null, 1, null, null, null));

        UUID runId = jt.queryForObject("SELECT id FROM ccm_run WHERE control_code = 'LINK-CTL'", UUID.class);
        assertThat(jt.queryForObject(
                "SELECT run_id FROM ccm_exception WHERE reference = 'REF-LINK-1'", UUID.class)).isEqualTo(runId);
        assertThat(jt.queryForObject(
                "SELECT run_id FROM ccm_exception WHERE reference = 'REF-LINK-OLD'", UUID.class)).isNull();
    }
}
