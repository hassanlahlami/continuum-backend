package com.continuum.iam.internal;

import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.iam.api.SegregationOfDutiesService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sélection de l'implémentation de la séparation des tâches (SoD, §2.1.8/§2.2) selon le dialecte
 * de base (D4) : {@code continuum.db.dialect} = {@code postgresql} (défaut, {@code ltree}) ou
 * {@code oracle} (table de fermeture, {@code RAW(16)}).
 */
@Configuration
class SegregationOfDutiesConfig {

    @Bean
    SegregationOfDutiesService segregationOfDutiesService(
            DataSource dataSource,
            AuditTrailService auditTrail,
            @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return "oracle".equalsIgnoreCase(dialect)
                ? new OracleSegregationOfDutiesService(dataSource, auditTrail)
                : new JdbcSegregationOfDutiesService(dataSource, auditTrail);
    }
}
