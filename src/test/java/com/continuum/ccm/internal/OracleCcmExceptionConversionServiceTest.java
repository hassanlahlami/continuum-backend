package com.continuum.ccm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.continuum.ccm.api.CcmExceptionConversionService.AlreadyConvertedException;
import com.continuum.ccm.api.CcmExceptionConversionService.ConversionResult;
import com.continuum.ccm.api.CcmExceptionConversionService.ConvertCommand;
import com.continuum.ccm.api.CcmExceptionConversionService.ConvertNotPermittedException;
import com.continuum.ccm.api.CcmExceptionConversionService.NotRetainedException;
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
 * Vérifie la conversion exception → constat candidat (§5.3, D4) sur une <strong>vraie base Oracle</strong>,
 * à parité avec {@link JdbcCcmExceptionConversionServiceTest} (RAW(16), table de fermeture). Activé
 * seulement si {@code -Dit.oracle.url=jdbc:oracle:…}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleCcmExceptionConversionServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONVERTER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final UUID MISSION = UUID.fromString("f0000000-0000-0000-0000-0000000000a1");
    private static final UUID EXC_RETAINED = UUID.fromString("ce000000-0000-0000-0000-0000000000e1");
    private static final UUID EXC_NEW = UUID.fromString("ce000000-0000-0000-0000-0000000000e2");

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
        db.seed(new ClassPathResource("ccm-convert-oracle-seed.sql"));

        auditTrail = new OracleAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void resetExceptions() {
        // Dénouer le lien avant de supprimer le constat (FK ccm_exception.finding_id → finding).
        jt.update("UPDATE ccm_exception SET finding_id = NULL, version = 0");
        jt.update("DELETE FROM finding WHERE source = 'CCM'");
        jt.update("UPDATE ccm_exception SET status_code='RETAINED', status_label='Retenue' WHERE reference='CCM-OOB-4'");
        jt.update("UPDATE ccm_exception SET status_code='NEW', status_label='À trier' WHERE reference='CCM-DORM-5'");
    }

    private static OracleCcmExceptionConversionService service() {
        return new OracleCcmExceptionConversionService(
                dataSource, new OracleAuthorizationService(dataSource), auditTrail);
    }

    private static ConvertCommand cmd(UUID user, UUID exc) {
        return new ConvertCommand(TENANT, user, exc, MISSION);
    }

    @Test
    void convertsRetainedExceptionIntoMissionFinding() {
        ConversionResult result = service().convert(cmd(CONVERTER, EXC_RETAINED));
        assertThat(result.missionId()).isEqualTo(MISSION);

        assertThat(jt.queryForObject("SELECT source FROM finding WHERE reference='CCM-OOB-4'", String.class))
                .isEqualTo("CCM");
        assertThat(jt.queryForObject("SELECT RAWTOHEX(mission_id) FROM finding WHERE reference='CCM-OOB-4'",
                String.class)).isEqualTo(MISSION.toString().replace("-", "").toUpperCase());
        assertThat(jt.queryForObject("SELECT TO_CHAR(SUBSTR(fact,1,40)) FROM finding WHERE reference='CCM-OOB-4'",
                String.class)).contains("Mouvement n°4");
        assertThat(jt.queryForObject("SELECT finding_id FROM ccm_exception WHERE reference='CCM-OOB-4'", byte[].class))
                .isNotNull();
        assertThat(auditTrail.hasActed(TENANT, CONVERTER, "ccm.exception.convert", "CCM_EXCEPTION",
                EXC_RETAINED.toString())).isTrue();
    }

    @Test
    void refusesToConvertANonRetainedException() {
        assertThatExceptionOfType(NotRetainedException.class).isThrownBy(
                () -> service().convert(cmd(CONVERTER, EXC_NEW)));
        assertThat(jt.queryForObject("SELECT count(*) FROM finding WHERE source='CCM'", Integer.class)).isZero();
    }

    @Test
    void refusesASecondConversion() {
        service().convert(cmd(CONVERTER, EXC_RETAINED));
        assertThatExceptionOfType(AlreadyConvertedException.class).isThrownBy(
                () -> service().convert(cmd(CONVERTER, EXC_RETAINED)));
        assertThat(jt.queryForObject("SELECT count(*) FROM finding WHERE source='CCM'", Integer.class)).isEqualTo(1);
    }

    @Test
    void forbidsConversionWithoutFeature() {
        assertThatExceptionOfType(ConvertNotPermittedException.class).isThrownBy(
                () -> service().convert(cmd(STRANGER, EXC_RETAINED)));
    }
}
