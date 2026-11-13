package com.continuum.audit.internal;

import com.continuum.audit.api.MissionPlanningService;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.iam.api.AuthorizationService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Câblage du service de création de mission / univers ; statut et phase initiaux paramétrables. */
@Configuration
class MissionPlanningConfig {

    @Bean
    MissionPlanningService missionPlanningService(DataSource dataSource, AuthorizationService authorization,
            AuditTrailService auditTrail,
            @Value("${continuum.db.dialect:postgresql}") String dialect,
            @Value("${continuum.mission.initial.status-code:PLANIFIEE}") String statusCode,
            @Value("${continuum.mission.initial.status-label:Planifiée}") String statusLabel,
            @Value("${continuum.mission.initial.phase-code:PREPARATION}") String phaseCode,
            @Value("${continuum.mission.initial.phase-label:Préparation}") String phaseLabel) {
        return new JdbcMissionPlanningService(dataSource, "oracle".equalsIgnoreCase(dialect), authorization,
                auditTrail, new JdbcMissionPlanningService.InitialState(statusCode, statusLabel, phaseCode,
                        phaseLabel));
    }
}
