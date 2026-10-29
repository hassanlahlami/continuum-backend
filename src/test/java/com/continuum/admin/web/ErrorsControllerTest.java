package com.continuum.admin.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.continuum.ProblemSecurityHandler;
import com.continuum.SecurityConfig;
import com.continuum.admin.ErrorLogSource;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.ScopedFeature;
import com.continuum.iam.api.SegregationOfDutiesService;
import com.continuum.iam.internal.AuthzExpression;
import java.time.Instant;
import java.util.List;
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

/** HTTP-level check of {@code GET /api/admin/errors}: mapping, inclusive date bounds, 403 without the feature. */
@WebMvcTest(controllers = ErrorsController.class)
@Import({SecurityConfig.class, ProblemSecurityHandler.class, AuthzExpression.class, AopAutoConfiguration.class})
class ErrorsControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private AuthorizationService authorization;
    @MockitoBean
    private SegregationOfDutiesService sod;
    @MockitoBean
    private ErrorLogSource source;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void returnsGroupsWithInclusiveDateRange() throws Exception {
        given(authorization.permissions(TENANT, USER))
                .willReturn(List.of(new ScopedFeature("admin.logs.tech.read", "TENANT", null)));
        Instant at = Instant.parse("2026-10-28T10:00:00Z");
        given(source.groupByEventCode(any(), any())).willReturn(List.of(new ErrorLogSource.Group(
                "CTN-INT-0102", "ERP call failed", "ERROR", 3, at, at, List.of("node-1"),
                List.of(new ErrorLogSource.Trend(at, 3)), null)));

        mvc.perform(get("/api/admin/errors").param("from", "2026-10-28").param("to", "2026-10-29").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventCode").value("CTN-INT-0102"))
                .andExpect(jsonPath("$[0].occurrences").value(3))
                .andExpect(jsonPath("$[0].level").value("ERROR"))
                .andExpect(jsonPath("$[0].nodes[0]").value("node-1"))
                .andExpect(jsonPath("$[0].trend[0].count").value(3));
        verify(source).groupByEventCode(eq(Instant.parse("2026-10-28T00:00:00Z")),
                eq(Instant.parse("2026-10-30T00:00:00Z")));
    }

    @Test
    void withoutFeatureIsForbidden() throws Exception {
        given(authorization.permissions(TENANT, USER)).willReturn(List.of());
        mvc.perform(get("/api/admin/errors").with(adminJwt())).andExpect(status().isForbidden());
    }

    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(b -> b.audience(List.of("continuum-admin"))
                .claim("acr", "2")
                .claim(AuthzExpression.CLAIM_TENANT, TENANT.toString())
                .claim(AuthzExpression.CLAIM_USER, USER.toString()));
    }
}
