package com.continuum.iam.internal;

import com.continuum.iam.api.AccountLoginStatusService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sélection de {@link AccountLoginStatusService} selon le dialecte de base (D4). */
@Configuration
class AccountLoginStatusConfig {

    @Bean
    AccountLoginStatusService accountLoginStatusService(
            DataSource dataSource, @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return "oracle".equalsIgnoreCase(dialect)
                ? new OracleAccountLoginStatusService(dataSource)
                : new JdbcAccountLoginStatusService(dataSource);
    }
}
