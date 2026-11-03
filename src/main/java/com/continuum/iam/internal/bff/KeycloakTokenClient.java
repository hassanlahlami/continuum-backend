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
    public record TokenResult(boolean invalidGrant, String refreshToken, JsonNode accessClaims) {
    }

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String tokenUri;
    private final String logoutUri;

    public KeycloakTokenClient(RestClient.Builder builder, ObjectMapper mapper, String tokenUri, String logoutUri) {
        this.http = builder.build();
        this.mapper = mapper;
        this.tokenUri = tokenUri;
        this.logoutUri = logoutUri;
    }

    public TokenResult passwordGrant(AuthBffProperties.Client client, String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", client.clientId());
        form.add("client_secret", client.clientSecret());
        form.add("username", username);
        form.add("password", password);
        form.add("scope", "openid");
        try {
            JsonNode body = http.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(JsonNode.class);
            return new TokenResult(false, body.path("refresh_token").asText(),
                    claims(body.path("access_token").asText()));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 401) {
                // invalid_grant (wrong credentials, locked, OTP required) — indistinguishable on purpose (no enumeration)
                return new TokenResult(true, null, null);
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
