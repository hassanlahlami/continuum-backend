package com.continuum.ccm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.testsupport.OracleTestSchema;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Vérifie le contrôle CCM de cumul SoD (§5) sur une vraie base Oracle (D4) : un cumul {@code STATIC}/
 * {@code BLOCK} donne une <strong>exception</strong> ({@code ccm_exception}, D34 — pas de constat),
 * idempotente via {@code MERGE} — à parité avec PostgreSQL ({@link JdbcSodMonitoringTest}). Activé
 * seulement si {@code -Dit.oracle.url=jdbc:oracle:…}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleSodMonitoringTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jt;
    private static OracleSodMonitoring monitoring;

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
        db.seed(new ClassPathResource("iam-sod-oracle-seed.sql"));

        monitoring = new OracleSodMonitoring(dataSource);
    }

    @Test
    void createsOneCcmExceptionPerCumulThenIsIdempotent() {
        int created = monitoring.detectCumuls(TENANT);
        assertThat(created).isEqualTo(1);

        // D34 : exception à trier, aucun constat créé.
        assertThat(jt.queryForObject("SELECT count(*) FROM finding", Integer.class)).isZero();
        assertThat(jt.queryForObject(
                "SELECT count(*) FROM ccm_exception WHERE control_code = 'SOD-VIREMENTS' AND status_code = 'NEW' "
                + "AND reference LIKE 'CCM-SOD-%'", Integer.class)).isEqualTo(1);
        assertThat(jt.queryForObject("SELECT title FROM ccm_exception", String.class))
                .contains("finding.write").contains("finding.validate");
        // Chaîne de preuve portée par l'exception (§5.3).
        assertThat(jt.queryForObject("SELECT JSON_VALUE(source_record, '$.featureA') FROM ccm_exception",
                String.class)).isEqualTo("finding.write");

        // Idempotent : le MERGE ne recrée pas l'exception.
        assertThat(monitoring.detectCumuls(TENANT)).isZero();
        assertThat(jt.queryForObject("SELECT count(*) FROM ccm_exception", Integer.class)).isEqualTo(1);
    }
}
