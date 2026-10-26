package com.continuum.ccm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.continuum.ccm.api.CcmExceptionTriageService.CcmExceptionView;
import com.continuum.ccm.api.CcmExceptionTriageService.IllegalTriageTransitionException;
import com.continuum.ccm.api.CcmExceptionTriageService.JustificationRequiredException;
import com.continuum.ccm.api.CcmExceptionTriageService.TriageCommand;
import com.continuum.ccm.api.CcmExceptionTriageService.TriageNotPermittedException;
import com.continuum.audittrail.internal.OracleAuditTrailService;
import com.continuum.iam.internal.OracleAuthorizationService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie le triage d'une exception CCM (§5.3, D4) sur une <strong>vraie base Oracle</strong> : droit
 * {@code ccm.exception.triage} (UNIT_TREE via table de fermeture, y compris exception sans unité via la
 * racine), transitions, justification à l'écart, verrou optimiste — à parité avec PostgreSQL
 * ({@link JdbcCcmExceptionTriageServiceTest}). Activé seulement si {@code -Dit.oracle.url=jdbc:oracle:…}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleCcmExceptionTriageServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TRIAGER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ASSIGNEE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final UUID EXC_UNIT = UUID.fromString("ce000000-0000-0000-0000-0000000000e1");
    private static final UUID EXC_NOUNIT = UUID.fromString("ce000000-0000-0000-0000-0000000000e2");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jt;
    private static OracleAuditTrailService auditTrail;

    @BeforeAll
    static void applyAndSeed() {
        String url = System.getProperty("it.oracle.url");
        Path oracle = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "oracle")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(oracle), "Scripts Oracle du migrateur introuvables : " + oracle);

        dataSource = new DriverManagerDataSource(
                url, System.getProperty("it.oracle.user"), System.getProperty("it.oracle.password"));
        dataSource.setDriverClassName("oracle.jdbc.OracleDriver");
        jt = new JdbcTemplate(dataSource);

        jt.execute("""
                BEGIN
                  FOR t IN (SELECT table_name FROM user_tables) LOOP
                    EXECUTE IMMEDIATE 'DROP TABLE "' || t.table_name || '" CASCADE CONSTRAINTS PURGE';
                  END LOOP;
                END;
                """);
        run(new FileSystemResource(oracle.resolve("platform").resolve("V202609300900__platform_feature_catalog.sql")));
        run(new FileSystemResource(oracle.resolve("platform").resolve("R__feature_catalog.sql")));
        run(new FileSystemResource(oracle.resolve("entity").resolve("V202609301000__entity_iam_structure.sql")));
        run(new FileSystemResource(oracle.resolve("entity").resolve("V202609301100__entity_audit_trail.sql")));
        run(new FileSystemResource(oracle.resolve("entity").resolve("V202610091200__entity_mission.sql")));
        run(new FileSystemResource(oracle.resolve("entity").resolve("V202610091300__entity_finding.sql")));
        run(new FileSystemResource(oracle.resolve("entity").resolve("V202610091500__finding_frap.sql")));
        run(new FileSystemResource(oracle.resolve("entity").resolve("V202610121000__ccm_exception.sql")));
        run(new ClassPathResource("ccm-triage-oracle-seed.sql"));

        auditTrail = new OracleAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void resetExceptions() {
        jt.update("UPDATE ccm_exception SET status_code='NEW', status_label='À trier', version=0,"
                + " dismissal_justification=NULL, assignee_user_id=NULL, triaged_by=NULL, triaged_at=NULL");
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    private static OracleCcmExceptionTriageService service() {
        return new OracleCcmExceptionTriageService(
                dataSource, new OracleAuthorizationService(dataSource), auditTrail);
    }

    private static String statusOf(UUID exceptionId) {
        return jt.queryForObject("SELECT status_code FROM ccm_exception WHERE id = HEXTORAW(?)",
                String.class, exceptionId.toString().replace("-", "").toUpperCase());
    }

    private static TriageCommand cmd(UUID user, UUID exc, String action, Integer version,
            String justification, UUID assignee) {
        return new TriageCommand(TENANT, user, exc, action, version, justification, assignee);
    }

    @Test
    void retainsAnException() {
        CcmExceptionView result = service().triage(cmd(TRIAGER, EXC_UNIT, "retain", 0, null, null));
        assertThat(result.statusCode()).isEqualTo("RETAINED");
        assertThat(result.version()).isEqualTo(1);
        assertThat(auditTrail.hasActed(TENANT, TRIAGER, "ccm.exception.triage", "CCM_EXCEPTION", EXC_UNIT.toString()))
                .isTrue();
    }

    @Test
    void triagesExceptionWithoutUnitViaTenantRoot() {
        CcmExceptionView result = service().triage(cmd(TRIAGER, EXC_NOUNIT, "retain", 0, null, null));
        assertThat(result.statusCode()).isEqualTo("RETAINED");
    }

    @Test
    void dismissAndReopenRoundTrip() {
        assertThatExceptionOfType(JustificationRequiredException.class).isThrownBy(
                () -> service().triage(cmd(TRIAGER, EXC_UNIT, "dismiss", 0, " ", null)));
        CcmExceptionView dismissed = service().triage(
                cmd(TRIAGER, EXC_UNIT, "dismiss", 0, "Faux positif.", null));
        assertThat(dismissed.statusCode()).isEqualTo("DISMISSED");
        CcmExceptionView reopened = service().triage(cmd(TRIAGER, EXC_UNIT, "reopen", 1, null, null));
        assertThat(reopened.statusCode()).isEqualTo("NEW");
        assertThat(reopened.dismissalJustification()).isNull();
    }

    @Test
    void assignsWithoutChangingStatus() {
        CcmExceptionView result = service().triage(cmd(TRIAGER, EXC_UNIT, "assign", 0, null, ASSIGNEE));
        assertThat(result.assigneeUserId()).isEqualTo(ASSIGNEE);
        assertThat(result.statusCode()).isEqualTo("NEW");
    }

    @Test
    void rejectsIllegalTransition() {
        assertThatExceptionOfType(IllegalTriageTransitionException.class).isThrownBy(
                () -> service().triage(cmd(TRIAGER, EXC_UNIT, "reopen", 0, null, null)));
    }

    @Test
    void forbidsTriageWithoutFeature() {
        assertThatExceptionOfType(TriageNotPermittedException.class).isThrownBy(
                () -> service().triage(cmd(STRANGER, EXC_UNIT, "retain", 0, null, null)));
        assertThat(statusOf(EXC_UNIT)).isEqualTo("NEW");
    }

    @Test
    void offersActionsByStatusAndRight() {
        // assign laisse le statut NEW : la vue renvoyée sert à vérifier availableActions.
        CcmExceptionView view = service().triage(cmd(TRIAGER, EXC_UNIT, "assign", 0, null, ASSIGNEE));
        assertThat(view.statusCode()).isEqualTo("NEW");
        assertThat(service().availableActions(TENANT, TRIAGER, view))
                .containsExactlyInAnyOrder("retain", "dismiss", "assign");
        assertThat(service().availableActions(TENANT, STRANGER, view)).isEmpty();
    }
}
