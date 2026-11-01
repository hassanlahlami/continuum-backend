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
import com.continuum.testsupport.OracleTestSchema;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
        OracleTestSchema db = OracleTestSchema.connect().reset();
        dataSource = db.dataSource();
        jt = new JdbcTemplate(dataSource);
        db.platform("V202609300900__platform_feature_catalog.sql");
        db.platform("R__feature_catalog.sql");
        db.entity("V202609301000__entity_iam_structure.sql");
        db.entity("V202609301100__entity_audit_trail.sql");
        db.entity("V202610091200__entity_mission.sql");
        db.entity("V202610091300__entity_finding.sql");
        db.entity("V202610091500__finding_frap.sql");
        db.entity("V202610121000__ccm_exception.sql");
        db.entity("V202610141000__ccm_run.sql");
        db.entity("V202611011000__ccm_exception_run_mission.sql");
        db.seed(new ClassPathResource("ccm-triage-oracle-seed.sql"));

        auditTrail = new OracleAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void resetExceptions() {
        jt.update("UPDATE ccm_exception SET status_code='NEW', status_label='À trier', version=0,"
                + " dismissal_justification=NULL, assignee_user_id=NULL, triaged_by=NULL, triaged_at=NULL");
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
