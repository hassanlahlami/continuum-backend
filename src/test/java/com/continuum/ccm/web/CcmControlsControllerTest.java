package com.continuum.ccm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.continuum.ccm.api.CcmControlCatalog;
import com.continuum.ccm.internal.JdbcCcmControlDirectory;
import com.continuum.iam.internal.JdbcAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import gen.app.ccm.model.CcmControl;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/**
 * Vérifie la bibliothèque de contrôles CCM (contrat {@code app/ccm.yaml}, §5) sur base réelle :
 * {@code listCcmControls}/{@code getCcmControl} fusionnent le catalogue du pack avec le paramétrage du
 * tenant, filtrent (catégorie/activation), exigent {@code ccm.read}. Base via {@code -Dit.db.url}.
 */
class CcmControlsControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID READER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000dd");

    private static DataSource dataSource;
    private static CcmControlsController controller;

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

        run(pg.resolve("V202609300900__platform_feature_catalog.sql"));
        run(pg.resolve("R__feature_catalog.sql"));
        run(pg.resolve("V202609301000__entity_iam_structure.sql"));
        run(pg.resolve("V202610091600__ccm_control.sql"));
        runCp("ccm-controls-seed.sql");

        controller = new CcmControlsController(
                new JdbcCcmControlDirectory(dataSource, new ObjectMapper()),
                new JdbcAuthorizationService(dataSource));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void run(Path script) {
        execute(new FileSystemResource(script));
    }

    private static void runCp(String name) {
        execute(new ClassPathResource(name));
    }

    private static void execute(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    private static void authenticateAs(UUID user) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none")
                .claim("tenant_id", TENANT.toString())
                .claim("continuum_user_id", user.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void listsCatalogMergedWithTenantConfig() {
        authenticateAs(READER);
        List<CcmControl> all = controller.listCcmControls(null, null, null).getBody();
        assertThat(all).extracting(CcmControl::getCode)
                .containsExactlyInAnyOrder("SOD-VIREMENTS", "SEUIL-HORAIRE", "COMPTES-DORMANTS");
        // Activation reflète ccm_control ; chaque contrôle porte ses définitions de paramètres (catalogue).
        assertThat(all).allSatisfy(c -> assertThat(c.getParameterDefinitions()).isNotEmpty());
        assertThat(all).filteredOn(c -> c.getCode().equals("COMPTES-DORMANTS"))
                .singleElement().satisfies(c -> assertThat(c.getActive()).isFalse());
    }

    @Test
    void filtersByActiveAndCategory() {
        authenticateAs(READER);
        assertThat(controller.listCcmControls(null, null, true).getBody()).hasSize(2);
        assertThat(controller.listCcmControls(null, "ACCESS", null).getBody())
                .singleElement().satisfies(c -> assertThat(c.getCode()).isEqualTo("SOD-VIREMENTS"));
    }

    @Test
    void getReturnsOneControlWithSettings() {
        authenticateAs(READER);
        CcmControl c = controller.getCcmControl(CcmControlCatalog.idOf("SEUIL-HORAIRE")).getBody();
        assertThat(c).isNotNull();
        assertThat(c.getCode()).isEqualTo("SEUIL-HORAIRE");
        assertThat(c.getCategory()).isEqualTo(CcmControl.CategoryEnum.TRANSACTIONS);
        assertThat(c.getSettings().getParameters()).containsKey("thresholdAmount");
    }

    @Test
    void getUnknownControlIsNotFound() {
        authenticateAs(READER);
        assertThatThrownBy(() -> controller.getCcmControl(UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void forbidsWithoutCcmRead() {
        authenticateAs(STRANGER);
        assertThatThrownBy(() -> controller.listCcmControls(null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
