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
        run(pg.resolve("V202610141000__ccm_run.sql"));
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
}
