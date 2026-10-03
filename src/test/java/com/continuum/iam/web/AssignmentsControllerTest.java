package com.continuum.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.audittrail.internal.JdbcAuditTrailService;
import com.continuum.iam.internal.JdbcAssignmentService;
import com.continuum.iam.internal.JdbcSegregationOfDutiesService;
import gen.admin.iam.model.Assignment;
import gen.admin.iam.model.AssignmentInput;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
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
 * Vérifie {@link AssignmentsController} (contrat {@code admin/tenancy-iam.yaml}) sur base réelle :
 * {@code POST /assignments} crée l'affectation (201), applique le contrôle SoD statique (409) et
 * l'interdiction d'auto-octroi (422). Le <em>maker</em> vient du jeton. Base via {@code -Dit.db.url}.
 */
class AssignmentsControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID UNIT = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final UUID POS_READ = UUID.fromString("d0000000-0000-0000-0000-000000000003");
    private static final UUID POS_VALIDATE = UUID.fromString("d0000000-0000-0000-0000-000000000002");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static AssignmentsController controller;

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
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");

        run(new FileSystemResource(pg.resolve("V202609300900__platform_feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("R__feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("V202609301000__entity_iam_structure.sql")));
        run(new FileSystemResource(pg.resolve("V202609301100__entity_audit_trail.sql")));
        run(new ClassPathResource("iam-sod-assign-seed.sql"));

        AuditTrailService audit = new JdbcAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
        controller = new AssignmentsController(new JdbcAssignmentService(
                dataSource, audit, new JdbcSegregationOfDutiesService(dataSource, audit)));
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    private static void authenticateAs(UUID actorUserId) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none")
                .claim("tenant_id", TENANT.toString())
                .claim("continuum_user_id", actorUserId.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void resetState() {
        SecurityContextHolder.clearContext();
        // Les appels directs (hors transaction Spring) ne rollbackent pas : on retire les lignes
        // créées par les tests pour revenir à l'état du seed (b2 ne détient que finding.write).
        jdbc.update("DELETE FROM assignment WHERE user_id = ? AND position_id IN (?, ?)",
                TARGET, POS_READ, POS_VALIDATE);
        jdbc.update("DELETE FROM audit_event WHERE tenant_id = ?", TENANT);
    }

    private static AssignmentInput input(UUID userId, UUID positionId) {
        return new AssignmentInput()
                .userId(userId)
                .positionId(positionId)
                .unitId(UNIT)
                .kind(AssignmentInput.KindEnum.HOLDER)
                .validFrom(LocalDate.now());
    }

    @Test
    void createsAssignmentReturns201AndPersists() {
        authenticateAs(ACTOR);

        ResponseEntity<Assignment> response = controller.createAssignment(input(TARGET, POS_READ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Assignment body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getId()).isNotNull();
        assertThat(body.getState()).isEqualTo(Assignment.StateEnum.ACTIVE);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM assignment WHERE user_id = ? AND position_id = ?",
                Integer.class, TARGET, POS_READ)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'admin.assignment.manage' AND object_id = ?",
                Integer.class, body.getId().toString())).isEqualTo(1);
    }

    @Test
    void staticSodConflictReturns409() {
        authenticateAs(ACTOR);

        assertThatThrownBy(() -> controller.createAssignment(input(TARGET, POS_VALIDATE)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void selfGrantReturns422() {
        authenticateAs(ACTOR);

        assertThatThrownBy(() -> controller.createAssignment(input(ACTOR, POS_READ)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }
}
