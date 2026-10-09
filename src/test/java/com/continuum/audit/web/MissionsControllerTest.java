package com.continuum.audit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.continuum.audit.internal.JdbcMissionDirectory;
import gen.app.missions.model.Mission;
import gen.app.missions.model.MissionPage;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/**
 * Vérifie {@link MissionsController} (contrat {@code app/missions.yaml}) sur base réelle : la liste et
 * le détail ne renvoient que les missions dont l'utilisateur peut lire l'unité ({@code mission.read},
 * §2.1.6), avec pagination et filtre texte. Base via {@code -Dit.db.url}.
 */
class MissionsControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final UUID MISSION_IG = UUID.fromString("f0000000-0000-0000-0000-000000000001");
    private static final UUID MISSION_OTHER = UUID.fromString("f0000000-0000-0000-0000-000000000002");

    private static DataSource dataSource;
    private static MissionsController controller;

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
        run(new ClassPathResource("mission-read-seed.sql"));

        controller = new MissionsController(new JdbcMissionDirectory(dataSource));
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
    void listReturnsOnlyMissionsInScope() {
        authenticateAs(USER);
        MissionPage page = controller.listMissions(0, 25, null, null, null, null, null, null, null, null, false)
                .getBody();

        assertThat(page).isNotNull();
        assertThat(page.getTotalItems()).isEqualTo(1);
        assertThat(page.getItems()).hasSize(1);
        Mission m = page.getItems().get(0);
        assertThat(m.getReference()).isEqualTo("MIS-A001");
        assertThat(m.getStatus().getLabel()).isEqualTo("En cours");
        assertThat(m.getProgressPercent()).isEqualTo(40);
        assertThat(m.getUnit().getLabel()).isEqualTo("Inspection Générale");
    }

    @Test
    void textFilterMatchesReferenceOrTitle() {
        authenticateAs(USER);
        assertThat(controller.listMissions(0, 25, null, "A001", null, null, null, null, null, null, false)
                .getBody().getItems()).hasSize(1);
        assertThat(controller.listMissions(0, 25, null, "introuvable", null, null, null, null, null, null, false)
                .getBody().getItems()).isEmpty();
    }

    @Test
    void getMissionInScopeReturnsIt() {
        authenticateAs(USER);
        ResponseEntity<Mission> response = controller.getMission(MISSION_IG);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getReference()).isEqualTo("MIS-A001");
    }

    @Test
    void getMissionOutOfScopeIsNotFound() {
        authenticateAs(USER);
        assertThatThrownBy(() -> controller.getMission(MISSION_OTHER))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void strangerSeesNothing() {
        authenticateAs(STRANGER);
        assertThat(controller.listMissions(0, 25, null, null, null, null, null, null, null, null, false)
                .getBody().getItems()).isEmpty();
    }
}
