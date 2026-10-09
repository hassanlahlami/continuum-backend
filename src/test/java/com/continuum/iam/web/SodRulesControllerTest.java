package com.continuum.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.continuum.iam.internal.JdbcSodRuleDirectory;
import gen.admin.iam.model.SodRule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Vérifie {@link SodRulesController} (contrat {@code admin/tenancy-iam.yaml}, §2.2) sur base réelle :
 * {@code GET /sod-rules} liste les règles SoD du tenant du jeton (statiques et dynamiques), mappées
 * vers le modèle généré. Base via {@code -Dit.db.url} (sinon test neutralisé).
 */
class SodRulesControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private static DataSource dataSource;
    private static SodRulesController controller;

    @BeforeAll
    static void applySchemaAndSeed() {
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

        run(new FileSystemResource(pg.resolve("V202609300900__platform_feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("R__feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("V202609301000__entity_iam_structure.sql")));
        run(new ClassPathResource("iam-sod-seed.sql"));

        controller = new SodRulesController(new JdbcSodRuleDirectory(dataSource));
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("Échec d'application du script " + script, e);
        }
    }

    private static void authenticateFor(UUID tenantId) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none")
                .claim("tenant_id", tenantId.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsSodRulesOfTenantFromToken() {
        authenticateFor(TENANT);

        ResponseEntity<List<SodRule>> response = controller.listSodRules();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<SodRule> rules = response.getBody();
        assertThat(rules).isNotNull().hasSize(3);
        assertThat(rules).allSatisfy(r -> assertThat(r.getId()).isNotNull());

        assertThat(rules)
                .extracting(SodRule::getFeatureA, SodRule::getFeatureB, SodRule::getType, SodRule::getSeverity)
                .containsExactlyInAnyOrder(
                        tuple("finding.write", "finding.validate",
                                SodRule.TypeEnum.STATIC, SodRule.SeverityEnum.BLOCK),
                        tuple("finding.write", "finding.review",
                                SodRule.TypeEnum.STATIC, SodRule.SeverityEnum.WARN),
                        tuple("finding.validate", "finding.review",
                                SodRule.TypeEnum.DYNAMIC, SodRule.SeverityEnum.BLOCK));

        assertThat(rules).filteredOn(r -> r.getType() == SodRule.TypeEnum.STATIC).hasSize(2);
        assertThat(rules).filteredOn(r -> r.getType() == SodRule.TypeEnum.DYNAMIC).hasSize(1);
    }

    @Test
    void returnsEmptyForTenantWithoutRules() {
        authenticateFor(OTHER_TENANT);

        ResponseEntity<List<SodRule>> response = controller.listSodRules();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isEmpty();
    }
}
