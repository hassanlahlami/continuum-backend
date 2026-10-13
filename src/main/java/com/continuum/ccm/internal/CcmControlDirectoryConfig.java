package com.continuum.ccm.internal;

import com.continuum.ccm.api.CcmControlDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sélection du read model de paramétrage des contrôles CCM (CCM-03) selon le dialecte de la base
 * plateforme (D4) : {@code continuum.db.dialect} = {@code postgresql} (défaut) ou {@code oracle}
 * ({@code RAW(16)}, {@code NUMBER(1)}). Alimente le scheduler (moteur) et la bibliothèque de contrôles.
 */
@Configuration
class CcmControlDirectoryConfig {

    @Bean
    CcmControlDirectory ccmControlDirectory(
            DataSource dataSource, ObjectMapper mapper,
            @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return "oracle".equalsIgnoreCase(dialect)
                ? new OracleCcmControlDirectory(dataSource, mapper)
                : new JdbcCcmControlDirectory(dataSource, mapper);
    }
}
