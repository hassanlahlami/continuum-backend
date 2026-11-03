package com.continuum.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.continuum.iam.internal.bff.AuthBffService;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * BFF auth (D41, ADR 0014) on a real PostgreSQL database with a simulated Keycloak (token and logout endpoints):
 * login, sliding session, CSRF double-submit, logout, rate limit driven by database parameters.
 * Database via {@code -Dit.db.url}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "it.db.url", matches = "jdbc:.+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthControllerIT {

    static final String TENANT = "11111111-1111-1111-1111-111111111111";
    static final String USER = "22222222-2222-2222-2222-222222222222";
    static HttpServer kc;
    static volatile boolean logoutCalled;

    @BeforeAll
    static void startKeycloak() throws Exception {
        kc = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        kc.createContext("/realms/r/protocol/openid-connect/token", ex -> {
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean ok = (form.contains("password=good") || form.contains("refresh_token=rt-1"))
                    && form.contains("client_secret=s3cret");
            String body;
            int code;
            if (ok) {
                String payload = "{\"sub\":\"" + USER + "\",\"acr\":\"1\"}";
                String jwt = "e30." + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
                body = "{\"access_token\":\"" + jwt + "\",\"refresh_token\":\"rt-1\",\"expires_in\":300}";
                code = 200;
            } else {
                body = "{\"error\":\"invalid_grant\"}";
                code = 401;
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, body.length());
            ex.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            ex.close();
        });
        kc.createContext("/realms/r/protocol/openid-connect/logout", ex -> {
            logoutCalled = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    .contains("refresh_token=rt-1");
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        kc.start();
    }

    @AfterAll
    static void stop() {
        kc.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        if (kc == null) {
            startKeycloak();
        }
        String url = System.getProperty("it.db.url");
        String user = System.getProperty("it.db.user");
        String password = System.getProperty("it.db.password");
        Path pg = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "postgresql")
                .toAbsolutePath().normalize();
        DataSource ds = new DriverManagerDataSource(url, user, password);
        JdbcTemplate j = new JdbcTemplate(ds);
        j.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        j.execute("DROP SCHEMA public CASCADE");
        j.execute("CREATE SCHEMA public");
        for (String f : new String[] {"V202609300900__platform_feature_catalog.sql", "R__feature_catalog.sql",
                "V202609301000__entity_iam_structure.sql", "V202609301100__entity_audit_trail.sql",
                "V202609301200__platform_tenant.sql", "V202610271100__platform_user_account.sql",
                "V202611021000__platform_bff_session.sql"}) {
            try (Connection c = ds.getConnection()) {
                ScriptUtils.executeSqlScript(c,
                        new EncodedResource(new FileSystemResource(pg.resolve(f)), StandardCharsets.UTF_8));
            } catch (SQLException e) {
                throw new IllegalStateException(f, e);
            }
        }
        j.update("INSERT INTO platform.tenant (id, code, label, active) VALUES (?::uuid, 'DEMO', 'Demo', true)", TENANT);
        j.update("INSERT INTO platform.user_account (id, tenant_id, username, source) VALUES (?::uuid, ?::uuid, 'alice', 'LOCAL')",
                USER, TENANT);
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", () -> user);
        r.add("spring.datasource.password", () -> password);
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://127.0.0.1:" + kc.getAddress().getPort() + "/realms/r");
        r.add("continuum.audittrail.hmac-key", () -> "boot-test-hmac-key");
        r.add("continuum.schema.version-check.enabled", () -> "false");
        r.add("continuum.auth.bff.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("continuum.auth.bff.cookie-secure", () -> "true");
        r.add("continuum.auth.bff.apps.app.client-id", () -> "continuum-app-bff");
        r.add("continuum.auth.bff.apps.app.client-secret", () -> "s3cret");
    }

    @TestConfiguration
    static class Cfg {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none").claim("sub", "t").build();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    private String csrf() throws Exception {
        MvcResult r = mvc.perform(get("/api/auth/session")).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false)).andReturn();
        Cookie c = r.getResponse().getCookie("XSRF-TOKEN");
        assertThat(c).as("XSRF-TOKEN cookie set by GET /session").isNotNull();
        assertThat(c.isHttpOnly()).isFalse();
        return c.getValue();
    }

    private MvcResult login(String token, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType("application/json")
                .cookie(new Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", token)
                .content("{\"entity\":\"DEMO\",\"username\":\"alice\",\"password\":\"" + password + "\"}")).andReturn();
    }

    @Test
    @Order(1)
    void optionsUseDatabaseMode() throws Exception {
        mvc.perform(get("/api/auth/options").param("app", "app").param("entity", "DEMO"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("redirect"));
        mvc.perform(get("/api/auth/options").param("app", "app").param("entity", "NOPE"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(2)
    void loginWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"good\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(3)
    void loginSessionLogoutRoundTrip() throws Exception {
        String t = csrf();
        MvcResult ok = login(t, "good");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("AUTHENTICATED").contains("alice")
                .doesNotContain("rt-1").doesNotContain("access_token");
        String setCookie = ok.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("CT_SESSION=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict");
        Cookie session = ok.getResponse().getCookie("CT_SESSION");

        // only the hash of the cookie is stored
        JdbcTemplate j = new JdbcTemplate(dataSource);
        assertThat(j.queryForObject("SELECT COUNT(*) FROM platform.auth_session WHERE id_hash = ?", Integer.class,
                AuthBffService.hash(session.getValue()))).isEqualTo(1);

        mvc.perform(get("/api/auth/session").cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.idleTimeoutSeconds").value(1800));

        // cookie -> bearer bridge: a resource call carrying only CT_SESSION is authenticated (404 = past security)
        mvc.perform(get("/api/nothing-here")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/nothing-here").cookie(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/nothing-here").cookie(new Cookie("CT_SESSION", "forged"))).andExpect(status().isUnauthorized());

        String t2 = csrf();
        mvc.perform(post("/api/auth/logout").cookie(session, new Cookie("XSRF-TOKEN", t2)).header("X-XSRF-TOKEN", t2))
                .andExpect(status().isNoContent());
        assertThat(logoutCalled).isTrue();
        mvc.perform(get("/api/auth/session").cookie(session)).andExpect(jsonPath("$.authenticated").value(false));
        mvc.perform(get("/api/nothing-here").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    @Order(4)
    void wrongCredentialsThenRateLimitFromDatabaseParams() throws Exception {
        new JdbcTemplate(dataSource).update("INSERT INTO platform.tenant_param (tenant_id, param_key, param_value)"
                + " VALUES (?::uuid, 'auth.ratelimit.identifier.max_attempts', '2')", TENANT);
        String t = csrf();
        assertThat(login(t, "bad").getResponse().getStatus()).isEqualTo(401);
        MvcResult second = login(t, "bad");
        assertThat(second.getResponse().getStatus()).isEqualTo(429);
        assertThat(second.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(second.getResponse().getContentAsString()).contains("rate-limited");
        assertThat(login(t, "good").getResponse().getStatus()).isEqualTo(429);
    }
}
