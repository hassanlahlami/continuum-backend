package com.continuum.iam.internal.bff;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.iam.internal.bff.AuthBffService.LoginOutcome;
import com.continuum.testsupport.OracleTestSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * Auth BFF store + service on a real Oracle ({@code -Dit.oracle.url=jdbc:oracle:…}), through the PLATFORM account
 * (ADR 0012); Keycloak is stubbed. Same behaviour as the PostgreSQL {@code AuthControllerIT}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleAuthBffServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static AuthBffService service;
    private static JdbcTemplate platform;

    /** Keycloak double: password {@code good} succeeds. */
    static class StubKeycloak extends KeycloakTokenClient {
        boolean loggedOut;

        StubKeycloak(ObjectMapper mapper) {
            super(RestClient.builder(), mapper, "http://unused", "http://unused");
        }

        @Override
        public TokenResult passwordGrant(AuthBffProperties.Client client, String username, String password) {
            if (!"good".equals(password)) {
                return new TokenResult(true, null, null);
            }
            ObjectMapper m = new ObjectMapper();
            return new TokenResult(false, "rt-1", m.createObjectNode().put("sub", USER.toString()).put("acr", "1"));
        }

        @Override
        public String authorizationUri() {
            return "http://kc.test/auth";
        }

        @Override
        public TokenResult exchangeCode(AuthBffProperties.Client client, String code, String verifier, String redirectUri) {
            ObjectMapper m = new ObjectMapper();
            return new TokenResult(false, "rt-2", m.createObjectNode().put("sub", USER.toString()).put("acr", "2"));
        }

        @Override
        public void logout(AuthBffProperties.Client client, String refreshToken) {
            loggedOut = "rt-1".equals(refreshToken);
        }
    }

    private static StubKeycloak keycloak;

    @BeforeAll
    static void setUp() {
        OracleTestSchema db = OracleTestSchema.connect().reset();
        db.platform("V202609300900__platform_feature_catalog.sql", "V202609301200__platform_tenant.sql",
                "V202610271000__platform_tenant_status_version.sql", "V202610271100__platform_user_account.sql",
                "V202611021000__platform_bff_session.sql", "V202611031000__auth_session_app.sql");
        platform = db.platformJdbc();
        platform.update("INSERT INTO platform.tenant (id, code, label, active) VALUES (?, 'DEMO', 'Demo', 1)", raw(TENANT));
        platform.update("INSERT INTO platform.user_account (id, tenant_id, username, source) VALUES (?, ?, 'alice', 'LOCAL')",
                raw(USER), raw(TENANT));
        platform.update("INSERT INTO platform.tenant_param (tenant_id, param_key, param_value)"
                + " VALUES (?, 'auth.ratelimit.identifier.max_attempts', '2')", raw(TENANT));

        keycloak = new StubKeycloak(new ObjectMapper());
        AuthBffProperties props = new AuthBffProperties("", "", true,
                Base64.getEncoder().encodeToString(new byte[32]),
                Map.of("app", new AuthBffProperties.Client("continuum-app-bff", "s"),
                        "admin", new AuthBffProperties.Client("continuum-admin-bff", "s")), "", "");
        service = new AuthBffService(new AuthBffStore(platform.getDataSource(), true), keycloak,
                new RefreshTokenCipher(props.encryptionKey()), props, Clock.systemUTC());
    }

    private static byte[] raw(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    @Test
    void authorizeWithUpdatePasswordAction() {
        var r = service.startAuthorization("app", TENANT, "/", null, null, "http://x/cb", "update-password");
        assertThat(r.location()).contains("kc_action=UPDATE_PASSWORD").contains("code_challenge_method=S256");
        assertThat(service.startAuthorization("app", TENANT, "/", null, null, "http://x/cb", null).location())
                .doesNotContain("kc_action");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.startAuthorization("app", TENANT, "/", null, null, "http://x/cb", "UPDATE_PASSWORD"))
                .isInstanceOf(AuthBffService.BadFlowException.class);
    }

    @Test
    void loginSessionLogoutAndRateLimit() {
        assertThat(service.tenant("DEMO")).contains(TENANT);
        assertThat(service.tenant("NOPE")).isEmpty();
        assertThat(service.loginMode(TENANT, "app")).isEqualTo("redirect");

        LoginOutcome ok = service.login("app", TENANT, "alice", "good", "10.0.0.1");
        assertThat(ok).isInstanceOf(LoginOutcome.Success.class);
        var opened = ((LoginOutcome.Success) ok).opened();
        assertThat(opened.view().displayName()).isEqualTo("alice");
        assertThat(opened.view().acr()).isEqualTo(1);
        assertThat(opened.view().idleTimeoutSeconds()).isEqualTo(1800);

        assertThat(service.current("app", opened.cookieValue())).isPresent();
        assertThat(service.current("app", "unknown")).isEmpty();
        service.logout("app", opened.cookieValue());
        assertThat(keycloak.loggedOut).isTrue();
        assertThat(service.current("app", opened.cookieValue())).isEmpty();

        // tenant override max_attempts = 2: first failure is a plain 401, second trips the limiter
        assertThat(service.login("app", TENANT, "alice", "bad", "10.0.0.2")).isInstanceOf(LoginOutcome.InvalidCredentials.class);
        assertThat(service.login("app", TENANT, "alice", "bad", "10.0.0.2")).isInstanceOf(LoginOutcome.RateLimited.class);
        assertThat(service.login("app", TENANT, "alice", "good", "10.0.0.3")).isInstanceOf(LoginOutcome.RateLimited.class);
    }

    @Test
    void adminSessionRowKeepsItsFrontAndStepUpUpgradesIt() {
        var opened = ((LoginOutcome.Success) service.login("admin", TENANT, "dave", "good", "10.0.1.1")).opened();
        assertThat(platform.queryForObject("SELECT app FROM platform.auth_session WHERE id_hash = ?", String.class,
                AuthBffService.hash(opened.cookieValue()))).isEqualTo("admin");
        assertThat(service.current("app", opened.cookieValue())).isEmpty();
        assertThat(service.current("admin", opened.cookieValue())).isPresent();
        assertThat(service.startStepUp("app", opened.cookieValue(), 2, "/x", "http://h/cb")).isEmpty();

        var r = service.startStepUp("admin", opened.cookieValue(), 2, "/x", "http://h/cb").orElseThrow();
        assertThat(r.location()).startsWith("http://kc.test/auth?").contains("acr_values=2");
        String state = java.net.URLDecoder.decode(org.springframework.web.util.UriComponentsBuilder
                .fromUriString(r.location()).build().getQueryParams().getFirst("state"), java.nio.charset.StandardCharsets.UTF_8);
        var done = service.complete("c", state, r.nonce(), "http://h/cb");
        assertThat(done.cookieValue()).isNull();
        assertThat(done.returnTo()).isEqualTo("/x");
        assertThat(service.current("admin", opened.cookieValue()).orElseThrow().acr()).isEqualTo(2);
        org.junit.jupiter.api.Assertions.assertThrows(AuthBffService.BadFlowException.class,
                () -> service.complete("c", state, "other-browser", "http://h/cb"));
        org.junit.jupiter.api.Assertions.assertThrows(AuthBffService.BadFlowException.class,
                () -> AuthBffService.checkReturnTo("//evil"));
    }
}
