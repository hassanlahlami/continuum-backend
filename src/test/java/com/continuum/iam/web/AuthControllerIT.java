package com.continuum.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@EnabledIfSystemProperty(named = "it.db.url", matches = "jdbc:.+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthControllerIT {

    static final String TENANT = "11111111-1111-1111-1111-111111111111";
    static final String USER = "22222222-2222-2222-2222-222222222222";
    static HttpServer kc;
    static volatile boolean logoutCalled;
    static volatile String lastTokenForm;
    static volatile String stepUpAcr;

    @BeforeAll
    static void startKeycloak() throws Exception {
        kc = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        kc.createContext("/realms/r/protocol/openid-connect/token", ex -> {
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean needsOtp = form.contains("password=needotp");
            boolean otpOk = form.contains("totp=123456");
            boolean codeGrant = form.contains("grant_type=authorization_code");
            lastTokenForm = form;
            boolean ok = (form.contains("password=good") || form.contains("refresh_token=rt-1") || (needsOtp && otpOk)
                    || (codeGrant && form.contains("code=good-code") && form.contains("code_verifier=")))
                    && form.contains("client_secret=s3cret");
            String body;
            int code;
            if (needsOtp && !otpOk && !form.contains("totp=")) {
                body = "{\"error\":\"invalid_grant\",\"error_description\":\"continuum:otp_required\"}";
                code = 401;
            } else if (form.contains("password=otpish")) {
                body = "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid otp code\"}";
                code = 401;
            } else if (form.contains("password=wrongpwd")) {
                body = "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}";
                code = 401;
            } else if (form.contains("password=lockedacct")) {
                body = "{\"error\":\"invalid_grant\",\"error_description\":\"Account temporarily disabled\"}";
                code = 400;
            } else if (form.contains("password=disabledacct")) {
                body = "{\"error\":\"invalid_grant\",\"error_description\":\"Account disabled\"}";
                code = 400;
            } else if (form.contains("password=disabledwrongpwd")) {
                body = "{\"error\":\"invalid_grant\",\"error_description\":\"Account disabled\"}";
                code = 400;
            } else if (form.contains("password=setup")) {
                body = "{\"error\":\"invalid_grant\",\"error_description\":\"Account is not fully set up\"}";
                code = 400;
            } else if (ok) {
                String acr = form.contains("code=good-code") && stepUpAcr != null ? stepUpAcr : "1";
                String payload = "{\"sub\":\"" + USER + "\",\"acr\":\"" + acr + "\"}";
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
                "V202611021000__platform_bff_session.sql",
                "V202611031000__auth_session_app.sql"}) {
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
        r.add("continuum.auth.bff.apps.admin.client-id", () -> "continuum-admin-bff");
        r.add("continuum.auth.bff.apps.admin.client-secret", () -> "s3cret");
    }

    @TestConfiguration
    static class Cfg {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none").claim("sub", "t").claim("acr", "1")
                    .claim("aud", java.util.List.of("continuum-app", "continuum-admin")).build();
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
    void adminFrontUsesItsOwnCookieAndStepUpIsRequired() throws Exception {
        String t = csrf();
        MvcResult ok = mvc.perform(post("/api/auth/login").contentType("application/json")
                .cookie(new Cookie("XSRF-TOKEN", t)).header("X-XSRF-TOKEN", t)
                .content("{\"app\":\"admin\",\"entity\":\"DEMO\",\"username\":\"alice\",\"password\":\"good\"}"))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getCookie("CT_SESSION")).isNull();
        String setCookie = ok.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("CT_ADMIN_SESSION=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("Path=/api").contains("HttpOnly").contains("SameSite=Strict");
        Cookie admin = ok.getResponse().getCookie("CT_ADMIN_SESSION");

        mvc.perform(get("/api/auth/session").param("app", "admin").cookie(admin))
                .andExpect(jsonPath("$.authenticated").value(true));
        // the app front does not read the admin cookie
        mvc.perform(get("/api/auth/session").cookie(admin)).andExpect(jsonPath("$.authenticated").value(false));

        // admin session at acr 1 on /api/admin/** -> 401 step-up-required, not 403
        mvc.perform(get("/api/admin/nothing-here").cookie(admin)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("step-up-required"))
                .andExpect(jsonPath("$.requiredAcr").value(2))
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("insufficient_user_authentication")));
        // /api/admin/** ignores CT_SESSION
        mvc.perform(get("/api/admin/nothing-here").cookie(new Cookie("CT_SESSION", admin.getValue())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.reason").doesNotExist());
    }
    private MvcResult loginAs(String app, String user, String password) throws Exception {
        String t = csrf();
        return mvc.perform(post("/api/auth/login").contentType("application/json")
                .cookie(new Cookie("XSRF-TOKEN", t)).header("X-XSRF-TOKEN", t)
                .content("{\"app\":\"" + app + "\",\"entity\":\"DEMO\",\"username\":\"" + user
                        + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    @Test
    @Order(5)
    void sessionRowRemembersItsFront() throws Exception {
        MvcResult ok = loginAs("admin", "dave", "good");
        Cookie admin = ok.getResponse().getCookie("CT_ADMIN_SESSION");
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT app FROM platform.auth_session WHERE id_hash = ?", String.class,
                AuthBffService.hash(admin.getValue()))).isEqualTo("admin");
        // an admin session cookie replayed as the app cookie opens nothing, even if the name is forged
        mvc.perform(get("/api/auth/session").cookie(new Cookie("CT_SESSION", admin.getValue())))
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    @Order(6)
    void mfaChallengeThenOtp() throws Exception {
        MvcResult first = loginAs("app", "carol", "needotp");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        String body = first.getResponse().getContentAsString();
        assertThat(body).contains("MFA_REQUIRED").contains("otp").doesNotContain("needotp");
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.challengeId");
        String t = csrf();
        // unknown challenge
        mvc.perform(post("/api/auth/mfa").contentType("application/json").cookie(new Cookie("XSRF-TOKEN", t))
                .header("X-XSRF-TOKEN", t).content("{\"challengeId\":\"nope\",\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.reason").value("session-expired"));
        // wrong code: same answer as wrong credentials, challenge stays usable
        mvc.perform(post("/api/auth/mfa").contentType("application/json").cookie(new Cookie("XSRF-TOKEN", t))
                .header("X-XSRF-TOKEN", t).content("{\"challengeId\":\"" + id + "\",\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.reason").value("invalid-credentials"));
        MvcResult ok = mvc.perform(post("/api/auth/mfa").contentType("application/json")
                .cookie(new Cookie("XSRF-TOKEN", t)).header("X-XSRF-TOKEN", t)
                .content("{\"challengeId\":\"" + id + "\",\"code\":\"123456\"}")).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("AUTHENTICATED");
        assertThat(ok.getResponse().getCookie("CT_SESSION")).isNotNull();
        // single use
        mvc.perform(post("/api/auth/mfa").contentType("application/json").cookie(new Cookie("XSRF-TOKEN", t))
                .header("X-XSRF-TOKEN", t).content("{\"challengeId\":\"" + id + "\",\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(8)
    void everyInvalidGrantIsTheSameInvalidCredentialsAndNeverLeaksTheDescription(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String[] passwords = {"wrongpwd", "lockedacct", "disabledacct", "disabledwrongpwd", "otpish", "plainbad"};
        String[] leaks = {"Invalid user credentials", "temporarily disabled", "Account disabled", "Invalid otp", "continuum:otp", "error_description"};
        String first = null;
        for (String pwd : passwords) {
            MvcResult r = loginAs("app", "user-" + pwd, pwd);
            assertThat(r.getResponse().getStatus()).as(pwd).isEqualTo(401);
            String body = r.getResponse().getContentAsString().replaceAll("\"traceId\":\"[^\"]*\"", "");
            assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.reason")).isEqualTo("invalid-credentials");
            for (String leak : leaks) {
                assertThat(body).as(pwd + " body").doesNotContainIgnoringCase(leak);
            }
            if (first == null) {
                first = body;
            }
            assertThat(body).as("identical body for " + pwd).isEqualTo(first);
        }
        for (String leak : leaks) {
            assertThat(output.getAll()).as("logs").doesNotContainIgnoringCase(leak);
        }
    }

    @Test
    @Order(7)
    void pendingRequiredActionsPointToTheBrowserFlow() throws Exception {
        MvcResult r = loginAs("app", "erin", "setup");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentAsString()).contains("ACTION_REQUIRED")
                .contains("/api/auth/authorize");
    }

    @Test
    @Order(8)
    void authorizeCallbackAndStepUp() throws Exception {
        // returnTo must be an internal route
        for (String bad : new String[] {"https://evil.example/x", "//evil.example", "/a\\b"}) {
            mvc.perform(get("/api/auth/authorize").param("entity", "DEMO").param("returnTo", bad))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/auth/authorize").param("entity", "NOPE")).andExpect(status().isBadRequest());

        MvcResult start = mvc.perform(get("/api/auth/authorize").param("entity", "DEMO")
                .param("app", "admin").param("returnTo", "/missions").param("idp", "corp"))
                .andExpect(status().isFound()).andReturn();
        String loc = start.getResponse().getHeader("Location");
        assertThat(loc).contains("/protocol/openid-connect/auth?").contains("client_id=continuum-admin-bff")
                .contains("response_type=code").contains("code_challenge_method=S256").contains("code_challenge=")
                .contains("kc_idp_hint=corp").contains("acr_values=2").contains("redirect_uri=http");
        Cookie flow = start.getResponse().getCookie("CT_AUTH_STATE");
        assertThat(flow).isNotNull();
        assertThat(flow.isHttpOnly()).isTrue();
        String state = org.springframework.web.util.UriComponentsBuilder.fromUriString(loc).build()
                .getQueryParams().getFirst("state");
        state = java.net.URLDecoder.decode(state, StandardCharsets.UTF_8);

        // forged / unbound state
        mvc.perform(get("/api/auth/callback").param("code", "good-code").param("state", "garbage").cookie(flow))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/callback").param("code", "good-code").param("state", state))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/callback").param("code", "bad-code").param("state", state).cookie(flow))
                .andExpect(status().isBadRequest());

        stepUpAcr = "2";
        MvcResult done = mvc.perform(get("/api/auth/callback").param("code", "good-code").param("state", state)
                .cookie(flow)).andExpect(status().isFound()).andExpect(header().string("Location", "/missions"))
                .andReturn();
        assertThat(lastTokenForm).contains("code_verifier=").contains("client_id=continuum-admin-bff");
        Cookie admin = done.getResponse().getCookie("CT_ADMIN_SESSION");
        assertThat(admin).isNotNull();
        mvc.perform(get("/api/auth/session").param("app", "admin").cookie(admin))
                .andExpect(jsonPath("$.authenticated").value(true)).andExpect(jsonPath("$.acr").value(2));

        // step-up of a session opened by /login at acr 1
        stepUpAcr = null;
        Cookie low = loginAs("admin", "frank", "good").getResponse().getCookie("CT_ADMIN_SESSION");
        mvc.perform(get("/api/auth/session").param("app", "admin").cookie(low)).andExpect(jsonPath("$.acr").value(1));
        mvc.perform(get("/api/auth/step-up").param("app", "admin").param("acr", "2")).andExpect(status().isUnauthorized());
        MvcResult su = mvc.perform(get("/api/auth/step-up").param("app", "admin").param("acr", "2")
                .param("returnTo", "/admin/home").cookie(low)).andExpect(status().isFound()).andReturn();
        String suLoc = su.getResponse().getHeader("Location");
        assertThat(suLoc).contains("acr_values=2").contains("client_id=continuum-admin-bff");
        String suState = java.net.URLDecoder.decode(org.springframework.web.util.UriComponentsBuilder
                .fromUriString(suLoc).build().getQueryParams().getFirst("state"), StandardCharsets.UTF_8);
        stepUpAcr = "2";
        MvcResult suDone = mvc.perform(get("/api/auth/callback").param("code", "good-code").param("state", suState)
                .cookie(su.getResponse().getCookie("CT_AUTH_STATE"))).andExpect(status().isFound())
                .andExpect(header().string("Location", "/admin/home")).andReturn();
        // same session cookie, upgraded level (no new cookie)
        assertThat(suDone.getResponse().getCookie("CT_ADMIN_SESSION")).isNull();
        mvc.perform(get("/api/auth/session").param("app", "admin").cookie(low)).andExpect(jsonPath("$.acr").value(2));
        // a step-up for the app front needs the app session
        mvc.perform(get("/api/auth/step-up").param("acr", "2").cookie(new Cookie("CT_SESSION", low.getValue())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(9)
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

    /** ADR 0014 (d): Keycloak already counts the missing-OTP refusal; the BFF must not feed its own counter with it. */
    @Test
    @Order(10)
    void mfaRequiredIsNeverCountedAsAnIdentifierOrIpFailure() throws Exception {
        // identifier max_attempts is 2 here (test above): counting MFA_REQUIRED would block from the 2nd attempt
        for (int i = 0; i < 5; i++) {
            MvcResult r = loginAs("app", "frank", "needotp");
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getContentAsString()).contains("MFA_REQUIRED");
        }
    }
}
