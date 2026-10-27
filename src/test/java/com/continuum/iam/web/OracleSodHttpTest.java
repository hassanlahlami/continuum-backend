package com.continuum.iam.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.continuum.ProblemSecurityHandler;
import com.continuum.SecurityConfig;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.ScopedFeature;
import com.continuum.iam.api.SodConflictDirectory;
import com.continuum.iam.api.SodRuleDirectory;
import com.continuum.iam.internal.AuthzExpression;
import com.continuum.iam.internal.JdbcSodConflictDirectory;
import com.continuum.iam.internal.JdbcSodRuleDirectory;
import com.continuum.testsupport.OracleTestSchema;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP de bout en bout sur <strong>Oracle</strong> pour {@code GET /sod-rules} et {@code GET /sod-conflicts} :
 * chaîne de sécurité admin, contrôleur généré, annuaires JDBC réels (dialecte {@code oracle}, RAW(16)).
 * Seul le moteur {@code can()} est mocké (testé sur Oracle par {@code OracleAuthorizationServiceTest}).
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
@WebMvcTest(controllers = SodRulesController.class)
@Import({SecurityConfig.class, ProblemSecurityHandler.class, AuthzExpression.class, AopAutoConfiguration.class,
        OracleSodHttpTest.RealDirectories.class})
class OracleSodHttpTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID AA = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID RULE = UUID.fromString("90000000-0000-0000-0000-000000000001");

    private static DataSource dataSource;

    @BeforeAll
    static void schema() {
        OracleTestSchema db = OracleTestSchema.connect().reset();
        dataSource = db.dataSource();
        db.platform("V202609300900__platform_feature_catalog.sql", "V202609301200__platform_tenant.sql",
                "V202610271000__platform_tenant_status_version.sql", "R__feature_catalog.sql");
        db.entity("V202609301000__entity_iam_structure.sql");
        db.entity("V202610261000__sod_rule_from_pack.sql");
        db.seed(new org.springframework.core.io.ClassPathResource("iam-oracle-sod-seed.sql"));
    }

    @TestConfiguration
    static class RealDirectories {
        @Bean
        SodRuleDirectory sodRuleDirectory() {
            return new JdbcSodRuleDirectory(dataSource, "oracle");
        }

        @Bean
        SodConflictDirectory sodConflictDirectory(SodRuleDirectory rules) {
            return new JdbcSodConflictDirectory(dataSource, rules, true);
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthorizationService authorization;
    @MockitoBean
    private com.continuum.iam.api.SegregationOfDutiesService sod;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void listsRulesFromOracle() throws Exception {
        given(authorization.permissions(TENANT, AA))
                .willReturn(List.of(new ScopedFeature("admin.sod.read", "TENANT", null)));

        mvc.perform(get("/api/admin/sod-rules").with(adminJwt(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(RULE.toString()))
                .andExpect(jsonPath("$[0].featureA").value("finding.write"))
                .andExpect(jsonPath("$[0].featureB").value("finding.validate"))
                .andExpect(jsonPath("$[0].type").value("STATIC"))
                .andExpect(jsonPath("$[0].severity").value("BLOCK"));
    }

    @Test
    void listsConflictsFromOracle() throws Exception {
        given(authorization.permissions(TENANT, AA))
                .willReturn(List.of(new ScopedFeature("admin.sod.read", "TENANT", null)));

        mvc.perform(get("/api/admin/sod-conflicts").with(adminJwt(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].user.id").value(AA.toString()))
                .andExpect(jsonPath("$[0].rule.id").value(RULE.toString()))
                .andExpect(jsonPath("$[0].viaAssignments.length()").value(2));
    }

    @Test
    void otherTenantSeesNothing() throws Exception {
        given(authorization.permissions(OTHER_TENANT, AA))
                .willReturn(List.of(new ScopedFeature("admin.sod.read", "TENANT", null)));

        mvc.perform(get("/api/admin/sod-rules").with(adminJwt(OTHER_TENANT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/admin/sod-conflicts").with(adminJwt(OTHER_TENANT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void withoutReadFeatureIsForbidden() throws Exception {
        given(authorization.permissions(TENANT, AA)).willReturn(List.of());

        mvc.perform(get("/api/admin/sod-rules").with(adminJwt(TENANT))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/sod-conflicts").with(adminJwt(TENANT))).andExpect(status().isForbidden());
    }

    private static RequestPostProcessor adminJwt(UUID tenant) {
        return jwt().jwt(b -> b.audience(List.of("continuum-admin")).claim("acr", "2")
                .claim(AuthzExpression.CLAIM_TENANT, tenant.toString())
                .claim(AuthzExpression.CLAIM_USER, AA.toString()));
    }
}
