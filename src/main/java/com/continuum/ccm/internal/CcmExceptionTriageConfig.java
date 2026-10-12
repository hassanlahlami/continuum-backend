package com.continuum.ccm.internal;

import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.ccm.api.CcmExceptionTriageService;
import com.continuum.iam.api.AuthorizationService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sélection de l'implémentation du triage d'exception CCM (§5.3, D34) selon le dialecte de la base
 * plateforme (D4) : {@code continuum.db.dialect} = {@code postgresql} (défaut) ou {@code oracle}
 * ({@code RAW(16)}, {@code SYSTIMESTAMP}). Les dépendances (autorisation, journal d'audit) sont déjà
 * multi-dialecte.
 */
@Configuration
class CcmExceptionTriageConfig {

    @Bean
    CcmExceptionTriageService ccmExceptionTriageService(
            DataSource dataSource,
            AuthorizationService authorization,
            AuditTrailService auditTrail,
            @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return "oracle".equalsIgnoreCase(dialect)
                ? new OracleCcmExceptionTriageService(dataSource, authorization, auditTrail)
                : new JdbcCcmExceptionTriageService(dataSource, authorization, auditTrail);
    }
}
