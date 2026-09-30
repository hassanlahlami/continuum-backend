package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie {@link FeatureCatalogVerifier} sur une base réelle : catalogue conforme, dérive détectée,
 * et schéma absent. Base fournie via {@code -Dit.db.url} (sinon test neutralisé).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FeatureCatalogVerifierTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void applyCatalog() {
        String url = System.getProperty("it.db.url");
        Assumptions.assumeTrue(url != null && !url.isBlank() && !url.startsWith("${"),
                "Base de test absente : lancer avec -Dit.db.url=… -Dit.db.user=… -Dit.db.password=…");

        Path pg = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "postgresql")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(pg), "Scripts du continuum-migrator introuvables : " + pg);

        dataSource = new DriverManagerDataSource(
                url, System.getProperty("it.db.user"), System.getProperty("it.db.password"));
        jdbc = new JdbcTemplate(dataSource);

        // Base propre, indépendamment de l'ordre des classes de test (DB partagée).
        jdbc.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");

        run(new FileSystemResource(pg.resolve("V202609300900__platform_feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("R__feature_catalog.sql")));
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("Échec d'application du script " + script, e);
        }
    }

    @Test
    @Order(1)
    void passesWhenCatalogMatchesRegistry() {
        assertThatCode(() -> new FeatureCatalogVerifier(dataSource).verify()).doesNotThrowAnyException();
    }

    @Test
    @Order(2)
    void failsWithDriftCodeWhenAFeatureIsMissing() {
        jdbc.update("DELETE FROM platform.feature WHERE code = 'ccm.read'");

        assertThatThrownBy(() -> new FeatureCatalogVerifier(dataSource).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FeatureCatalogVerifier.EVENT_CATALOG_DRIFT)
                .hasMessageContaining("ccm.read");
    }

    @Test
    @Order(3)
    void failsWithSchemaCodeWhenCatalogTableMissing() {
        jdbc.execute("DROP SCHEMA platform CASCADE");

        assertThatThrownBy(() -> new FeatureCatalogVerifier(dataSource).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FeatureCatalogVerifier.EVENT_SCHEMA_MISSING);
    }
}
