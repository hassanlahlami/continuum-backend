package com.continuum.admin.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.continuum.ProblemSecurityHandler;
import com.continuum.SecurityConfig;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.ScopedFeature;
import com.continuum.iam.api.SegregationOfDutiesService;
import com.continuum.iam.internal.AuthzExpression;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** HTTP-level check of {@code GET /api/admin/health}: UP/DOWN database probe and 403 without {@code ops.health.read}. */
@WebMvcTest(controllers = HealthController.class)
@Import({SecurityConfig.class, ProblemSecurityHandler.class, AuthzExpression.class, AopAutoConfiguration.class})
class HealthControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private AuthorizationService authorization;
    @MockitoBean
    private SegregationOfDutiesService sod;
    @MockitoBean
    private DataSource dataSource;
    @MockitoBean
    private com.continuum.SchemaVersionVerifier schemaVersions;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void reportsDatabaseUpWhenConnectionIsValid() throws Exception {
        grant();
        var conn = org.mockito.Mockito.mock(java.sql.Connection.class);
        given(conn.isValid(2)).willReturn(true);
        given(dataSource.getConnection()).willReturn(conn);
        given(conn.getSchema()).willReturn("public");
        given(schemaVersions.appliedVersionIfReadable()).willReturn(java.util.OptionalLong.of(202611031000L));

        mvc.perform(get("/api/admin/health").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].schemaVersions[0].schemaName").value("public"))
                .andExpect(jsonPath("$[0].schemaVersions[0].appliedVersion").value("202611031000"))
                .andExpect(jsonPath("$[0].schemaVersions[0].expectedVersion")
                        .value(Long.toString(com.continuum.SchemaVersionVerifier.REQUIRED_SCHEMA_VERSION)))
                .andExpect(jsonPath("$[0].component").value("database"))
                .andExpect(jsonPath("$[0].status").value("UP"))
                .andExpect(jsonPath("$[0].checkedAt").exists());
    }

    @Test
    void reportsDatabaseDownWhenUnreachable() throws Exception {
        grant();
        given(dataSource.getConnection()).willThrow(new SQLException("boom"));

        mvc.perform(get("/api/admin/health").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("DOWN"));
    }

    @Test
    void withoutFeatureIsForbidden() throws Exception {
        given(authorization.permissions(TENANT, USER)).willReturn(List.of());
        mvc.perform(get("/api/admin/health").with(adminJwt())).andExpect(status().isForbidden());
    }

    private void grant() {
        given(authorization.permissions(TENANT, USER))
                .willReturn(List.of(new ScopedFeature("ops.health.read", "TENANT", null)));
    }

    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(b -> b.audience(List.of("continuum-admin"))
                .claim("acr", "2")
                .claim(AuthzExpression.CLAIM_TENANT, TENANT.toString())
                .claim(AuthzExpression.CLAIM_USER, USER.toString()));
    }
}
