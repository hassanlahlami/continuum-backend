package com.continuum.audit.internal;

import com.continuum.audittrail.internal.OracleAuditTrailService;
import com.continuum.iam.internal.OracleAuthorizationService;
import com.continuum.testsupport.OracleTestSchema;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** MIS-02 sur Oracle réel (parité D4) : mêmes assertions que {@link JdbcMissionPlanningServiceTest}. */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleMissionPlanningServiceTest {

    @Test
    void missionWizardScenario() {
        OracleTestSchema db = OracleTestSchema.connect().reset();
        db.platform("V202609300900__platform_feature_catalog.sql");
        db.platform("R__feature_catalog.sql");
        db.entity("V202609301000__entity_iam_structure.sql");
        db.entity("V202609301100__entity_audit_trail.sql");
        db.entity("V202610091200__entity_mission.sql");
        db.entity("V202611131000__mission_planning_schema.sql");
        db.seed(new ClassPathResource("mission-create-oracle-seed.sql"));

        OracleAuditTrailService trail = new OracleAuditTrailService(db.dataSource(),
                "k".getBytes(StandardCharsets.UTF_8));
        MissionPlanningScenario.run(new JdbcMissionPlanningService(db.dataSource(), true,
                new OracleAuthorizationService(db.dataSource()), trail,
                new JdbcMissionPlanningService.InitialState("PLANIFIEE", "Planifiée", "PREPARATION", "Préparation")),
                new JdbcTemplate(db.dataSource()));
    }
}
