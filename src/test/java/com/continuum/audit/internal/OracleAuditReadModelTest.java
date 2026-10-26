package com.continuum.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.audit.api.FindingDirectory;
import com.continuum.audit.api.MissionDirectory;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie les read models <strong>Oracle</strong> missions/constats (§6, D4) sur une vraie base Oracle :
 * périmètre via la table de fermeture {@code org_unit_closure} (portée {@code UNIT_TREE}), RAW(16),
 * pagination {@code OFFSET/FETCH}. Activé seulement si {@code -Dit.oracle.url=jdbc:oracle:…}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleAuditReadModelTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void applyAndSeed() {
        String url = System.getProperty("it.oracle.url");
        Path oracle = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "oracle")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(oracle), "Scripts Oracle du migrateur introuvables : " + oracle);

        dataSource = new DriverManagerDataSource(
                url, System.getProperty("it.oracle.user"), System.getProperty("it.oracle.password"));
        dataSource.setDriverClassName("oracle.jdbc.OracleDriver");

        // Schéma Oracle persistant entre exécutions : on repart d'un schéma propre.
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).execute("""
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
        run(new ClassPathResource("audit-oracle-read-seed.sql"));
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    @Test
    void missionsInScopeViaClosureTable() {
        MissionDirectory directory = new OracleMissionDirectory(dataSource);

        MissionDirectory.Page page = directory.listVisible(TENANT, USER, null, 0, 25);
        assertThat(page.totalItems()).isEqualTo(1);
        assertThat(page.items()).singleElement()
                .satisfies(m -> assertThat(m.reference()).isEqualTo("MIS-A001"));

        assertThat(directory.listVisible(TENANT, STRANGER, null, 0, 25).items()).isEmpty();
        assertThat(directory.listVisible(TENANT, USER, "A001", 0, 25).items()).hasSize(1);
    }

    @Test
    void findingsInScopeViaClosureTable() {
        FindingDirectory directory = new OracleFindingDirectory(dataSource);

        FindingDirectory.Page page = directory.searchVisible(TENANT, USER, null, null, null, 0, 25);
        assertThat(page.totalItems()).isEqualTo(1);
        assertThat(page.items()).singleElement()
                .satisfies(f -> assertThat(f.reference()).isEqualTo("CST-A001-01"));

        assertThat(directory.searchVisible(TENANT, STRANGER, null, null, null, 0, 25).items()).isEmpty();
    }
}
