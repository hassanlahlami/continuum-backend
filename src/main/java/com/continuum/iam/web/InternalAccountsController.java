package com.continuum.iam.web;

import com.continuum.iam.api.AccountLoginStatusService;
import com.continuum.iam.api.AccountLoginStatusService.Decision;
import gen.internal.keycloak.api.AccountsApi;
import gen.internal.keycloak.model.GetAccountLoginStatus200Response;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API interne appelée par le SPI Keycloak (contrat {@code internal/keycloak.yaml}, §4.5.2) : un compte
 * peut-il ouvrir une session ? Protégée par la chaîne {@code /internal/**} (client credentials).
 */
@RestController
@RequestMapping("/internal")
public class InternalAccountsController implements AccountsApi {

    private final AccountLoginStatusService status;

    public InternalAccountsController(AccountLoginStatusService status) {
        this.status = status;
    }

    @Override
    public ResponseEntity<GetAccountLoginStatus200Response> getAccountLoginStatus(UUID continuumUserId, UUID tenantId) {
        Decision d = status.loginStatus(tenantId, continuumUserId);
        GetAccountLoginStatus200Response body = new GetAccountLoginStatus200Response(d.allowed());
        if (!d.allowed()) {
            body.setReason(GetAccountLoginStatus200Response.ReasonEnum.valueOf(d.reason().name()));
        }
        return ResponseEntity.ok(body);
    }
}
