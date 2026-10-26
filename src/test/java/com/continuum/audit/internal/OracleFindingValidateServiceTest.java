package com.continuum.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.continuum.audit.api.FindingActionService.ActionNotPermittedException;
import com.continuum.audit.api.FindingActionService.DynamicSodViolationException;
import com.continuum.audit.api.FindingActionService.FindingNotVisibleException;
import com.continuum.audit.api.FindingActionService.StaleFindingException;
import com.continuum.audit.api.FindingActionService.ValidateFindingCommand;
import com.continuum.audit.api.FindingView;
import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.internal.OracleAuditTrailService;
import com.continuum.iam.internal.OracleAuthorizationService;
import com.continuum.iam.internal.OracleSegregationOfDutiesService;
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
 * Vérifie la validation d'un constat (§2.1.4, §2.2) sur une <strong>vraie base Oracle</strong> (D4) :
 * droit unité-ou-mission, SoD dynamique (auteur bloqué), verrou optimiste, journalisation — à parité
 * avec PostgreSQL ({@link JdbcFindingValidateServiceTest}). RAW(16) + table de fermeture. Activé
 * seulement si {@code -Dit.oracle.url=jdbc:oracle:…}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleFindingValidateServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID VALIDATOR = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID READER = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final UUID UNIT_VALIDATOR = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final UUID MISSION = UUID.fromString("f0000000-0000-0000-0000-0000000000a1");
    private static final UUID FINDING = UUID.fromString("11110000-0000-0000-0000-000000000001");

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
        db.seed(new ClassPathResource("finding-validate-oracle-seed.sql"));

        auditTrail = new OracleAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
        auditTrail.append(new AuditEntry(TENANT, AUTHOR, "finding.write", "FINDING", FINDING.toString(), null));
    }

    @BeforeEach
    void resetFinding() {
        jt.update("UPDATE finding SET status_code='OUVERT', status_label='Ouvert', version=0 WHERE reference='CST-A001-01'");
    }

    private static OracleFindingActionService service() {
        return new OracleFindingActionService(dataSource, new OracleFindingDirectory(dataSource),
                new OracleAuthorizationService(dataSource),
                new OracleSegregationOfDutiesService(dataSource, auditTrail), auditTrail);
    }

    private static FindingView load(UUID user) {
        return new OracleFindingDirectory(dataSource).findVisible(TENANT, user, MISSION, FINDING).orElseThrow();
    }

    @Test
    void validatesForTeamMemberWhoDidNotAuthor() {
        FindingView result = service().validate(
                new ValidateFindingCommand(TENANT, VALIDATOR, MISSION, FINDING, 0, "Revue OK"));
        assertThat(result.statusCode()).isEqualTo("VALIDATED");
        assertThat(result.version()).isEqualTo(1);
    }

    @Test
    void validatesViaUnitScopedPermission() {
        assertThat(service().availableActions(TENANT, UNIT_VALIDATOR, MISSION, load(UNIT_VALIDATOR)))
                .containsExactly("validate");
        assertThat(service().validate(new ValidateFindingCommand(TENANT, UNIT_VALIDATOR, MISSION, FINDING, 0, null))
                .statusCode()).isEqualTo("VALIDATED");
    }

    @Test
    void blocksValidationByTheAuthorViaDynamicSod() {
        assertThatExceptionOfType(DynamicSodViolationException.class).isThrownBy(() -> service().validate(
                new ValidateFindingCommand(TENANT, AUTHOR, MISSION, FINDING, 0, null)));
    }

    @Test
    void forbidsValidationWithoutRight() {
        assertThatExceptionOfType(ActionNotPermittedException.class).isThrownBy(() -> service().validate(
                new ValidateFindingCommand(TENANT, READER, MISSION, FINDING, 0, null)));
    }

    @Test
    void hidesFindingFromUsersOutsideScope() {
        assertThatExceptionOfType(FindingNotVisibleException.class).isThrownBy(() -> service().validate(
                new ValidateFindingCommand(TENANT, STRANGER, MISSION, FINDING, 0, null)));
    }

    @Test
    void rejectsStaleVersion() {
        assertThatExceptionOfType(StaleFindingException.class).isThrownBy(() -> service().validate(
                new ValidateFindingCommand(TENANT, VALIDATOR, MISSION, FINDING, 5, null)));
    }

    @Test
    void offersAndWithdrawsValidate() {
        assertThat(service().availableActions(TENANT, VALIDATOR, MISSION, load(VALIDATOR)))
                .containsExactly("validate");
        assertThat(service().availableActions(TENANT, READER, MISSION, load(READER))).isEmpty();
        service().validate(new ValidateFindingCommand(TENANT, VALIDATOR, MISSION, FINDING, 0, null));
        assertThat(service().availableActions(TENANT, VALIDATOR, MISSION, load(VALIDATOR))).isEmpty();
    }
}
