package com.continuum.iam.internal;

import com.continuum.iam.api.AuthorizationService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sélection de l'implémentation du moteur d'autorisation selon le dialecte de base (D4) :
 * {@code continuum.db.dialect} = {@code postgresql} (défaut) ou {@code oracle}.
 *
 * <p>Repositories spécifiques par dialecte (§4, D4) : PostgreSQL via {@code ltree},
 * Oracle via table de fermeture.
 */
@Configuration
class AuthorizationConfig {

    @Bean
    AuthorizationService authorizationService(
            DataSource dataSource,
            @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return forDialect(dataSource, dialect);
    }

    static AuthorizationService forDialect(DataSource dataSource, String dialect) {
        return "oracle".equalsIgnoreCase(dialect)
                ? new OracleAuthorizationService(dataSource)
                : new JdbcAuthorizationService(dataSource);
    }
}
