package com.continuum.audit.internal;

import com.continuum.audit.api.FindingDirectory;
import com.continuum.audit.api.MissionDirectory;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sélection des read models du module {@code audit} (missions, constats) selon le dialecte de base
 * (D4) : {@code continuum.db.dialect} = {@code postgresql} (défaut) ou {@code oracle}. PostgreSQL
 * s'appuie sur {@code ltree}, Oracle sur la table de fermeture {@code org_unit_closure}.
 */
@Configuration
class AuditReadModelConfig {

    @Bean
    MissionDirectory missionDirectory(
            DataSource dataSource, @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return "oracle".equalsIgnoreCase(dialect)
                ? new OracleMissionDirectory(dataSource)
                : new JdbcMissionDirectory(dataSource);
    }

    @Bean
    FindingDirectory findingDirectory(
            DataSource dataSource, @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return "oracle".equalsIgnoreCase(dialect)
                ? new OracleFindingDirectory(dataSource)
                : new JdbcFindingDirectory(dataSource);
    }
}
