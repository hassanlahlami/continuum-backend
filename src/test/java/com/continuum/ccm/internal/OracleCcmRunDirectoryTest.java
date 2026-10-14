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
 * Vérifie l'historique des exécutions CCM ({@code ccm_run}) sur une <strong>vraie base Oracle</strong>
 * (D4), à parité avec {@link JdbcCcmRunDirectoryTest} : enregistrement ({@code SYS_GUID()}, {@code RAW(16)},
 * JSON {@code IS JSON}, liaisons nulles typées) et dernière exécution par contrôle ({@code ROW_NUMBER()}).
 * Activé seulement si {@code -Dit.oracle.url=jdbc:oracle:…}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleCcmRunDirectoryTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static DriverManagerDataSource dataSource;
    private static CcmRunDirectory directory;

    @BeforeAll
    static void applySchema() {
        String url = System.getProperty("it.oracle.url");
        Path oracle = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "oracle")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(oracle), "Scripts Oracle du migrateur introuvables : " + oracle);

        dataSource = new DriverManagerDataSource(
                url, System.getProperty("it.oracle.user"), System.getProperty("it.oracle.password"));
        dataSource.setDriverClassName("oracle.jdbc.OracleDriver");
        JdbcTemplate jt = new JdbcTemplate(dataSource);
        jt.execute("""
                BEGIN
                  FOR t IN (SELECT table_name FROM user_tables) LOOP
                    EXECUTE IMMEDIATE 'DROP TABLE "' || t.table_name || '" CASCADE CONSTRAINTS PURGE';
                  END LOOP;
                END;
                """);
        run(new FileSystemResource(oracle.resolve("V202610141000__ccm_run.sql")));
        directory = new OracleCcmRunDirectory(dataSource);
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
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
        assertThat(latest.get("SEUIL-HORAIRE").status()).isEqualTo(Status.FAILED);
        assertThat(latest.get("SEUIL-HORAIRE").populationSize()).isEqualTo(1200L);
        assertThat(latest.get("COMPTES-DORMANTS").status()).isEqualTo(Status.SUCCEEDED);
        assertThat(latest.get("COMPTES-DORMANTS").populationSize()).isNull();
        assertThat(latest.get("COMPTES-DORMANTS").exceptionsCount()).isEqualTo(1);
    }
}
