package com.continuum.iam.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.continuum.SecurityConfig;
import com.continuum.iam.api.AssignmentDirectory;
import com.continuum.iam.api.AssignmentView;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.ScopedFeature;
import com.continuum.tenancy.api.Tenant;
import com.continuum.tenancy.api.TenancyService;
import java.util.List;
import java.util.Optional;
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
 * Vérifie les endpoints contractuels {@code /api/app/me/permissions} et {@code /api/app/me} (§2.1.7,
 * D32) : chaîne de sécurité app, projection des read models, JSON conforme au contrat. JWT simulé.
 */
@WebMvcTest(controllers = MeController.class)
@Import(SecurityConfig.class)
class MeControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SCOPE = UUID.fromString("b0000000-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthorizationService authorization;

    @MockitoBean
    private TenancyService tenancy;

    @MockitoBean
    private AssignmentDirectory assignments;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void returnsPermissions() throws Exception {
        given(authorization.permissions(TENANT, USER))
                .willReturn(List.of(new ScopedFeature("mission.read", "UNIT_TREE", SCOPE)));

        mvc.perform(get("/api/app/me/permissions").with(appJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authzVersion").value(12))
                .andExpect(jsonPath("$.features[0].code").value("mission.read"))
                .andExpect(jsonPath("$.features[0].scopes[0].type").value("UNIT_TREE"));
    }

    @Test
    void returnsCurrentUser() throws Exception {
        given(tenancy.find(TENANT)).willReturn(Optional.of(new Tenant(TENANT, "BANQUE-DEMO", "Banque Démo", true)));
        given(tenancy.listActive()).willReturn(List.of(new Tenant(TENANT, "BANQUE-DEMO", "Banque Démo", true)));
        given(assignments.activeAssignments(TENANT, USER, "fr"))
                .willReturn(List.of(new AssignmentView("CHEF_DIR", "Chef de direction", SCOPE, "DIR_AUDIT", "HOLDER", null)));

        mvc.perform(get("/api/app/me").with(appJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(USER.toString()))
                .andExpect(jsonPath("$.user.displayName").value("Inspecteur Un"))
                .andExpect(jsonPath("$.entity.code").value("BANQUE-DEMO"))
                .andExpect(jsonPath("$.assignments[0].positionLabel").value("Chef de direction"))
                .andExpect(jsonPath("$.assignments[0].unit.code").value("DIR_AUDIT"));
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
                .claim("authz_version", 12)
                .claim("name", "Inspecteur Un")
                .claim("email", "inspecteur.un@banque-demo.test")
                .claim("locale", "fr"));
    }
}
