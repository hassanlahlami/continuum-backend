package com.continuum.iam.internal;

import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.iam.api.AdminDirectory;
import com.continuum.iam.api.DirectoryFederation;
import com.continuum.iam.api.DirectorySyncService;
import com.continuum.iam.api.IdentityProviderAdmin;
import com.continuum.iam.api.UserAccountActionService;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Câblage des actions de compte : dialecte de base (D4) et client d'administration Keycloak (optionnel). */
@Configuration
class UserAccountActionConfig {

    @Bean
    IdentityProviderAdmin identityProviderAdmin(
            @Value("${continuum.keycloak.admin.base-url:}") String baseUrl,
            @Value("${continuum.keycloak.admin.realm:}") String realm,
            @Value("${continuum.keycloak.admin.client-id:}") String clientId,
            @Value("${continuum.keycloak.admin.client-secret:}") String clientSecret,
            @Value("${continuum.directory-sync.client-id:}") String syncClientId,
            @Value("${continuum.directory-sync.client-secret:}") String syncClientSecret) {
        if (baseUrl.isBlank()) {
            // Non configuré : reset-mfa / close-sessions répondent 503 plutôt que d'échouer silencieusement.
            return new IdentityProviderAdmin() {
                @Override
                public void resetMfa(UUID id) {
                    throw unavailable();
                }

                @Override
                public void closeSessions(UUID id) {
                    throw unavailable();
                }

                private IdentityProviderUnavailableException unavailable() {
                    return new IdentityProviderUnavailableException(
                            "Administration Keycloak non configurée (continuum.keycloak.admin.*)", null);
                }
            };
        }
        return new KeycloakIdentityProviderAdmin(baseUrl, realm, clientId, clientSecret, syncClientId, syncClientSecret);
    }

    @Bean
    UserAccountActionService userAccountActionService(
            DataSource dataSource, AuditTrailService auditTrail, IdentityProviderAdmin identity,
            @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return new JdbcUserAccountActionService(dataSource, auditTrail, identity, "oracle".equalsIgnoreCase(dialect));
    }

    @Bean
    AdminDirectory adminDirectory(DataSource dataSource,
                                  @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return new JdbcAdminDirectory(dataSource, "oracle".equalsIgnoreCase(dialect));
    }

    @Bean
    DirectoryFederation directoryFederation(IdentityProviderAdmin identity) {
        if (identity instanceof DirectoryFederation federation) {
            return federation;
        }
        return source -> {
            throw new IdentityProviderAdmin.IdentityProviderUnavailableException(
                    "Administration Keycloak non configurée (continuum.keycloak.admin.*)", null);
        };
    }

    @Bean(destroyMethod = "shutdown")
    java.util.concurrent.ExecutorService directorySyncExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "directory-sync");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    DirectorySyncService directorySyncService(DataSource dataSource, DirectoryFederation federation,
            @org.springframework.beans.factory.annotation.Qualifier("directorySyncExecutor") Executor executor,
            @Value("${continuum.directory-sync.source-code:}") String sourceCode,
            @Value("${continuum.db.dialect:postgresql}") String dialect) {
        return new JdbcDirectorySyncService(dataSource, "oracle".equalsIgnoreCase(dialect), federation, executor,
                sourceCode);
    }
}
