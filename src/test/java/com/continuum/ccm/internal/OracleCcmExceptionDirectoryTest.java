package com.continuum.ccm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.ccm.api.CcmExceptionDirectory;
import com.continuum.ccm.api.CcmExceptionDirectory.Filter;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie le read model des exceptions CCM (§5.3) sur une <strong>vraie base Oracle</strong> (D4), à
 * parité avec {@link JdbcCcmExceptionDirectory} : périmètre {@code ccm.read} via table de fermeture,
 * exception sans unité visible à la racine du tenant, SQL spécifique (JSON_VALUE, OFFSET/FETCH). Le jeu
 * de test réutilisé donne à l'auditeur 00A1 (UNIT_TREE sur IG) deux exceptions (une sur AG, une sans
 * unité). Activé seulement si {@code -Dit.oracle.url=jdbc:oracle:…}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleCcmExceptionDirectoryTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AUDITOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final UUID EXC_UNIT = UUID.fromString("ce000000-0000-0000-0000-0000000000e1");
    private static final UUID EXC_NOUNIT = UUID.fromString("ce000000-0000-0000-0000-0000000000e2");

    private static DriverManagerDataSource dataSource;
    private static OracleCcmExceptionDirectory directory;

    @BeforeAll
    static void applyAndSeed() {
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
        run(new FileSystemResource(oracle.resolve("platform").resolve("V202609300900__platform_feature_catalog.sql")));
        run(new FileSystemResource(oracle.resolve("platform").resolve("R__feature_catalog.sql")));
        run(new FileSystemResource(oracle.resolve("entity").resolve("V202609301000__entity_iam_structure.sql")));
        run(new FileSystemResource(oracle.resolve("entity").resolve("V202610091200__entity_mission.sql")));
        run(new FileSystemResource(oracle.resolve("entity").resolve("V202610091300__entity_finding.sql")));
        run(new FileSystemResource(oracle.resolve("entity").resolve("V202610091500__finding_frap.sql")));
        run(new FileSystemResource(oracle.resolve("entity").resolve("V202610121000__ccm_exception.sql")));
        run(new ClassPathResource("ccm-triage-oracle-seed.sql"));

        directory = new OracleCcmExceptionDirectory(dataSource);
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    private static Filter all() {
        return new Filter(null, null, null, false);
    }

    @Test
    void readsVisibleExceptionsIncludingUnitLessViaRoot() {
        // L'auditeur UNIT_TREE sur la racine voit l'exception de la sous-unité ET celle sans unité.
        assertThat(directory.search(TENANT, AUDITOR, all(), 0, 25).totalItems()).isEqualTo(2);
        assertThat(directory.findVisible(TENANT, AUDITOR, EXC_UNIT)).isPresent()
                .get().satisfies(r -> assertThat(r.reference()).isEqualTo("CCM-OOB-4"));
        assertThat(directory.findVisible(TENANT, AUDITOR, EXC_NOUNIT)).isPresent();
    }

    @Test
    void filtersByStatus() {
        assertThat(directory.search(TENANT, AUDITOR, new Filter(List.of("NEW"), null, null, false), 0, 25)
                .totalItems()).isEqualTo(2);
        assertThat(directory.search(TENANT, AUDITOR, new Filter(List.of("DISMISSED"), null, null, false), 0, 25)
                .totalItems()).isZero();
    }

    @Test
    void hidesEverythingFromAStranger() {
        assertThat(directory.search(TENANT, STRANGER, all(), 0, 25).totalItems()).isZero();
        assertThat(directory.findVisible(TENANT, STRANGER, EXC_UNIT)).isEmpty();
    }

    @Test
    void aggregatesOverviewStatsInScope() {
        // Les deux exceptions visibles sont NEW → ouvertes et à trier ; la tendance les compte toutes.
        CcmExceptionDirectory.Stats stats = directory.overviewStats(TENANT, AUDITOR);
        assertThat(stats.openExceptions()).isEqualTo(2);
        assertThat(stats.toTriage()).isEqualTo(2);
        assertThat(stats.trend().stream().mapToInt(p -> p.count()).sum()).isEqualTo(2);
        // Hors périmètre : l'étranger n'agrège rien.
        assertThat(directory.overviewStats(TENANT, STRANGER).openExceptions()).isZero();
    }
}
