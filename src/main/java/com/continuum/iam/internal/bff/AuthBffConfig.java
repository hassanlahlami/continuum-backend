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

    /**
     * Cookie-to-bearer bridge for the resource server: an explicit {@code Authorization: Bearer} header wins
     * (service callers, tests); otherwise the {@code CT_SESSION} cookie is exchanged server-side for the session's
     * access token (sessions are opened with the {@code app} client; admin-grade {@code acr}/audience still need step-up, ADR 0014).
     */
    @Bean
    org.springframework.security.oauth2.server.resource.web.BearerTokenResolver sessionCookieBearerTokenResolver(
            AuthBffService service) {
        var header = new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver();
        return request -> {
            String bearer = header.resolve(request);
            if (bearer != null || request.getCookies() == null) {
                return bearer;
            }
            for (jakarta.servlet.http.Cookie c : request.getCookies()) {
                if (AuthBffProperties.SESSION_COOKIE.equals(c.getName())) {
                    // POST /api/auth/login always opens the session with the "app" client (AuthController.APP)
                    return service.accessToken("app", c.getValue()).orElse(null);
                }
            }
            return null;
        };
    }
}
