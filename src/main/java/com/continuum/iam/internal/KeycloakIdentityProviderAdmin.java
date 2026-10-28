package com.continuum.iam.internal;

import com.continuum.iam.api.DirectoryFederation;
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
public class KeycloakIdentityProviderAdmin implements IdentityProviderAdmin, DirectoryFederation {

    private static final Set<String> MFA_CREDENTIAL_TYPES = Set.of("otp", "webauthn", "webauthn-passwordless");
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() { };

    private final RestClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final String syncClientId;
    private final String syncClientSecret;

    public KeycloakIdentityProviderAdmin(String baseUrl, String realm, String clientId, String clientSecret) {
        this(baseUrl, realm, clientId, clientSecret, null, null);
    }

    /** {@code syncClientId}/{@code syncClientSecret}: least-privilege client for directory sync (blank = reuse admin). */
    public KeycloakIdentityProviderAdmin(String baseUrl, String realm, String clientId, String clientSecret,
                                         String syncClientId, String syncClientSecret) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        boolean dedicated = syncClientId != null && !syncClientId.isBlank();
        this.syncClientId = dedicated ? syncClientId : clientId;
        this.syncClientSecret = dedicated ? syncClientSecret : clientSecret;
    }

    @Override
    public void resetMfa(UUID continuumUserId) {
        call(() -> {
            String token = token(clientId, clientSecret);
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
            String token = token(clientId, clientSecret);
            String kcId = continuumUserId.toString();
            http.post().uri("/admin/realms/{r}/users/{id}/logout", realm, kcId)
                    .headers(h -> h.setBearerAuth(token)).retrieve().toBodilessEntity();
        });
    }

    @Override
    public int fullSync(String sourceCode) {
        int[] failed = {0};
        call(() -> {
            String token = token(syncClientId, syncClientSecret);
            List<Map<String, Object>> found = http.get()
                    .uri("/admin/realms/{r}/components?type={t}&name={n}", realm,
                            "org.keycloak.storage.UserStorageProvider", sourceCode)
                    .headers(h -> h.setBearerAuth(token)).retrieve().body(LIST_OF_MAPS);
            String componentId = (found == null ? List.<Map<String, Object>>of() : found).stream()
                    .filter(c -> sourceCode.equals(c.get("name"))).map(c -> String.valueOf(c.get("id")))
                    .findFirst().orElseThrow(() -> new IdentityProviderUnavailableException(
                            "Directory source '" + sourceCode + "' not found in the identity provider", null));
            Map<String, Object> result = http.post()
                    .uri("/admin/realms/{r}/user-storage/{id}/sync?action=triggerFullSync", realm, componentId)
                    .headers(h -> h.setBearerAuth(token)).retrieve().body(MAP);
            if (result != null && result.get("failed") instanceof Number n) {
                failed[0] = n.intValue();
            }
        });
        return failed[0];
    }

    private String token(String clientId, String clientSecret) {
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
