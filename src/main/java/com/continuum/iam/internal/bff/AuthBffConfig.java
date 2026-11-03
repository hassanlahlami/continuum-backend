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

    /** Query parameters carry the wire value ({@code app}/{@code admin}), not the Java constant name. */
    @Bean
    org.springframework.core.convert.converter.Converter<String, gen.auth.model.AuthApp> authAppConverter() {
        return new org.springframework.core.convert.converter.Converter<>() {
            @Override
            public gen.auth.model.AuthApp convert(String source) {
                return gen.auth.model.AuthApp.fromValue(source);
            }
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * Cookie-to-bearer bridge for the resource server: an explicit {@code Authorization: Bearer} header wins
     * (service callers, tests); otherwise the session cookie of the front owning the path ({@code CT_ADMIN_SESSION} on {@code /api/admin/**}, else {@code CT_SESSION}) is exchanged server-side for the session's
     * access token (ADR 0014 addendum b).
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
            // /api/admin/** reads only CT_ADMIN_SESSION, everything else only CT_SESSION (ADR 0014 addendum b)
            String app = request.getRequestURI().startsWith(request.getContextPath() + "/api/admin/") ? "admin" : "app";
            String name = AuthBffProperties.cookieName(app);
            for (jakarta.servlet.http.Cookie c : request.getCookies()) {
                if (name.equals(c.getName())) {
                    return service.accessToken(app, c.getValue()).orElse(null);
                }
            }
            return null;
        };
    }
}
