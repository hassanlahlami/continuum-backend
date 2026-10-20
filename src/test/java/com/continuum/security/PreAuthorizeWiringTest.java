package com.continuum.security;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.continuum.ProblemSecurityHandler;
import com.continuum.SecurityConfig;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.SegregationOfDutiesService;
import com.continuum.iam.api.SodViolation;
import com.continuum.iam.internal.AuthzExpression;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Vérifie le câblage {@code @PreAuthorize("@authz.can(...)")} (plan §2.1.7) : un endpoint annoté
 * renvoie 200 quand {@link AuthorizationService#can} accorde, 403 quand il refuse. Le tenant et
 * l'utilisateur proviennent des claims du JWT ({@code tenant_id}, {@code continuum_user_id}).
 *
 * <p>Pas de Keycloak : le JWT est simulé ({@code spring-security-test}) ; {@link AuthorizationService}
 * est mocké — le moteur lui-même est testé sur base réelle par {@code JdbcAuthorizationServiceTest}.
 */
@WebMvcTest(controllers = {SecuredTestController.class, SecuredObjectController.class})
@Import({SecurityConfig.class, ProblemSecurityHandler.class, AuthzExpression.class})
class PreAuthorizeWiringTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID GRANTED_UNIT = UUID.fromString("b0000000-0000-0000-0000-000000000003");
    private static final UUID DENIED_UNIT = UUID.fromString("b0000000-0000-0000-0000-000000000004");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthorizationService authorization;

    @MockitoBean
    private SegregationOfDutiesService sod;

    @MockitoBean
    private JwtDecoder jwtDecoder; // requis par le resource server ; non invoqué (JWT simulé)

    @Test
    void returns200WhenFeatureGranted() throws Exception {
        given(authorization.can(TENANT, USER, "mission.order.approve", GRANTED_UNIT)).willReturn(true);

        mvc.perform(get("/test/unit/{unitId}/approve", GRANTED_UNIT).with(callerJwt()))
                .andExpect(status().isOk());
    }

    @Test
    void returns403WhenFeatureDenied() throws Exception {
        given(authorization.can(TENANT, USER, "mission.order.approve", DENIED_UNIT)).willReturn(false);

        mvc.perform(get("/test/unit/{unitId}/approve", DENIED_UNIT).with(callerJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void canDoAllowsWhenGrantedAndNoDynamicSodBlock() throws Exception {
        given(authorization.can(TENANT, USER, "finding.validate", GRANTED_UNIT)).willReturn(true);
        given(sod.dynamicConflicts(TENANT, USER, "finding.validate", "FINDING", "F-1"))
                .willReturn(List.of());

        mvc.perform(get("/test/unit/{u}/finding/{o}/validate", GRANTED_UNIT, "F-1").with(callerJwt()))
                .andExpect(status().isOk());
    }

    @Test
    void canDoDeniesWhenDynamicSodBlocks() throws Exception {
        given(authorization.can(TENANT, USER, "finding.validate", GRANTED_UNIT)).willReturn(true);
        given(sod.dynamicConflicts(TENANT, USER, "finding.validate", "FINDING", "F-1"))
                .willReturn(List.of(new SodViolation("finding.write", "finding.validate",
                        SodViolation.Severity.BLOCK)));

        mvc.perform(get("/test/unit/{u}/finding/{o}/validate", GRANTED_UNIT, "F-1").with(callerJwt()))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor callerJwt() {
        return jwt().jwt(j -> j
                .claim(AuthzExpression.CLAIM_TENANT, TENANT.toString())
                .claim(AuthzExpression.CLAIM_USER, USER.toString()));
    }
}
