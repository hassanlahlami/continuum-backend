package com.continuum.ccm.internal;

import com.continuum.ccm.api.SodMonitoring;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sélection de l'implémentation du contrôle CCM de cumul SoD (§5) selon le dialecte de la base
 * plateforme (D4) : {@code continuum.db.dialect} = {@code postgresql} (défaut, {@code ON CONFLICT}) ou
 * {@code oracle} ({@code MERGE}, {@code RAW(16)}).
 */
@Configuration
class SodMonitoringConfig {

    @Bean
    SodMonitoring sodMonitoring(
            DataSource dataSource,
            @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return "oracle".equalsIgnoreCase(dialect)
                ? new OracleSodMonitoring(dataSource)
                : new JdbcSodMonitoring(dataSource);
    }
}
