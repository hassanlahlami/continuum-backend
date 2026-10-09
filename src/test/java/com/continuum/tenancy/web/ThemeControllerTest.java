package com.continuum.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.tenancy.internal.JdbcThemeDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie {@link ThemeController} (contrat {@code public/public.yaml}, §1.6.3) sur base réelle : la
 * feuille CSS porte les tokens {@code --ct-*} du tenant, avec ETag et 304. Base via {@code -Dit.db.url}.
 */
class ThemeControllerTest {

    private static DataSource dataSource;
    private static ThemeController controller;

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
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");

        run(new FileSystemResource(pg.resolve("V202609301200__platform_tenant.sql")));
        run(new FileSystemResource(pg.resolve("V202610091400__platform_tenant_theme.sql")));

        jdbc.update("INSERT INTO platform.tenant (id, code, label, active) VALUES "
                + "('11111111-1111-1111-1111-111111111111', 'BANQUE-DEMO', 'Banque Démo', true)");
        jdbc.update("INSERT INTO tenant_theme (tenant_id, tokens, version) VALUES "
                + "('11111111-1111-1111-1111-111111111111', ?::jsonb, 1)",
                "{\"--ct-color-primary\":\"#0f766e\",\"--evil\":\"x;}\"}");

        controller = new ThemeController(new JdbcThemeDirectory(dataSource, new ObjectMapper()));
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    @Test
    void servesTenantTokensAsCssWithEtag() {
        ResponseEntity<String> response = controller.getThemeStylesheet("BANQUE-DEMO", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/css");
        assertThat(response.getHeaders().getETag()).isEqualTo("\"BANQUE-DEMO-1\"");
        assertThat(response.getBody()).contains(":root{").contains("--ct-color-primary:#0f766e;");
        // Seuls les tokens --ct-* sont émis, et l'injection est neutralisée.
        assertThat(response.getBody()).doesNotContain("--evil");
    }

    @Test
    void returns304WhenEtagMatches() {
        ResponseEntity<String> response = controller.getThemeStylesheet("BANQUE-DEMO", "\"BANQUE-DEMO-1\"");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void unknownEntityServesEmptyRoot() {
        ResponseEntity<String> response = controller.getThemeStylesheet("INCONNU", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"INCONNU-0\"");
        assertThat(response.getBody()).contains(":root{");
    }
}
