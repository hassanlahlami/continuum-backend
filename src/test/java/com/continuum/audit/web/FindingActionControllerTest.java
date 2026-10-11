package com.continuum.audit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.continuum.audit.internal.JdbcFindingActionService;
import com.continuum.audit.internal.JdbcFindingDirectory;
import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.internal.JdbcAuditTrailService;
import com.continuum.iam.internal.JdbcAuthorizationService;
import com.continuum.iam.internal.JdbcSegregationOfDutiesService;
import gen.app.findings.model.ActionRequest;
import gen.app.findings.model.AvailableAction;
import gen.app.findings.model.Finding;
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
import org.junit.jupiter.api.BeforeEach;
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
 * Vérifie la frontière HTTP de {@link FindingsController} pour les actions (contrat {@code findings.yaml},
 * §2.1.4, D32) sur base réelle : {@code getFinding} sérialise {@code availableActions} décidées par le
 * serveur, et {@code performFindingAction} mappe droit→403, SoD dynamique→409, action inconnue→422.
 * Base via {@code -Dit.db.url}.
 */
class FindingActionControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID READER = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final UUID UNIT_VALIDATOR = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final UUID MISSION = UUID.fromString("f0000000-0000-0000-0000-0000000000a1");
    private static final UUID FINDING = UUID.fromString("11110000-0000-0000-0000-000000000001");

    private static DataSource dataSource;
    private static JdbcTemplate jt;
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
        jt = new JdbcTemplate(dataSource);
        jt.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jt.execute("DROP SCHEMA public CASCADE");
        jt.execute("CREATE SCHEMA public");

        run(new FileSystemResource(pg.resolve("V202609300900__platform_feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("R__feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("V202609301000__entity_iam_structure.sql")));
        run(new FileSystemResource(pg.resolve("V202609301100__entity_audit_trail.sql")));
        run(new FileSystemResource(pg.resolve("V202610091200__entity_mission.sql")));
        run(new FileSystemResource(pg.resolve("V202610091300__entity_finding.sql")));
        run(new FileSystemResource(pg.resolve("V202610091500__finding_frap.sql")));
        run(new ClassPathResource("finding-validate-seed.sql"));

        JdbcAuditTrailService audit = new JdbcAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
        // L'auteur (aa) a écrit le constat : la SoD dynamique doit lui interdire la validation.
        audit.append(new AuditEntry(TENANT, AUTHOR, "finding.write", "FINDING", FINDING.toString(), null));

        JdbcFindingDirectory directory = new JdbcFindingDirectory(dataSource);
        JdbcFindingActionService actions = new JdbcFindingActionService(dataSource, directory,
                new JdbcAuthorizationService(dataSource),
                new JdbcSegregationOfDutiesService(dataSource, audit), audit);
        controller = new FindingsController(directory, actions);
    }

    @BeforeEach
    void resetFinding() {
        jt.update("UPDATE finding SET status_code='OUVERT', status_label='Ouvert', version=0 WHERE id=?", FINDING);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
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

    @Test
    void getFindingExposesValidateActionToAnEligibleUser() {
        authenticateAs(UNIT_VALIDATOR);
        Finding body = controller.getFinding(MISSION, FINDING).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getAvailableActions())
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.getCode()).isEqualTo("validate");
                    assertThat(a.getLabel()).isEqualTo("Valider");
                    assertThat(a.getStyle()).isEqualTo(AvailableAction.StyleEnum.PRIMARY);
                });
    }

    @Test
    void getFindingExposesNoActionToAReader() {
        authenticateAs(READER);
        Finding body = controller.getFinding(MISSION, FINDING).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getAvailableActions()).isEmpty();
    }

    @Test
    void validateReturnsUpdatedFinding() {
        authenticateAs(UNIT_VALIDATOR);
        Finding body = controller.performFindingAction(MISSION, FINDING, "validate", new ActionRequest(0)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getStatus().getCode()).isEqualTo("VALIDATED");
        assertThat(body.getAvailableActions()).isEmpty();
    }

    @Test
    void validateByAReaderIsForbidden() {
        authenticateAs(READER);
        assertThatThrownBy(() -> controller.performFindingAction(MISSION, FINDING, "validate", new ActionRequest(0)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void validateByTheAuthorConflictsOnDynamicSod() {
        authenticateAs(AUTHOR);
        assertThatThrownBy(() -> controller.performFindingAction(MISSION, FINDING, "validate", new ActionRequest(0)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void unknownActionIsUnprocessable() {
        authenticateAs(UNIT_VALIDATOR);
        assertThatThrownBy(() -> controller.performFindingAction(MISSION, FINDING, "frobnicate", new ActionRequest(0)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }
}
