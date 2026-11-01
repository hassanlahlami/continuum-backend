package com.continuum.ccm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.continuum.audittrail.internal.JdbcAuditTrailService;
import com.continuum.ccm.internal.JdbcCcmExceptionConversionService;
import com.continuum.ccm.internal.JdbcCcmExceptionDirectory;
import com.continuum.ccm.internal.JdbcCcmExceptionTriageService;
import com.continuum.iam.internal.JdbcAuthorizationService;
import gen.app.ccm.model.ActionRequest;
import gen.app.ccm.model.CcmException;
import gen.app.ccm.model.CcmExceptionPage;
import gen.app.ccm.model.ConvertCcmExceptionToFinding201Response;
import gen.app.ccm.model.ConvertCcmExceptionToFindingRequest;
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
 * Vérifie la frontière HTTP de {@link CcmController} (contrat {@code app/ccm.yaml}, §5.3, D34) sur base
 * réelle : {@code performCcmExceptionAction} (retenir/écarter) et {@code convertCcmExceptionToFinding}
 * mappent les décisions serveur en 200/201/403/409/422, et renvoient {@code availableActions}. Le
 * contrôleur délègue aux services déjà vérifiés sur les deux dialectes ; test PG via {@code -Dit.db.url}.
 */
class CcmControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AUDITOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final UUID MISSION = UUID.fromString("f0000000-0000-0000-0000-0000000000a1");
    private static final UUID EXC_NEW = UUID.fromString("ce000000-0000-0000-0000-0000000000e1");
    private static final UUID EXC_RETAINED = UUID.fromString("ce000000-0000-0000-0000-0000000000e2");

    private static DataSource dataSource;
    private static JdbcTemplate jt;
    private static CcmController controller;

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

        run(pg.resolve("V202609300900__platform_feature_catalog.sql"));
        run(pg.resolve("R__feature_catalog.sql"));
        run(pg.resolve("V202609301000__entity_iam_structure.sql"));
        run(pg.resolve("V202609301100__entity_audit_trail.sql"));
        run(pg.resolve("V202610091200__entity_mission.sql"));
        run(pg.resolve("V202610091300__entity_finding.sql"));
        run(pg.resolve("V202610091500__finding_frap.sql"));
        run(pg.resolve("V202610121000__ccm_exception.sql"));
        run(pg.resolve("V202610141000__ccm_run.sql"));
        run(pg.resolve("V202611011000__ccm_exception_run_mission.sql"));
        runCp("ccm-web-seed.sql");

        JdbcAuditTrailService audit = new JdbcAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
        JdbcAuthorizationService authz = new JdbcAuthorizationService(dataSource);
        controller = new CcmController(
                new JdbcCcmExceptionTriageService(dataSource, authz, audit),
                new JdbcCcmExceptionConversionService(dataSource, authz, audit),
                new JdbcCcmExceptionDirectory(dataSource));
    }

    @BeforeEach
    void resetExceptions() {
        jt.update("UPDATE ccm_exception SET finding_id=NULL, version=0, dismissal_justification=NULL,"
                + " assignee_user_id=NULL, triaged_by=NULL, triaged_at=NULL");
        jt.update("DELETE FROM finding WHERE source='CCM'");
        jt.update("UPDATE ccm_exception SET status_code='NEW', status_label='À trier' WHERE id=?", EXC_NEW);
        jt.update("UPDATE ccm_exception SET status_code='RETAINED', status_label='Retenue' WHERE id=?", EXC_RETAINED);
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
    void retainReturnsUpdatedExceptionWithActions() {
        authenticateAs(AUDITOR);
        CcmException body = controller.performCcmExceptionAction(EXC_NEW, "retain", new ActionRequest().version(0))
                .getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus().getCode()).isEqualTo("RETAINED");
        assertThat(body.getAvailableActions()).extracting(a -> a.getCode()).containsExactly("assign");
    }

    @Test
    void dismissWithoutJustificationIsUnprocessable() {
        authenticateAs(AUDITOR);
        assertThatThrownBy(() -> controller.performCcmExceptionAction(EXC_NEW, "dismiss", new ActionRequest().version(0)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void triageByStrangerIsForbidden() {
        authenticateAs(STRANGER);
        assertThatThrownBy(() -> controller.performCcmExceptionAction(EXC_NEW, "retain", new ActionRequest().version(0)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void unknownActionIsUnprocessable() {
        authenticateAs(AUDITOR);
        assertThatThrownBy(() -> controller.performCcmExceptionAction(EXC_NEW, "frobnicate", new ActionRequest().version(0)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void convertRetainedExceptionReturns201() {
        authenticateAs(AUDITOR);
        ConvertCcmExceptionToFinding201Response body = controller.convertCcmExceptionToFinding(
                EXC_RETAINED, new ConvertCcmExceptionToFindingRequest(MISSION)).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getMissionId()).isEqualTo(MISSION);
        assertThat(body.getFindingId()).isNotNull();
        assertThat(jt.queryForObject("SELECT count(*) FROM finding WHERE source='CCM'", Integer.class)).isEqualTo(1);
    }

    @Test
    void getReturnsVisibleExceptionWithServerActions() {
        authenticateAs(AUDITOR);
        CcmException body = controller.getCcmException(EXC_NEW).getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus().getCode()).isEqualTo("NEW");
        assertThat(body.getDetectedAt()).isNotNull();
        assertThat(body.getAvailableActions()).extracting(a -> a.getCode())
                .containsExactlyInAnyOrder("retain", "dismiss", "assign");
    }

    @Test
    void getIsNotFoundForStrangerOutsideScope() {
        authenticateAs(STRANGER);
        assertThatThrownBy(() -> controller.getCcmException(EXC_NEW))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void listReturnsVisibleExceptionsAndFiltersByStatus() {
        authenticateAs(AUDITOR);
        CcmExceptionPage all = controller.listCcmExceptions(0, 25, null, null, null, null, null, null, false)
                .getBody();
        assertThat(all).isNotNull();
        assertThat(all.getTotalItems()).isEqualTo(2);

        CcmExceptionPage retained = controller.listCcmExceptions(
                0, 25, null, List.of("RETAINED"), null, null, null, null, false).getBody();
        assertThat(retained).isNotNull();
        assertThat(retained.getItems()).singleElement()
                .satisfies(x -> assertThat(x.getStatus().getCode()).isEqualTo("RETAINED"));
    }

    @Test
    void listIsEmptyForStranger() {
        authenticateAs(STRANGER);
        CcmExceptionPage body = controller.listCcmExceptions(0, 25, null, null, null, null, null, null, false)
                .getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTotalItems()).isZero();
    }

    @Test
    void convertByStrangerIsForbidden() {
        authenticateAs(STRANGER);
        assertThatThrownBy(() -> controller.convertCcmExceptionToFinding(
                EXC_RETAINED, new ConvertCcmExceptionToFindingRequest(MISSION)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
