package com.continuum.ccm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.ccm.api.CcmPopulationGateway.DormantAccountCriteria;
import com.continuum.ccm.api.CcmPopulationGateway.OutOfBoundsCriteria;
import com.continuum.testsupport.OracleTestSchema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Vérifie, sur une vraie base Oracle (D4), la chaîne CCM population <strong>de bout en bout</strong> :
 * lecture des vues CBS {@code V_AUDIT_*} (connecteur) puis écriture idempotente des
 * <strong>exceptions</strong> ({@code ccm_exception}, D34 — jamais de constat) dans la plateforme — à
 * parité avec PostgreSQL. Activé seulement si {@code -Dit.oracle.url=jdbc:oracle:…}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OraclePopulationControlMonitoringTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jt;

    @BeforeAll
    static void applyAndSeed() {
        OracleTestSchema db = OracleTestSchema.connect().reset();
        dataSource = db.dataSource();
        jt = new JdbcTemplate(dataSource);
        db.platform("V202609300900__platform_feature_catalog.sql");
        db.platform("R__feature_catalog.sql");
        db.entity("V202609301000__entity_iam_structure.sql");
        db.entity("V202610091200__entity_mission.sql");
        db.entity("V202610091300__entity_finding.sql");
        db.entity("V202610091500__finding_frap.sql");
        db.entity("V202610121000__ccm_exception.sql");
        db.entity("V202610141000__ccm_run.sql");
        db.entity("V202611011000__ccm_exception_run_mission.sql");
        db.seed(new ClassPathResource("amplitude-cbs-seed.sql"));
    }

    @BeforeEach
    void clearExceptions() {
        jt.update("DELETE FROM ccm_exception");
    }

    private static OraclePopulationControlMonitoring monitoring() {
        return new OraclePopulationControlMonitoring(
                new OracleCcmPopulationGateway(dataSource), dataSource, "amplitude-test (JDBC)");
    }

    @Test
    void writesDormantExceptionFromCbsEndToEnd() {
        int created = monitoring().recordDormantExceptions(TENANT, "COMPTES-DORMANTS",
                new DormantAccountCriteria(12, new BigDecimal("10000"), List.of(), FROM, TO),
                new BigDecimal("100000"));
        assertThat(created).isEqualTo(1);
        // D34 : exception à trier, aucun constat créé automatiquement.
        assertThat(jt.queryForObject("SELECT count(*) FROM finding WHERE reference = 'CCM-DORM-5'", Integer.class))
                .isZero();
        assertThat(jt.queryForObject("SELECT status_code FROM ccm_exception WHERE reference = 'CCM-DORM-5'",
                String.class)).isEqualTo("NEW");
        assertThat(jt.queryForObject("SELECT criticality_code FROM ccm_exception WHERE reference = 'CCM-DORM-5'",
                String.class)).isEqualTo("HIGH");
        // Chaîne de preuve : enregistrement source du CBS porté par l'exception (§5.3).
        assertThat(jt.queryForObject("SELECT JSON_VALUE(source_record, '$.accountId') FROM ccm_exception "
                + "WHERE reference = 'CCM-DORM-5'", String.class)).isEqualTo("A002000000003");
    }

    @Test
    void writesOutOfBoundsExceptionFromCbsEndToEnd() {
        int created = monitoring().recordOutOfBoundsExceptions(TENANT, "SEUIL-HORAIRE",
                new OutOfBoundsCriteria(new BigDecimal("1000000"), List.of("SAT", "SUN"), 2, List.of("BATCH"), FROM, TO));
        assertThat(created).isEqualTo(1);
        assertThat(jt.queryForObject("SELECT criticality_code FROM ccm_exception WHERE reference = 'CCM-OOB-4'",
                String.class)).isEqualTo("HIGH");
    }

    @Test
    void isIdempotent() {
        var crit = new DormantAccountCriteria(12, new BigDecimal("10000"), List.of(), FROM, TO);
        assertThat(monitoring().recordDormantExceptions(TENANT, "COMPTES-DORMANTS", crit, new BigDecimal("100000")))
                .isEqualTo(1);
        assertThat(monitoring().recordDormantExceptions(TENANT, "COMPTES-DORMANTS", crit, new BigDecimal("100000")))
                .isEqualTo(0);
        assertThat(jt.queryForObject("SELECT count(*) FROM ccm_exception WHERE reference = 'CCM-DORM-5'",
                Integer.class)).isEqualTo(1);
    }
}
