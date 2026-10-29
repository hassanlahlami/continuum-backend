package com.continuum.admin.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.continuum.ProblemSecurityHandler;
import com.continuum.SecurityConfig;
import com.continuum.admin.TraceSource;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.ScopedFeature;
import com.continuum.iam.api.SegregationOfDutiesService;
import com.continuum.iam.internal.AuthzExpression;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

/** HTTP-level check of {@code GET /api/admin/traces/{traceId}}: mapping, 404, 403 without the feature. */
@WebMvcTest(controllers = TraceController.class)
@Import({SecurityConfig.class, ProblemSecurityHandler.class, AuthzExpression.class, AopAutoConfiguration.class})
class TraceControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private AuthorizationService authorization;
    @MockitoBean
    private SegregationOfDutiesService sod;
    @MockitoBean
    private TraceSource source;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void returnsTrace() throws Exception {
        allow();
        Instant at = Instant.parse("2026-10-28T10:00:00Z");
        given(source.find(TRACE)).willReturn(Optional.of(new TraceSource.Trace(TRACE, at, 120, true,
                List.of(new TraceSource.Span("s1", null, "continuum-backend", "POST /x", 0, 120, true, "CTN-INT-0102")),
                List.of(Map.of("message", "boom")))));

        mvc.perform(get("/api/admin/traces/" + TRACE).with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value(TRACE))
                .andExpect(jsonPath("$.outcome").value("ERROR"))
                .andExpect(jsonPath("$.durationMs").value(120))
                .andExpect(jsonPath("$.spans[0].status").value("ERROR"))
                .andExpect(jsonPath("$.spans[0].eventCode").value("CTN-INT-0102"))
                .andExpect(jsonPath("$.logs[0].message").value("boom"));
    }

    @Test
    void unknownTraceIsNotFound() throws Exception {
        allow();
        given(source.find(TRACE)).willReturn(Optional.empty());
        mvc.perform(get("/api/admin/traces/" + TRACE).with(adminJwt())).andExpect(status().isNotFound());
    }

    @Test
    void withoutFeatureIsForbidden() throws Exception {
        given(authorization.permissions(TENANT, USER)).willReturn(List.of());
        mvc.perform(get("/api/admin/traces/" + TRACE).with(adminJwt())).andExpect(status().isForbidden());
    }

    private void allow() {
        given(authorization.permissions(TENANT, USER))
                .willReturn(List.of(new ScopedFeature("admin.logs.tech.read", "TENANT", null)));
    }

    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(b -> b.audience(List.of("continuum-admin"))
                .claim("acr", "2")
                .claim(AuthzExpression.CLAIM_TENANT, TENANT.toString())
                .claim(AuthzExpression.CLAIM_USER, USER.toString()));
    }
}
