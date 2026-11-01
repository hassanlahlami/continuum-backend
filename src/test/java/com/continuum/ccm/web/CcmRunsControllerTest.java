package com.continuum.ccm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.continuum.ccm.api.CcmControlCatalog;
import com.continuum.ccm.api.CcmRunDirectory;
import com.continuum.ccm.api.CcmRunDirectory.RunRecord;
import com.continuum.ccm.api.CcmRunDirectory.Trigger;
import com.continuum.ccm.internal.JdbcCcmRunDirectory;
import com.continuum.iam.internal.JdbcAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import gen.app.ccm.model.CcmRun;
import gen.app.ccm.model.CcmRunPage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
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
 * Vérifie l'exposition HTTP de l'historique des exécutions CCM (contrat {@code app/ccm.yaml}
 * {@code listCcmControlRuns}/{@code getCcmRun}, §5) sur base réelle : pagination par contrôle (plus récent
 * d'abord), lecture par identifiant, {@code ccm.read} exigé. Base PostgreSQL via {@code -Dit.db.url}.
 */
class CcmRunsControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID READER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final UUID SEUIL = CcmControlCatalog.idOf("SEUIL-HORAIRE");

    private static DataSource dataSource;
    private static CcmRunDirectory runs;
    private static CcmRunsController controller;

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
        run(pg.resolve("V202610091200__entity_mission.sql"));
        run(pg.resolve("V202610091300__entity_finding.sql"));
        run(pg.resolve("V202610091500__finding_frap.sql"));
        run(pg.resolve("V202610121000__ccm_exception.sql"));
        run(pg.resolve("V202610141000__ccm_run.sql"));
        run(pg.resolve("V202611011000__ccm_exception_run_mission.sql"));
        runCp("ccm-controls-seed.sql");

        runs = new JdbcCcmRunDirectory(dataSource);
        controller = new CcmRunsController(runs, new JdbcAuthorizationService(dataSource), new ObjectMapper());

        OffsetDateTime t0 = OffsetDateTime.now().minusHours(3);
        runs.record(new RunRecord(TENANT, "SEUIL-HORAIRE", Trigger.SCHEDULE, t0, t0.plusSeconds(5),
                1000L, 2, "CBS", Map.of("thresholdAmount", 1_000_000), null));
        runs.record(new RunRecord(TENANT, "SEUIL-HORAIRE", Trigger.SCHEDULE, t0.plusHours(1),
                t0.plusHours(1).plusSeconds(4), 1100L, 0, "CBS", null, "connexion CBS perdue"));
        runs.record(new RunRecord(TENANT, "COMPTES-DORMANTS", Trigger.MANUAL, t0.plusHours(2),
                t0.plusHours(2).plusSeconds(2), null, 1, null, null, null));
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
    void listsRunsOfAControlNewestFirst() {
        authenticateAs(READER);
        CcmRunPage page = controller.listCcmControlRuns(SEUIL, 0, 25).getBody();
        assertThat(page).isNotNull();
        assertThat(page.getTotalItems()).isEqualTo(2);
        CcmRun newest = page.getItems().get(0);
        assertThat(newest.getStatus()).isEqualTo(CcmRun.StatusEnum.FAILED);
        assertThat(newest.getControlId()).isEqualTo(SEUIL);
        assertThat(newest.getControlLabel()).isNotBlank();
        assertThat(newest.getProblem()).isNotNull();
    }

    @Test
    void getReturnsOneRunById() {
        authenticateAs(READER);
        UUID runId = controller.listCcmControlRuns(SEUIL, 0, 25).getBody().getItems().get(0).getId();
        CcmRun run = controller.getCcmRun(runId).getBody();
        assertThat(run).isNotNull();
        assertThat(run.getId()).isEqualTo(runId);
        assertThat(run.getParametersUsed()).isNotNull();
    }

    @Test
    void getUnknownRunIsNotFound() {
        authenticateAs(READER);
        assertThatThrownBy(() -> controller.getCcmRun(UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void listUnknownControlIsNotFound() {
        authenticateAs(READER);
        assertThatThrownBy(() -> controller.listCcmControlRuns(UUID.randomUUID(), 0, 25))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void forbidsWithoutCcmRead() {
        authenticateAs(STRANGER);
        assertThatThrownBy(() -> controller.listCcmControlRuns(SEUIL, 0, 25))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
