package com.continuum.ccm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.ccm.api.CcmControlDirectory.CcmControl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
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
 * Vérifie la lecture du paramétrage des contrôles CCM (CCM-03, §5) sur base réelle : seuls les
 * contrôles activés sont renvoyés, avec leurs paramètres JSON désérialisés. Base via {@code -Dit.db.url}.
 */
class JdbcCcmControlDirectoryTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static DataSource dataSource;

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
        JdbcTemplate jt = new JdbcTemplate(dataSource);
        jt.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jt.execute("DROP SCHEMA public CASCADE");
        jt.execute("CREATE SCHEMA public");
        run(pg.resolve("V202610091600__ccm_control.sql"));

        jt.update("INSERT INTO ccm_control(tenant_id, control_code, enabled, params) VALUES (?,?,?,?::jsonb)",
                TENANT, "SEUIL-HORAIRE", true,
                "{\"thresholdAmount\":1000000,\"nonWorkingDays\":[\"SAT\",\"SUN\"],\"excludeChannels\":[\"BATCH\"]}");
        jt.update("INSERT INTO ccm_control(tenant_id, control_code, enabled, params) VALUES (?,?,?,?::jsonb)",
                TENANT, "COMPTES-DORMANTS", true, "{\"dormancyMonths\":12,\"minAmount\":10000}");
        jt.update("INSERT INTO ccm_control(tenant_id, control_code, enabled, params) VALUES (?,?,?,?::jsonb)",
                TENANT, "CONTROLE-DESACTIVE", false, "{}");
    }

    private static void run(Path script) {
        try (Connection c = dataSource.getConnection()) {
            Resource r = new FileSystemResource(script);
            ScriptUtils.executeSqlScript(c, new EncodedResource(r, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    @Test
    void returnsOnlyEnabledControlsWithParsedParams() {
        JdbcCcmControlDirectory directory = new JdbcCcmControlDirectory(dataSource, new ObjectMapper());
        List<CcmControl> controls = directory.activeControls(TENANT);

        assertThat(controls).extracting(CcmControl::code)
                .containsExactlyInAnyOrder("SEUIL-HORAIRE", "COMPTES-DORMANTS");

        CcmControl threshold = controls.stream().filter(c -> c.code().equals("SEUIL-HORAIRE")).findFirst().orElseThrow();
        assertThat(threshold.integer("thresholdAmount", 0)).isEqualTo(1000000);
        assertThat(threshold.params().get("nonWorkingDays")).isEqualTo(List.of("SAT", "SUN"));

        CcmControl dormant = controls.stream().filter(c -> c.code().equals("COMPTES-DORMANTS")).findFirst().orElseThrow();
        assertThat(dormant.integer("dormancyMonths", 0)).isEqualTo(12);
    }
}
