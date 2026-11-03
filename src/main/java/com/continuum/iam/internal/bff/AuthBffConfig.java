package com.continuum.iam.internal.bff;

import com.continuum.PlatformWriteConfig.PlatformWrite;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wiring of the auth BFF (ADR 0014). Active only when {@code continuum.auth.bff.encryption-key} is provided
 * (Vault), so deployments without BFF keep working unchanged. Sessions are written through the platform
 * DataSource (ADR 0012).
 */
@Configuration
@EnableConfigurationProperties(AuthBffProperties.class)
@ConditionalOnExpression("!'${continuum.auth.bff.encryption-key:}'.isEmpty()")
public class AuthBffConfig {

    public static final String ENABLED = "!'${continuum.auth.bff.encryption-key:}'.isEmpty()";

    @Bean
    AuthBffService authBffService(PlatformWrite platform, RestClient.Builder rest, ObjectMapper mapper,
            AuthBffProperties props, @Value("${continuum.db.dialect:postgresql}") String dialect,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        String oidc = issuer.replaceAll("/+$", "") + "/protocol/openid-connect";
        String tokenUri = notBlank(props.tokenUri()) ? props.tokenUri() : oidc + "/token";
        String logoutUri = notBlank(props.logoutUri()) ? props.logoutUri() : oidc + "/logout";
        return new AuthBffService(
                new AuthBffStore(platform.dataSource(), "oracle".equalsIgnoreCase(dialect)),
                new KeycloakTokenClient(rest, mapper, tokenUri, logoutUri),
                new RefreshTokenCipher(props.encryptionKey()), props, Clock.systemUTC());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
