package com.continuum.admin.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.continuum.ProblemSecurityHandler;
import com.continuum.SecurityConfig;
import com.continuum.admin.AlertRuleStore;
import com.continuum.admin.AlertRuleStore.Rule;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.ScopedFeature;
import com.continuum.iam.api.SegregationOfDutiesService;
import com.continuum.iam.internal.AuthzExpression;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** HTTP-level check of the alert rule endpoints: CRUD, toggle via PUT, validation (422), 404 and 403. */
@WebMvcTest(controllers = ErrorsController.class)
@Import({SecurityConfig.class, ProblemSecurityHandler.class, AuthzExpression.class, AopAutoConfiguration.class})
class AlertRulesControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID RULE = UUID.fromString("3f6c2a1e-8b7d-4c21-9a55-0e2f4b6d8c10");
    private static final String BODY =
            "{\"label\":\"CBS down\",\"eventCode\":\"CTN-INT-0102\",\"threshold\":5,\"window\":\"PT10M\","
                    + "\"channels\":[\"IN_APP\"],\"enabled\":false}";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private AuthorizationService authorization;
    @MockitoBean
    private SegregationOfDutiesService sod;
    @MockitoBean
    private AlertRuleStore store;
    @MockitoBean
    private com.continuum.admin.ErrorLogSource errorLogSource;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static Rule rule(boolean enabled) {
        return new Rule(RULE, "CBS down", "CTN-INT-0102", 5, "PT10M", List.of("IN_APP"), enabled);
    }

    @Test
    void listsRulesWithActions() throws Exception {
        grant();
        given(store.list()).willReturn(List.of(rule(true)));
        mvc.perform(get("/api/admin/alert-rules").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(RULE.toString()))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].availableActions[0].code").value("edit"));
    }

    @Test
    void createsRule() throws Exception {
        grant();
        given(store.create(any(), any(), any())).willReturn(rule(false));
        mvc.perform(post("/api/admin/alert-rules").with(adminJwt()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void toggleIsAPutChangingEnabled() throws Exception {
        grant();
        given(store.update(any(), any(), any(), any())).willReturn(Optional.of(rule(false)));
        mvc.perform(put("/api/admin/alert-rules/" + RULE).with(adminJwt()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void rejectsInvalidWindow() throws Exception {
        grant();
        mvc.perform(post("/api/admin/alert-rules").with(adminJwt()).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("PT10M", "ten minutes")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknownRuleIs404() throws Exception {
        grant();
        given(store.update(any(), any(), any(), any())).willReturn(Optional.empty());
        mvc.perform(put("/api/admin/alert-rules/" + RULE).with(adminJwt()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/alert-rules/" + RULE).with(adminJwt())).andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns204() throws Exception {
        grant();
        given(store.delete(any(), any(), any())).willReturn(true);
        mvc.perform(delete("/api/admin/alert-rules/" + RULE).with(adminJwt())).andExpect(status().isNoContent());
    }

    @Test
    void withoutFeatureIsForbidden() throws Exception {
        given(authorization.permissions(TENANT, USER)).willReturn(List.of());
        mvc.perform(get("/api/admin/alert-rules").with(adminJwt())).andExpect(status().isForbidden());
    }

    private void grant() {
        given(authorization.permissions(TENANT, USER))
                .willReturn(List.of(new ScopedFeature("admin.alerts.manage", "TENANT", null)));
    }

    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(b -> b.audience(List.of("continuum-admin"))
                .claim("acr", "2")
                .claim(AuthzExpression.CLAIM_TENANT, TENANT.toString())
                .claim(AuthzExpression.CLAIM_USER, USER.toString()));
    }
}
