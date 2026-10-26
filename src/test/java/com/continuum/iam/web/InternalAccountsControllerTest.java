package com.continuum.iam.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.continuum.ProblemSecurityHandler;
import com.continuum.SecurityConfig;
import com.continuum.iam.api.AccountLoginStatusService;
import com.continuum.iam.api.AccountLoginStatusService.Decision;
import com.continuum.iam.api.AccountLoginStatusService.Reason;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Contrat {@code internal/keycloak.yaml} : chaîne {@code /internal/**} (azp) et projection de la décision. */
@WebMvcTest(controllers = InternalAccountsController.class)
@Import({SecurityConfig.class, ProblemSecurityHandler.class})
class InternalAccountsControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String URL = "/internal/accounts/" + USER + "/status?tenantId=" + TENANT;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AccountLoginStatusService status;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void allowsWithServiceAccountToken() throws Exception {
        given(status.loginStatus(TENANT, USER)).willReturn(Decision.allow());
        mvc.perform(get(URL).with(client("continuum-keycloak")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    void deniesWithMachineReason() throws Exception {
        given(status.loginStatus(TENANT, USER)).willReturn(Decision.deny(Reason.TENANT_SUSPENDED));
        mvc.perform(get(URL).with(client("continuum-keycloak")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("TENANT_SUSPENDED"));
    }

    @Test
    void rejectsOtherClient() throws Exception {
        mvc.perform(get(URL).with(client("continuum-app"))).andExpect(status().isForbidden());
    }

    @Test
    void rejectsUserTokenWithoutAzp() throws Exception {
        mvc.perform(get(URL).with(jwt().jwt(b -> b.audience(List.of("continuum-app")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsAnonymous() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor client(String azp) {
        return jwt().jwt(b -> b.claim("azp", azp));
    }
}
