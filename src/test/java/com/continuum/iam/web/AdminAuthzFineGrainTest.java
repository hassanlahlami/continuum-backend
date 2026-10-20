package com.continuum.iam.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.continuum.ProblemSecurityHandler;
import com.continuum.SecurityConfig;
import com.continuum.iam.api.AssignmentService;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.RoleService;
import com.continuum.iam.api.ScopedFeature;
import com.continuum.iam.api.SegregationOfDutiesService;
import com.continuum.iam.internal.AuthzExpression;
import java.util.List;
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

/**
 * Autorisation <strong>fine</strong> (feature-level, §2.1.7) sur les deux endpoints d'écriture admin,
 * par-dessus la chaîne grossière (audience {@code continuum-admin} + {@code acr}≥2) : sans la
 * fonctionnalité → 403 ; avec → l'appel atteint le service. JWT simulé, {@link AuthorizationService}
 * mocké (le moteur est testé sur base réelle par {@code JdbcAuthorizationServiceTest}).
 */
@WebMvcTest(controllers = {AssignmentsController.class, RolesController.class})
@Import({SecurityConfig.class, ProblemSecurityHandler.class, AuthzExpression.class, AopAutoConfiguration.class})
class AdminAuthzFineGrainTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID POSITION = UUID.fromString("d0000000-0000-0000-0000-000000000003");
    private static final UUID UNIT = UUID.fromString("b0000000-0000-0000-0000-000000000003");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthorizationService authorization;
    @MockitoBean
    private SegregationOfDutiesService sod;
    @MockitoBean
    private AssignmentService assignmentService;
    @MockitoBean
    private RoleService roleService;
    @MockitoBean
    private JwtDecoder jwtDecoder; // requis par le resource server ; non invoqué (JWT simulé)

    private static final String ASSIGNMENT_JSON = """
            {"userId":"%s","positionId":"%s","unitId":"%s","kind":"HOLDER","validFrom":"2026-01-01"}
            """.formatted(TARGET, POSITION, UNIT);
    private static final String ROLE_JSON =
            "{\"code\":\"VALIDATEUR\",\"label\":{\"fr\":\"Valideur\"},\"kind\":\"ORGANISATIONAL\"}";

    @Test
    void assignmentWithoutFeatureIsForbidden() throws Exception {
        given(authorization.can(TENANT, USER, "admin.assignment.manage", UNIT)).willReturn(false);

        mvc.perform(post("/api/admin/assignments").with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON).content(ASSIGNMENT_JSON))
                .andExpect(status().isForbidden());
        verify(assignmentService, never()).assign(any());
    }

    @Test
    void assignmentWithFeatureReachesService() throws Exception {
        given(authorization.can(TENANT, USER, "admin.assignment.manage", UNIT)).willReturn(true);
        given(assignmentService.assign(any())).willReturn(UUID.randomUUID());

        mvc.perform(post("/api/admin/assignments").with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON).content(ASSIGNMENT_JSON))
                .andExpect(status().isCreated());
        verify(assignmentService).assign(any());
    }

    @Test
    void roleWithoutFeatureIsForbidden() throws Exception {
        given(authorization.permissions(TENANT, USER)).willReturn(List.of());

        mvc.perform(post("/api/admin/roles").with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON).content(ROLE_JSON))
                .andExpect(status().isForbidden());
        verify(roleService, never()).create(any());
    }

    @Test
    void roleWithFeatureReachesService() throws Exception {
        given(authorization.permissions(TENANT, USER))
                .willReturn(List.of(new ScopedFeature("admin.role.manage", "TENANT", null)));
        given(roleService.create(any())).willReturn(UUID.randomUUID());

        mvc.perform(post("/api/admin/roles").with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON).content(ROLE_JSON))
                .andExpect(status().isCreated());
        verify(roleService).create(any());
    }

    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(b -> b.audience(List.of("continuum-admin"))
                .claim("acr", "2")
                .claim(AuthzExpression.CLAIM_TENANT, TENANT.toString())
                .claim(AuthzExpression.CLAIM_USER, USER.toString()));
    }
}
