package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Vérifie la sélection d'implémentation d'autorisation par dialecte (D4). */
class AuthorizationConfigTest {

    private static final DataSource DS = new DriverManagerDataSource();

    @Test
    void selectsPostgresByDefault() {
        assertThat(AuthorizationConfig.forDialect(DS, "postgresql"))
                .isInstanceOf(JdbcAuthorizationService.class);
    }

    @Test
    void selectsOracleWhenConfigured() {
        assertThat(AuthorizationConfig.forDialect(DS, "oracle"))
                .isInstanceOf(OracleAuthorizationService.class);
    }

    @Test
    void unknownDialectFallsBackToPostgres() {
        assertThat(AuthorizationConfig.forDialect(DS, "mariadb"))
                .isInstanceOf(JdbcAuthorizationService.class);
    }
}
