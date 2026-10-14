package com.continuum.ccm.internal;

import com.continuum.ccm.api.CcmRunDirectory;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sélection de l'historique des exécutions CCM ({@code ccm_run}) selon le dialecte de la base plateforme
 * (D4) : {@code continuum.db.dialect} = {@code postgresql} (défaut) ou {@code oracle}. Alimente le moteur
 * ({@code CcmScheduler}, écriture) et la bibliothèque/vue d'ensemble (santé dérivée, lecture).
 */
@Configuration
class CcmRunConfig {

    @Bean
    CcmRunDirectory ccmRunDirectory(
            DataSource dataSource,
            @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return "oracle".equalsIgnoreCase(dialect)
                ? new OracleCcmRunDirectory(dataSource)
                : new JdbcCcmRunDirectory(dataSource);
    }
}
