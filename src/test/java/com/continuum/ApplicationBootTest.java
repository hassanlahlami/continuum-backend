package com.continuum;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.iam.api.AuthorizationService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Démarrage complet de l'application (plan §4.3) : le contexte Spring s'assemble et démarre contre
 * une base réelle migrée, et {@code FeatureCatalogVerifier} valide le catalogue au ready.
 *
 * <p>Prouve le graphe de beans complet (moteur {@code can()}, SoD → {@code AuditTrailService} → clé
 * HMAC, deux chaînes de sécurité). Base fournie via {@code -Dit.db.url} (sinon classe désactivée).
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "it.db.url", matches = "jdbc:.+")
class ApplicationBootTest {

    @DynamicPropertySource
    static void datasourceAndSecrets(DynamicPropertyRegistry registry) {
        String url = System.getProperty("it.db.url");
        String user = System.getProperty("it.db.user");
        String password = System.getProperty("it.db.password");

        migrate(url, user, password);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        // Pas d'appel réseau OIDC au démarrage : un JwtDecoder de test est fourni ci-dessous.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("continuum.audittrail.hmac-key", () -> "boot-test-hmac-key");
    }

    private static void migrate(String url, String user, String password) {
        Path pg = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "postgresql")
                .toAbsolutePath().normalize();
        DataSource ds = new DriverManagerDataSource(url, user, password);
        exec(ds, "DROP SCHEMA IF EXISTS platform CASCADE");
        exec(ds, "DROP SCHEMA public CASCADE");
        exec(ds, "CREATE SCHEMA public");
        script(ds, new FileSystemResource(pg.resolve("V202609300900__platform_feature_catalog.sql")));
        script(ds, new FileSystemResource(pg.resolve("R__feature_catalog.sql")));
        script(ds, new FileSystemResource(pg.resolve("V202609301000__entity_iam_structure.sql")));
        script(ds, new FileSystemResource(pg.resolve("V202609301100__entity_audit_trail.sql")));
    }

    private static void exec(DataSource ds, String sql) {
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static void script(DataSource ds, Resource r) {
        try (Connection c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(r, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + r, e);
        }
    }

    @Autowired
    private AuthorizationService authorizationService;

    @Test
    void contextLoadsAndCoreBeansArePresent() {
        assertThat(authorizationService).isNotNull();
    }

    @TestConfiguration
    static class TestSecurityBeans {
        /** JwtDecoder factice : le resource server se câble sans appeler Keycloak au démarrage. */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none").subject("test").build();
        }
    }
}
