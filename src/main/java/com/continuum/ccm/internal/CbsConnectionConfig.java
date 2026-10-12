package com.continuum.ccm.internal;

import com.continuum.ccm.api.CcmPopulationGateway;
import com.continuum.ccm.api.PopulationControlMonitoring;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Câblage du connecteur CBS « Amplitude » pour les contrôles continus sur population (§5, D3), actif
 * uniquement quand {@code continuum.cbs.url} est défini (une installation sans CBS démarre inchangée).
 *
 * <p>La source de données CBS est <strong>interne</strong> au connecteur (pas un bean {@code DataSource}) :
 * la source plateforme auto-configurée reste la seule injectée par type, sans ambiguïté. Le connecteur
 * lit le CBS (Oracle) ; {@link PopulationControlMonitoring} écrit les exceptions (D34) dans la base
 * plateforme.
 */
@Configuration
@ConditionalOnProperty(prefix = "continuum.cbs", name = "url")
class CbsConnectionConfig {

    @Bean
    CcmPopulationGateway ccmPopulationGateway(
            @Value("${continuum.cbs.url}") String url,
            @Value("${continuum.cbs.username:}") String username,
            @Value("${continuum.cbs.password:}") String password,
            @Value("${continuum.cbs.driver-class-name:oracle.jdbc.OracleDriver}") String driverClassName) {
        DriverManagerDataSource cbs = new DriverManagerDataSource(url, username, password);
        cbs.setDriverClassName(driverClassName);
        return new OracleCcmPopulationGateway(cbs);
    }

    @Bean
    PopulationControlMonitoring populationControlMonitoring(CcmPopulationGateway gateway,
            DataSource platformDataSource,
            @Value("${continuum.db.dialect:postgresql}") String dialect,
            @Value("${continuum.cbs.data-source-label:CBS (JDBC, vues V_AUDIT_*)}") String dataSourceLabel) {
        // Le CBS est toujours Oracle (D3) ; l'écriture des exceptions vise la base PLATEFORME (PG ou Oracle).
        return "oracle".equalsIgnoreCase(dialect)
                ? new OraclePopulationControlMonitoring(gateway, platformDataSource, dataSourceLabel)
                : new JdbcPopulationControlMonitoring(gateway, platformDataSource, dataSourceLabel);
    }
}
