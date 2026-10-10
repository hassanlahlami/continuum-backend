package com.continuum.ccm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.ccm.api.SodMonitoring;
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
 * Vérifie le contrôle CCM de cumul SoD (plan §5) sur base réelle : un utilisateur détenant deux
 * fonctionnalités d'une règle {@code STATIC}/{@code BLOCK} donne un constat (source {@code CCM}), et le
 * contrôle est <strong>idempotent</strong>. Base via {@code -Dit.db.url}.
 */
class JdbcSodMonitoringTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static SodMonitoring monitoring;

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
        run(new FileSystemResource(pg.resolve("V202610091200__entity_mission.sql")));
        run(new FileSystemResource(pg.resolve("V202610091300__entity_finding.sql")));
        run(new FileSystemResource(pg.resolve("V202610091500__finding_frap.sql")));
        run(new ClassPathResource("iam-sod-seed.sql")); // user aa détient write+validate+review ; règle BLOCK write/validate

        monitoring = new JdbcSodMonitoring(dataSource);
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    @Test
    void createsOneCcmFindingPerCumulThenIsIdempotent() {
        int created = monitoring.detectCumuls(TENANT);
        assertThat(created).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM finding WHERE tenant_id = ? AND source = 'CCM' AND reference LIKE 'CCM-SOD-%'",
                Integer.class, TENANT)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT title FROM finding WHERE source = 'CCM'", String.class))
                .contains("finding.write").contains("finding.validate");

        // Idempotent : relancer le contrôle ne recrée pas le constat.
        assertThat(monitoring.detectCumuls(TENANT)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finding WHERE source = 'CCM'", Integer.class))
                .isEqualTo(1);
    }
}
