package com.continuum.iam.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.continuum.ProblemSecurityHandler;
import com.continuum.SecurityConfig;
import com.continuum.iam.api.AdminDirectory;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.DirectorySyncService;
import com.continuum.iam.api.ScopedFeature;
import com.continuum.iam.api.SegregationOfDutiesService;
import com.continuum.iam.api.SodConflictDirectory;
import com.continuum.iam.api.SodConflictView;
import com.continuum.iam.internal.AuthzExpression;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP-level check of {@code GET /api/admin/overview}: 200 with the wired KPIs when the caller holds
 * {@code admin.overview.read}, 403 without it. Authorization engine and read models are mocked (they are
 * covered on real PostgreSQL/Oracle by their own tests).
 */
@WebMvcTest(controllers = OverviewController.class)
@Import({SecurityConfig.class, ProblemSecurityHandler.class, AuthzExpression.class, AopAutoConfiguration.class})
class OverviewControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthorizationService authorization;
    @MockitoBean
    private SegregationOfDutiesService sod;
    @MockitoBean
    private AdminDirectory directory;
    @MockitoBean
    private SodConflictDirectory sodConflicts;
    @MockitoBean
    private DirectorySyncService directorySync;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void withFeatureReturnsWiredKpisAndOmitsTheRest() throws Exception {
        given(authorization.permissions(TENANT, USER))
                .willReturn(List.of(new ScopedFeature("admin.overview.read", "TENANT", null)));
        given(directory.countUsersWithoutAssignment(TENANT)).willReturn(3L);
        given(sodConflicts.listStaticConflicts(TENANT))
                .willReturn(List.of(Mockito.mock(SodConflictView.class), Mockito.mock(SodConflictView.class)));

        mvc.perform(get("/api/admin/overview").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.health").value("OK"))
                .andExpect(jsonPath("$.usersWithoutAssignment").value(3))
                .andExpect(jsonPath("$.staticSodConflicts").value(2))
                .andExpect(jsonPath("$.lastDirectorySyncAt").doesNotExist())
                .andExpect(jsonPath("$.licenseExpiresOn").doesNotExist());
    }

    @Test
    void exposesLastDirectorySyncAtWhenASyncSucceeded() throws Exception {
        given(authorization.permissions(TENANT, USER))
                .willReturn(List.of(new ScopedFeature("admin.overview.read", "TENANT", null)));
        given(directorySync.status(TENANT)).willReturn(java.util.Optional.of(new DirectorySyncService.SyncState(
                UUID.randomUUID(), "ad-demo", DirectorySyncService.State.SUCCESS,
                java.time.Instant.parse("2026-10-28T10:00:00Z"), null, 2)));

        mvc.perform(get("/api/admin/overview").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastDirectorySyncAt").value("2026-10-28T10:00:00Z"));
    }

    @Test
    void withoutFeatureIsForbidden() throws Exception {
        given(authorization.permissions(TENANT, USER)).willReturn(List.of());

        mvc.perform(get("/api/admin/overview").with(adminJwt()))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(b -> b.audience(List.of("continuum-admin"))
                .claim("acr", "2")
                .claim(AuthzExpression.CLAIM_TENANT, TENANT.toString())
                .claim(AuthzExpression.CLAIM_USER, USER.toString()));
    }
}
