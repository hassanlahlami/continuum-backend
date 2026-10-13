package com.continuum.ccm.internal;

import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.ccm.api.CcmExceptionConversionService;
import com.continuum.iam.api.AuthorizationService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sélection de l'implémentation de la conversion exception → constat (§5.3, D34) selon le dialecte de la
 * base plateforme (D4) : {@code continuum.db.dialect} = {@code postgresql} (défaut) ou {@code oracle}
 * ({@code RAW(16)}). Dépendances (autorisation, journal d'audit) déjà multi-dialecte.
 */
@Configuration
class CcmExceptionConversionConfig {

    @Bean
    CcmExceptionConversionService ccmExceptionConversionService(
            DataSource dataSource,
            AuthorizationService authorization,
            AuditTrailService auditTrail,
            @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return "oracle".equalsIgnoreCase(dialect)
                ? new OracleCcmExceptionConversionService(dataSource, authorization, auditTrail)
                : new JdbcCcmExceptionConversionService(dataSource, authorization, auditTrail);
    }
}
