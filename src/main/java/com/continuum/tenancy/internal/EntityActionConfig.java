package com.continuum.tenancy.internal;

import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.tenancy.api.EntityActionService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sélection de l'implémentation selon {@code continuum.db.dialect} ({@code postgresql} par défaut, ou {@code oracle}). */
@Configuration
class EntityActionConfig {

    @Bean
    EntityActionService entityActionService(
            DataSource dataSource, AuditTrailService auditTrail,
            @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return "oracle".equalsIgnoreCase(dialect)
                ? new OracleEntityActionService(dataSource, auditTrail)
                : new JdbcEntityActionService(dataSource, auditTrail);
    }
}
