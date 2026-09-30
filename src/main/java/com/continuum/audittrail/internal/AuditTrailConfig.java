package com.continuum.audittrail.internal;

import com.continuum.audittrail.api.AuditTrailService;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Câblage du journal d'audit (plan §2.3, §4). La clé HMAC vient de l'environnement
 * ({@code continuum.audittrail.hmac-key}, à alimenter depuis Vault en production, §4) — jamais en
 * dur dans le code.
 */
@Configuration
class AuditTrailConfig {

    @Bean
    AuditTrailService auditTrailService(
            DataSource dataSource,
            @Value("${continuum.audittrail.hmac-key:}") String hmacKey) {
        if (hmacKey == null || hmacKey.isBlank()) {
            throw new IllegalStateException(
                    "continuum.audittrail.hmac-key manquante : fournir une clé HMAC (Vault, §4) "
                            + "pour signer le journal d'audit.");
        }
        return new JdbcAuditTrailService(dataSource, hmacKey.getBytes(StandardCharsets.UTF_8));
    }
}
