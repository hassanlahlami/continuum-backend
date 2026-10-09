package com.continuum.iam.internal;

import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.iam.api.RoleService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sélection de l'implémentation d'écriture des rôles selon le dialecte de base (D4) :
 * {@code continuum.db.dialect} = {@code postgresql} (défaut) ou {@code oracle}.
 *
 * <p>Repositories spécifiques par dialecte (§2.1.1, §4, D4) : PostgreSQL stocke le libellé i18n en
 * {@code jsonb} ({@link JdbcRoleService}), Oracle en {@code VARCHAR2 IS JSON} avec UUID en
 * {@code RAW(16)} ({@link OracleRoleService}). Le journal d'audit injecté est déjà multi-dialecte.
 */
@Configuration
class RoleServiceConfig {

    @Bean
    RoleService roleService(
            DataSource dataSource,
            AuditTrailService auditTrail,
            @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return forDialect(dataSource, auditTrail, dialect);
    }

    static RoleService forDialect(DataSource dataSource, AuditTrailService auditTrail, String dialect) {
        return "oracle".equalsIgnoreCase(dialect)
                ? new OracleRoleService(dataSource, auditTrail)
                : new JdbcRoleService(dataSource, auditTrail);
    }
}
