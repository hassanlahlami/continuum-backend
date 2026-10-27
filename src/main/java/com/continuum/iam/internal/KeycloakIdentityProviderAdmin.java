package com.continuum.iam.internal;

import com.continuum.iam.api.IdentityProviderAdmin;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link IdentityProviderAdmin} via l'API d'administration Keycloak (compte de service, client credentials).
 * {@code platform.user_account.id} est l'identifiant du compte Keycloak.
 */
public class KeycloakIdentityProviderAdmin implements IdentityProviderAdmin {

    private static final Set<String> MFA_CREDENTIAL_TYPES = Set.of("otp", "webauthn", "webauthn-passwordless");
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() { };

    private final RestClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    public KeycloakIdentityProviderAdmin(String baseUrl, String realm, String clientId, String clientSecret) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public void resetMfa(UUID continuumUserId) {
        call(() -> {
            String token = token();
            String kcId = continuumUserId.toString();
            List<Map<String, Object>> credentials = http.get()
                    .uri("/admin/realms/{r}/users/{id}/credentials", realm, kcId)
                    .headers(h -> h.setBearerAuth(token)).retrieve().body(LIST_OF_MAPS);
            for (Map<String, Object> cred : credentials == null ? List.<Map<String, Object>>of() : credentials) {
                if (MFA_CREDENTIAL_TYPES.contains(String.valueOf(cred.get("type")))) {
                    http.delete().uri("/admin/realms/{r}/users/{id}/credentials/{cid}", realm, kcId, cred.get("id"))
                            .headers(h -> h.setBearerAuth(token)).retrieve().toBodilessEntity();
                }
            }
        });
    }

    @Override
    public void closeSessions(UUID continuumUserId) {
        call(() -> {
            String token = token();
            String kcId = continuumUserId.toString();
            http.post().uri("/admin/realms/{r}/users/{id}/logout", realm, kcId)
                    .headers(h -> h.setBearerAuth(token)).retrieve().toBodilessEntity();
        });
    }

    private String token() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        Map<String, Object> body = http.post().uri("/realms/{r}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(MAP);
        return String.valueOf(body.get("access_token"));
    }

    private static void call(Runnable r) {
        try {
            r.run();
        } catch (RestClientException e) {
            throw new IdentityProviderUnavailableException("Fournisseur d'identité injoignable ou en erreur", e);
        }
    }
}
