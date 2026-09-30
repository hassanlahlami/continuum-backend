package com.continuum.iam.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.continuum.SecurityConfig;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.ScopedFeature;
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

/**
 * Vérifie l'endpoint contractuel {@code GET /api/app/me/permissions} (§2.1.7, D32) de bout en bout :
 * chaîne de sécurité app, projection du read model, JSON conforme au contrat. JWT simulé.
 */
@WebMvcTest(controllers = MePermissionsController.class)
@Import(SecurityConfig.class)
class MePermissionsControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SCOPE = UUID.fromString("b0000000-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthorizationService authorization;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void returnsPermissionsForCurrentUser() throws Exception {
        given(authorization.permissions(TENANT, USER))
                .willReturn(List.of(new ScopedFeature("mission.read", "UNIT_TREE", SCOPE)));

        mvc.perform(get("/api/app/me/permissions").with(appJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authzVersion").value(12))
                .andExpect(jsonPath("$.features[0].code").value("mission.read"))
                .andExpect(jsonPath("$.features[0].scopes[0].type").value("UNIT_TREE"))
                .andExpect(jsonPath("$.features[0].scopes[0].id").value(SCOPE.toString()));
    }

    @Test
    void rejectsTokenWithoutAppAudience() throws Exception {
        mvc.perform(get("/api/app/me/permissions")
                .with(jwt().jwt(b -> b.audience(List.of("continuum-admin"))
                        .claim("tenant_id", TENANT.toString())
                        .claim("continuum_user_id", USER.toString()))))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor appJwt() {
        return jwt().jwt(b -> b.audience(List.of("continuum-app"))
                .claim("tenant_id", TENANT.toString())
                .claim("continuum_user_id", USER.toString())
                .claim("authz_version", 12));
    }
}
