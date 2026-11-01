package com.continuum.ccm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.continuum.ccm.internal.JdbcCcmControlDirectory;
import com.continuum.ccm.internal.JdbcCcmExceptionDirectory;
import com.continuum.ccm.internal.JdbcCcmRunDirectory;
import com.continuum.iam.internal.JdbcAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import gen.app.ccm.model.CcmOverview;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/**
 * Vérifie la vue d'ensemble du cockpit CCM (contrat {@code app/ccm.yaml} {@code getCcmOverview}, §5) sur
 * base réelle : compte les contrôles actifs, agrège les exceptions dans le périmètre {@code ccm.read}
 * (ouvertes, à trier, tendance par jour) et refuse l'accès sans droit. Base PostgreSQL via {@code -Dit.db.url}.
 */
class CcmOverviewControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID READER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000dd");

    private static DataSource dataSource;
    private static CcmOverviewController controller;

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
        run(pg.resolve("V202610091200__entity_mission.sql"));
        run(pg.resolve("V202610091300__entity_finding.sql"));
        run(pg.resolve("V202610091500__finding_frap.sql"));
        run(pg.resolve("V202610091600__ccm_control.sql"));
        run(pg.resolve("V202610121000__ccm_exception.sql"));
        run(pg.resolve("V202610141000__ccm_run.sql"));
        run(pg.resolve("V202611011000__ccm_exception_run_mission.sql"));
        runCp("ccm-overview-seed.sql");

        controller = new CcmOverviewController(
                new CcmControlAssembler(new JdbcCcmControlDirectory(dataSource, new ObjectMapper()),
                        new JdbcCcmRunDirectory(dataSource)),
                new JdbcCcmExceptionDirectory(dataSource),
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
    void aggregatesControlsAndExceptionsInScope() {
        authenticateAs(READER);
        CcmOverview overview = controller.getCcmOverview().getBody();
        assertThat(overview).isNotNull();
        // 3 contrôles au catalogue, 2 activés ; aucune exécution historisée → ni échec ni dérive.
        assertThat(overview.getControls()).hasSize(3);
        assertThat(overview.getActiveControls()).isEqualTo(2);
        assertThat(overview.getFailingControls()).isZero();
        assertThat(overview.getDriftingControls()).isZero();
        // 4 exceptions : 2 NEW + 1 RETAINED ouvertes, 1 DISMISSED fermée.
        assertThat(overview.getOpenExceptions()).isEqualTo(3);
        assertThat(overview.getExceptionsToTriage()).isEqualTo(2);
        // Tendance : 2 jours, 4 détections au total.
        assertThat(overview.getExceptionTrend()).hasSize(2);
        assertThat(overview.getExceptionTrend().stream().mapToInt(p -> p.getCount()).sum()).isEqualTo(4);
    }

    @Test
    void forbidsWithoutCcmRead() {
        authenticateAs(STRANGER);
        assertThatThrownBy(() -> controller.getCcmOverview())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
