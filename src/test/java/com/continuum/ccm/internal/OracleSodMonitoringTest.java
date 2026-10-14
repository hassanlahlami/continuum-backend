package com.continuum.ccm.internal;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Vérifie le contrôle CCM de cumul SoD (§5) sur une vraie base Oracle (D4) : un cumul {@code STATIC}/
 * {@code BLOCK} donne une <strong>exception</strong> ({@code ccm_exception}, D34 — pas de constat),
 * idempotente via {@code MERGE} — à parité avec PostgreSQL ({@link JdbcSodMonitoringTest}). Activé
 * seulement si {@code -Dit.oracle.url=jdbc:oracle:…}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleSodMonitoringTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jt;
    private static OracleSodMonitoring monitoring;

    @BeforeAll
    static void applyAndSeed() {
        String url = System.getProperty("it.oracle.url");
        Path oracle = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "oracle")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(oracle), "Scripts Oracle du migrateur introuvables : " + oracle);

        dataSource = new DriverManagerDataSource(
                url, System.getProperty("it.oracle.user"), System.getProperty("it.oracle.password"));
        dataSource.setDriverClassName("oracle.jdbc.OracleDriver");
        jt = new JdbcTemplate(dataSource);

        jt.execute("""
                BEGIN
                  FOR v IN (SELECT view_name FROM user_views) LOOP
                    EXECUTE IMMEDIATE 'DROP VIEW "' || v.view_name || '"';
                  END LOOP;
                  FOR t IN (SELECT table_name FROM user_tables) LOOP
                    EXECUTE IMMEDIATE 'DROP TABLE "' || t.table_name || '" CASCADE CONSTRAINTS PURGE';
                  END LOOP;
                END;
                """);
        run(new FileSystemResource(oracle.resolve("V202609300900__platform_feature_catalog.sql")));
        run(new FileSystemResource(oracle.resolve("R__feature_catalog.sql")));
        run(new FileSystemResource(oracle.resolve("V202609301000__entity_iam_structure.sql")));
        run(new FileSystemResource(oracle.resolve("V202610091200__entity_mission.sql")));
        run(new FileSystemResource(oracle.resolve("V202610091300__entity_finding.sql")));
        run(new FileSystemResource(oracle.resolve("V202610091500__finding_frap.sql")));
        run(new FileSystemResource(oracle.resolve("V202610121000__ccm_exception.sql")));
        run(new ClassPathResource("iam-sod-oracle-seed.sql"));

        monitoring = new OracleSodMonitoring(dataSource);
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    @Test
    void createsOneCcmExceptionPerCumulThenIsIdempotent() {
        int created = monitoring.detectCumuls(TENANT);
        assertThat(created).isEqualTo(1);

        // D34 : exception à trier, aucun constat créé.
        assertThat(jt.queryForObject("SELECT count(*) FROM finding", Integer.class)).isZero();
        assertThat(jt.queryForObject(
                "SELECT count(*) FROM ccm_exception WHERE control_code = 'SOD-VIREMENTS' AND status_code = 'NEW' "
                + "AND reference LIKE 'CCM-SOD-%'", Integer.class)).isEqualTo(1);
        assertThat(jt.queryForObject("SELECT title FROM ccm_exception", String.class))
                .contains("finding.write").contains("finding.validate");
        // Chaîne de preuve portée par l'exception (§5.3).
        assertThat(jt.queryForObject("SELECT JSON_VALUE(source_record, '$.featureA') FROM ccm_exception",
                String.class)).isEqualTo("finding.write");

        // Idempotent : le MERGE ne recrée pas l'exception.
        assertThat(monitoring.detectCumuls(TENANT)).isZero();
        assertThat(jt.queryForObject("SELECT count(*) FROM ccm_exception", Integer.class)).isEqualTo(1);
    }
}
