package com.continuum.iam.internal.bff;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Technical configuration of the auth BFF (ADR 0014). Business values (timeouts, rate limits, login mode)
 * live in the database ({@code platform.param_default}/{@code tenant_param}), never here.
 *
 * @param tokenUri  Keycloak token endpoint (realm), empty = derived from the OIDC issuer
 * @param logoutUri Keycloak logout endpoint, empty = derived from the issuer
 * @param cookieSecure {@code Secure} flag of the session cookie (false only for http dev)
 * @param encryptionKey base64 AES key (16/24/32 bytes) protecting refresh tokens at rest; supplied by Vault
 * @param apps      confidential client per front ({@code app}, {@code admin})
 * @param authUri   browser-facing Keycloak authorization endpoint, empty = derived from the issuer
 * @param callbackUri absolute URL of {@code /api/auth/callback} as seen by the browser, empty = derived from the
 *                  request (reverse proxy headers)
 */
@ConfigurationProperties("continuum.auth.bff")
public record AuthBffProperties(String tokenUri, String logoutUri, boolean cookieSecure, String encryptionKey,
        Map<String, Client> apps, String authUri, String callbackUri) {

    public record Client(String clientId, String clientSecret) {
    }

    public static final String SESSION_COOKIE = "CT_SESSION";
    public static final String ADMIN_SESSION_COOKIE = "CT_ADMIN_SESSION";

    /** Session cookie of a front: one cookie per front (ADR 0014 addendum b). */
    public static String cookieName(String app) {
        return "admin".equals(app) ? ADMIN_SESSION_COOKIE : SESSION_COOKIE;
    }
}
