package com.continuum.ccm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.ccm.api.CcmControlDirectory.CcmControl;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Vérifie la lecture du paramétrage des contrôles CCM (CCM-03) sur une <strong>vraie base Oracle</strong>
 * (D4), à parité avec {@link JdbcCcmControlDirectory} : {@code allControls} (tous, avec activation) et
 * {@code activeControls} (activés seulement), {@code RAW(16)}/{@code NUMBER(1)}/JSON. Activé seulement si
 * {@code -Dit.oracle.url=jdbc:oracle:…}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleCcmControlDirectoryTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static DriverManagerDataSource dataSource;
    private static OracleCcmControlDirectory directory;

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
        run(new FileSystemResource(oracle.resolve("V202610091600__ccm_control.sql")));
        run(new ClassPathResource("ccm-controls-oracle-seed.sql"));

        directory = new OracleCcmControlDirectory(dataSource, new ObjectMapper());
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    @Test
    void allControlsReturnsEveryControlWithActivationAndParsedParams() {
        assertThat(directory.allControls(TENANT)).extracting(CcmControl::code)
                .containsExactlyInAnyOrder("SOD-VIREMENTS", "SEUIL-HORAIRE", "COMPTES-DORMANTS");
        assertThat(directory.allControls(TENANT)).filteredOn(c -> c.code().equals("COMPTES-DORMANTS"))
                .singleElement().satisfies(c -> assertThat(c.enabled()).isFalse());
        assertThat(directory.allControls(TENANT)).filteredOn(c -> c.code().equals("SEUIL-HORAIRE"))
                .singleElement().satisfies(c -> assertThat(c.integer("thresholdAmount", 0)).isEqualTo(1000000));
    }

    @Test
    void activeControlsReturnsOnlyEnabled() {
        assertThat(directory.activeControls(TENANT)).extracting(CcmControl::code)
                .containsExactlyInAnyOrder("SOD-VIREMENTS", "SEUIL-HORAIRE");
    }
}
