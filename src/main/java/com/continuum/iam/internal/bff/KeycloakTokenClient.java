package com.continuum.iam.internal.bff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Server-side calls to Keycloak (ADR 0014): password grant and logout, with the confidential client of the front. */
public class KeycloakTokenClient {

    /** Outcome of a password grant. {@code invalidGrant} covers wrong password, locked account and missing OTP. */
    public record TokenResult(boolean invalidGrant, String refreshToken, JsonNode accessClaims, String errorDescription) {
        public TokenResult(boolean invalidGrant, String refreshToken, JsonNode accessClaims) {
            this(invalidGrant, refreshToken, accessClaims, null);
        }
    }

    /** Outcome of a refresh grant; {@code refreshToken} is the (possibly rotated) token to persist. */
    public record RefreshResult(boolean invalidGrant, String refreshToken, String accessToken, long expiresInSeconds) {
    }

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String tokenUri;
    private final String logoutUri;
    private final String authUri;

    public KeycloakTokenClient(RestClient.Builder builder, ObjectMapper mapper, String tokenUri, String logoutUri) {
        this(builder, mapper, tokenUri, logoutUri, null);
    }

    public KeycloakTokenClient(RestClient.Builder builder, ObjectMapper mapper, String tokenUri, String logoutUri,
            String authUri) {
        this.http = builder.build();
        this.mapper = mapper;
        this.tokenUri = tokenUri;
        this.logoutUri = logoutUri;
        this.authUri = authUri;
    }

    /** Browser-facing authorization endpoint of the realm. */
    public String authorizationUri() {
        return authUri;
    }

    public TokenResult passwordGrant(AuthBffProperties.Client client, String username, String password) {
        return passwordGrant(client, username, password, null);
    }

    /** Password grant, with the one-time code when answering an OTP challenge. */
    public TokenResult passwordGrant(AuthBffProperties.Client client, String username, String password, String totp) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", client.clientId());
        form.add("client_secret", client.clientSecret());
        form.add("username", username);
        form.add("password", password);
        form.add("scope", "openid");
        if (totp != null) {
            form.add("totp", totp);
        }
        return tokenRequest(form);
    }

    /** Authorization-code exchange (PKCE) terminated by the BFF with the confidential client. */
    public TokenResult exchangeCode(AuthBffProperties.Client client, String code, String codeVerifier,
            String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", client.clientId());
        form.add("client_secret", client.clientSecret());
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("redirect_uri", redirectUri);
        return tokenRequest(form);
    }

    private TokenResult tokenRequest(MultiValueMap<String, String> form) {
        try {
            JsonNode body = http.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(JsonNode.class);
            return new TokenResult(false, body.path("refresh_token").asText(),
                    claims(body.path("access_token").asText()));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 401) {
                // invalid_grant (wrong credentials, locked, OTP required): the description only drives the
                // ACTION_REQUIRED / MFA_REQUIRED outcomes, never the client-facing message (no enumeration)
                String description = null;
                try {
                    description = mapper.readTree(e.getResponseBodyAsString()).path("error_description").asText(null);
                } catch (Exception ignored) {
                    // body not JSON
                }
                return new TokenResult(true, null, null, description);
            }
            throw e;
        }
    }

    public RefreshResult refresh(AuthBffProperties.Client client, String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", client.clientId());
        form.add("client_secret", client.clientSecret());
        form.add("refresh_token", refreshToken);
        try {
            JsonNode body = http.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(JsonNode.class);
            return new RefreshResult(false, body.path("refresh_token").asText(refreshToken),
                    body.path("access_token").asText(), body.path("expires_in").asLong(60));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 401) {
                return new RefreshResult(true, null, null, 0);
            }
            throw e;
        }
    }

    /** Ends the Keycloak SSO session associated with the refresh token (best effort). */
    public void logout(AuthBffProperties.Client client, String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", client.clientId());
        form.add("client_secret", client.clientSecret());
        form.add("refresh_token", refreshToken);
        try {
            http.post().uri(logoutUri).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException ignored) {
            // the BFF session is revoked regardless; the SSO session then expires by itself
        }
    }

    /** Claims of a token received directly from Keycloak over the back channel (no browser in between). */
    private JsonNode claims(String jwt) {
        try {
            return mapper.readTree(Base64.getUrlDecoder().decode(jwt.split("[.]")[1]));
        } catch (Exception e) {
            throw new IllegalStateException("unreadable access token", e);
        }
    }
}
