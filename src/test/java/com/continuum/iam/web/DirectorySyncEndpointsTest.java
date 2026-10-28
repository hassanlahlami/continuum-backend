package com.continuum.iam.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.continuum.ProblemSecurityHandler;
import com.continuum.SecurityConfig;
import com.continuum.iam.api.AdminDirectory;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.DirectorySyncService;
import com.continuum.iam.api.DirectorySyncService.State;
import com.continuum.iam.api.ScopedFeature;
import com.continuum.iam.api.SegregationOfDutiesService;
import com.continuum.iam.api.UserAccountActionService;
import com.continuum.iam.internal.AuthzExpression;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** HTTP-level check of {@code GET|POST /api/admin/directory/sync}; the sync service is mocked (own contract test). */
@WebMvcTest(controllers = UsersController.class)
@Import({SecurityConfig.class, ProblemSecurityHandler.class, AuthzExpression.class, AopAutoConfiguration.class})
class DirectorySyncEndpointsTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthorizationService authorization;
    @MockitoBean
    private SegregationOfDutiesService sod;
    @MockitoBean
    private UserAccountActionService actions;
    @MockitoBean
    private AdminDirectory directory;
    @MockitoBean
    private DirectorySyncService sync;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private void grant(String feature) {
        given(authorization.permissions(TENANT, USER)).willReturn(List.of(new ScopedFeature(feature, "TENANT", null)));
    }

    @Test
    void statusIsEmptyBeforeAnySync() throws Exception {
        grant("admin.user.read");
        given(sync.status(TENANT)).willReturn(Optional.empty());
        mvc.perform(get("/api/admin/directory/sync").with(adminJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lastFullSyncAt").doesNotExist());
    }

    @Test
    void statusExposesProviderLastSyncAndError() throws Exception {
        grant("admin.user.read");
        given(sync.status(TENANT)).willReturn(Optional.of(new DirectorySyncService.SyncState(
                UUID.randomUUID(), "ad-demo", State.FAILED, Instant.parse("2026-10-28T10:00:00Z"), "boom", 4)));
        mvc.perform(get("/api/admin/directory/sync").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("ad-demo"))
                .andExpect(jsonPath("$.lastFullSyncAt").value("2026-10-28T10:00:00Z"))
                .andExpect(jsonPath("$.lastError").value("boom"));
    }

    @Test
    void triggerReturns202WithRunningJob() throws Exception {
        grant("admin.user.manage");
        UUID id = UUID.randomUUID();
        given(sync.configured()).willReturn(true);
        given(sync.trigger(TENANT)).willReturn(new DirectorySyncService.SyncState(
                id, "ad-demo", State.RUNNING, null, null, 1));
        mvc.perform(post("/api/admin/directory/sync").with(adminJwt()).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.type").value("DIRECTORY_SYNC"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void triggerIs503WhenNoSourceConfigured() throws Exception {
        grant("admin.user.manage");
        given(sync.configured()).willReturn(false);
        mvc.perform(post("/api/admin/directory/sync").with(adminJwt()).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void triggerWithoutManageFeatureIsForbidden() throws Exception {
        grant("admin.user.read");
        mvc.perform(post("/api/admin/directory/sync").with(adminJwt()).with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(b -> b.audience(List.of("continuum-admin"))
                .claim("acr", "2")
                .claim(AuthzExpression.CLAIM_TENANT, TENANT.toString())
                .claim(AuthzExpression.CLAIM_USER, USER.toString()));
    }
}
