package com.continuum.iam.internal;

import com.continuum.iam.api.SodConflictDirectory;
import com.continuum.iam.api.SodRuleDirectory;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sélection du dialecte (D4) pour {@link SodConflictDirectory} : {@code continuum.db.dialect}. */
@Configuration
class SodConflictDirectoryConfig {

    @Bean
    SodConflictDirectory sodConflictDirectory(
            DataSource dataSource,
            SodRuleDirectory rules,
            @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return new JdbcSodConflictDirectory(dataSource, rules, "oracle".equalsIgnoreCase(dialect));
    }
}
