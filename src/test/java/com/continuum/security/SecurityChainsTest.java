package com.continuum.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.continuum.ProblemSecurityHandler;
import com.continuum.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Vérifie la séparation des chaînes {@code /api/admin/**} et {@code /api/app/**} (plan §4.3.3) :
 * contrôle d'audience et de niveau d'authentification ({@code acr}), avec JWT simulé.
 */
@WebMvcTest(controllers = {AdminPingController.class, AppPingController.class})
@Import({SecurityConfig.class, ProblemSecurityHandler.class})
class SecurityChainsTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder jwtDecoder; // requis par le resource server ; non invoqué (JWT simulé)

    @Test
    void corsPreflightAllowedFromLocalhost8080() throws Exception {
        mvc.perform(options("/api/app/ping").header("Origin", "http://localhost:8080")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8080"));
    }

    @Test
    void corsPreflightRejectedFromOtherOrigin() throws Exception {
        mvc.perform(options("/api/app/ping").header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminAllowsAdminAudienceWithStrongAcr() throws Exception {
        mvc.perform(get("/api/admin/ping").with(token(List.of("continuum-admin"), "2")))
                .andExpect(status().isOk());
    }

    @Test
    void adminRejectsWrongAudience() throws Exception {
        mvc.perform(get("/api/admin/ping").with(token(List.of("continuum-app"), "2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRejectsWeakAcr() throws Exception {
        mvc.perform(get("/api/admin/ping").with(token(List.of("continuum-admin"), "1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void appAllowsAppAudience() throws Exception {
        mvc.perform(get("/api/app/ping").with(token(List.of("continuum-app"), null)))
                .andExpect(status().isOk());
    }

    @Test
    void appRejectsAdminAudience() throws Exception {
        mvc.perform(get("/api/app/ping").with(token(List.of("continuum-admin"), null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void accessDeniedIsProblemJsonWithTraceId() throws Exception {
        mvc.perform(get("/api/admin/ping").with(token(List.of("continuum-app"), "2")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Accès refusé"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void unauthenticatedIsProblemJsonWithTraceId() throws Exception {
        mvc.perform(get("/api/app/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Authentification requise"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    private static RequestPostProcessor token(List<String> audience, String acr) {
        return jwt().jwt(builder -> {
            builder.audience(audience);
            if (acr != null) {
                builder.claim("acr", acr);
            }
        });
    }
}
