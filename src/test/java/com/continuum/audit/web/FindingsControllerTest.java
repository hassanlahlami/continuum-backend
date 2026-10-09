package com.continuum.audit.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.audit.internal.JdbcFindingDirectory;
import gen.app.findings.model.Finding;
import gen.app.findings.model.FindingPage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Vérifie {@link FindingsController} (contrat {@code app/findings.yaml}) sur base réelle : la vue
 * transverse ne renvoie que les constats dont l'utilisateur peut lire l'unité ({@code finding.read},
 * §2.1.6), avec pagination et filtre texte. Base via {@code -Dit.db.url}.
 */
class FindingsControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    private static DataSource dataSource;
    private static FindingsController controller;

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

        run(new FileSystemResource(pg.resolve("V202609300900__platform_feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("R__feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("V202609301000__entity_iam_structure.sql")));
        run(new FileSystemResource(pg.resolve("V202610091200__entity_mission.sql")));
        run(new FileSystemResource(pg.resolve("V202610091300__entity_finding.sql")));
        run(new ClassPathResource("finding-read-seed.sql"));

        controller = new FindingsController(new JdbcFindingDirectory(dataSource));
    }

    private static void run(Resource script) {
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

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void searchReturnsOnlyFindingsInScope() {
        authenticateAs(USER);
        FindingPage page = controller.searchFindings(0, 25, null, null, null, null, null, null, null, null, null)
                .getBody();

        assertThat(page).isNotNull();
        assertThat(page.getTotalItems()).isEqualTo(1);
        assertThat(page.getItems()).hasSize(1);
        Finding f = page.getItems().get(0);
        assertThat(f.getReference()).isEqualTo("CST-A001-01");
        assertThat(f.getStatus().getLabel()).isEqualTo("Ouvert");
        assertThat(f.getCriticality().getLabel()).isEqualTo("Élevée");
        assertThat(f.getSource()).isEqualTo(Finding.SourceEnum.CCM);
        assertThat(f.getMissionTitle()).isEqualTo("Inspection agence A001");
    }

    @Test
    void textFilterMatchesReferenceOrTitle() {
        authenticateAs(USER);
        assertThat(controller.searchFindings(0, 25, null, "virements", null, null, null, null, null, null, null)
                .getBody().getItems()).hasSize(1);
        assertThat(controller.searchFindings(0, 25, null, "introuvable", null, null, null, null, null, null, null)
                .getBody().getItems()).isEmpty();
    }

    @Test
    void strangerSeesNothing() {
        authenticateAs(STRANGER);
        assertThat(controller.searchFindings(0, 25, null, null, null, null, null, null, null, null, null)
                .getBody().getItems()).isEmpty();
    }
}
