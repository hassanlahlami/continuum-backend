package com.continuum.audit.internal;

import com.continuum.audit.api.FindingActionService;
import com.continuum.audit.api.FindingDirectory;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.SegregationOfDutiesService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sélection de l'implémentation de l'action « valider un constat » (§2.1.4) selon le dialecte de base
 * (D4) : {@code continuum.db.dialect} = {@code postgresql} (défaut) ou {@code oracle} ({@code RAW(16)}).
 * Les dépendances injectées (read model, autorisation, SoD, journal d'audit) sont déjà multi-dialecte.
 */
@Configuration
class FindingActionConfig {

    @Bean
    FindingActionService findingActionService(
            DataSource dataSource,
            FindingDirectory findings,
            AuthorizationService authorization,
            SegregationOfDutiesService segregationOfDuties,
            AuditTrailService auditTrail,
            @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return "oracle".equalsIgnoreCase(dialect)
                ? new OracleFindingActionService(dataSource, findings, authorization, segregationOfDuties, auditTrail)
                : new JdbcFindingActionService(dataSource, findings, authorization, segregationOfDuties, auditTrail);
    }
}
