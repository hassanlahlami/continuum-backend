package com.continuum.ccm.internal;

import com.continuum.ccm.api.CcmExceptionDirectory;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sélection du read model des exceptions CCM (§5.3) selon le dialecte de la base plateforme (D4) :
 * {@code continuum.db.dialect} = {@code postgresql} (défaut, {@code ltree}) ou {@code oracle}
 * ({@code org_unit_closure}, {@code RAW(16)}).
 */
@Configuration
class CcmReadModelConfig {

    @Bean
    CcmExceptionDirectory ccmExceptionDirectory(
            DataSource dataSource, @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return "oracle".equalsIgnoreCase(dialect)
                ? new OracleCcmExceptionDirectory(dataSource)
                : new JdbcCcmExceptionDirectory(dataSource);
    }
}
