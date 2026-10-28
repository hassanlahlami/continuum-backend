package com.continuum.iam.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.audittrail.internal.JdbcAuditTrailService;
import com.continuum.iam.api.IdentityProviderAdmin;
import com.continuum.iam.internal.JdbcUserAccountActionService;
import gen.admin.iam.model.ActionRequest;
import gen.admin.iam.model.UserAccount;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/**
 * Frontière HTTP de {@link UsersController#performUserAction} sur base PostgreSQL réelle ({@code -Dit.db.url}) :
 * lock / unlock / disable (verrou optimiste, commentaire obligatoire), reset-mfa / close-sessions (fournisseur
 * d'identité simulé), {@code availableActions}, 404 / 409 / 422 / 503, journalisation.
 */
class UsersControllerTest {

    private static final UUID ACTOR_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static JdbcTemplate jt;
    private static UsersController controller;
    private static final List<String> idpCalls = new ArrayList<>();
    private static boolean idpDown;

    @BeforeAll
    static void applyAndSeed() {
        String url = System.getProperty("it.db.url");
        Assumptions.assumeTrue(url != null && !url.isBlank() && !url.startsWith("${"),
                "Base de test absente : lancer avec -Dit.db.url=… -Dit.db.user=… -Dit.db.password=…");
        Path pg = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "postgresql")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(pg), "Scripts du continuum-migrator introuvables : " + pg);
        DataSource ds = new DriverManagerDataSource(
                url, System.getProperty("it.db.user"), System.getProperty("it.db.password"));
        jt = new JdbcTemplate(ds);
        jt.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jt.execute("DROP SCHEMA public CASCADE");
        jt.execute("CREATE SCHEMA public");
        for (String s : List.of("V202609300900__platform_feature_catalog.sql",
                "V202609301000__entity_iam_structure.sql", "V202609301100__entity_audit_trail.sql",
                "V202609301200__platform_tenant.sql", "V202610271000__platform_tenant_status_version.sql",
                "V202610271100__platform_user_account.sql")) {
            try (Connection c = ds.getConnection()) {
                ScriptUtils.executeSqlScript(c,
                        new EncodedResource(new FileSystemResource(pg.resolve(s)), StandardCharsets.UTF_8));
            } catch (SQLException e) {
                throw new IllegalStateException(s, e);
            }
        }
        jt.update("INSERT INTO platform.tenant(id, code, label) VALUES (?, 'ADMIN', 'Admin')", ACTOR_TENANT);
        IdentityProviderAdmin idp = new IdentityProviderAdmin() {
            @Override
            public void resetMfa(UUID id) {
                call("reset-mfa");
            }

            @Override
            public void closeSessions(UUID id) {
                call("close-sessions");
            }

            private void call(String what) {
                if (idpDown) {
                    throw new IdentityProviderUnavailableException("down", null);
                }
                idpCalls.add(what);
            }
        };
        controller = new UsersController(new JdbcUserAccountActionService(ds,
                new JdbcAuditTrailService(ds, "k".getBytes(StandardCharsets.UTF_8)), idp, false),
                new com.continuum.iam.internal.JdbcAdminDirectory(ds, false),
                new com.continuum.iam.internal.JdbcDirectorySyncService(ds, false, src -> 0, Runnable::run, ""));
    }

    @BeforeEach
    void resetUser() {
        idpCalls.clear();
        idpDown = false;
        jt.update("DELETE FROM platform.user_account WHERE id IN (?, ?)", USER, ACTOR);
        jt.update("INSERT INTO platform.user_account(id, tenant_id, username, source, mfa_configured)"
                + " VALUES (?, ?, 'insp1.demo', 'LOCAL', TRUE)", USER, ACTOR_TENANT);
        jt.update("INSERT INTO platform.user_account(id, tenant_id, username, source) VALUES (?, ?, 'admin', 'LOCAL')",
                ACTOR, ACTOR_TENANT);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .claim("tenant_id", ACTOR_TENANT.toString()).claim("continuum_user_id", ACTOR.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static UserAccount act(UUID id, String code, Integer version, String comment) {
        return controller.performUserAction(id, code, new ActionRequest().version(version).comment(comment))
                .getBody();
    }

    private static HttpStatus failure(UUID id, String code, Integer version, String comment) {
        return (HttpStatus) org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
                () -> act(id, code, version, comment)).getStatusCode();
    }

    @Test
    void lockThenUnlock() {
        UserAccount l = act(USER, "lock", 0, "suspicious");
        assertThat(l.getAccountStatus()).isEqualTo(UserAccount.AccountStatusEnum.LOCKED);
        assertThat(l.getVersion()).isEqualTo(1);
        assertThat(l.getAvailableActions()).extracting(a -> a.getCode())
                .containsExactly("unlock", "reset-mfa", "disable", "close-sessions");
        UserAccount u = act(USER, "unlock", 1, null);
        assertThat(u.getAccountStatus()).isEqualTo(UserAccount.AccountStatusEnum.ACTIVE);
        assertThat(u.getVersion()).isEqualTo(2);
    }

    @Test
    void disableLeavesNoAction() {
        UserAccount d = act(USER, "disable", 0, "left the bank");
        assertThat(d.getAccountStatus()).isEqualTo(UserAccount.AccountStatusEnum.DISABLED);
        assertThat(d.getAvailableActions()).isEmpty();
    }

    @Test
    void commentRequiredForLockAndDisable() {
        assertThat(failure(USER, "lock", 0, null)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failure(USER, "disable", 0, "  ")).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void staleVersionIsConflictAndLeavesStatus() {
        assertThat(failure(USER, "lock", 5, "x")).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jt.queryForObject("SELECT status FROM platform.user_account WHERE id = ?", String.class, USER))
                .isEqualTo("ACTIVE");
    }

    @Test
    void invalidTransitionUnknownActionAndUnknownUser() {
        assertThat(failure(USER, "unlock", 0, null)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(failure(USER, "frobnicate", 0, null)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failure(UUID.randomUUID(), "lock", 0, "x")).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void noSelfActionExceptCloseSessions() {
        assertThat(failure(ACTOR, "lock", 0, "x")).isEqualTo(HttpStatus.CONFLICT);
        assertThat(act(ACTOR, "close-sessions", 0, null).getAvailableActions())
                .extracting(a -> a.getCode()).containsExactly("close-sessions");
    }

    @Test
    void resetMfaClearsFlagAndCallsIdentityProvider() {
        UserAccount r = act(USER, "reset-mfa", 0, null);
        assertThat(r.getMfaConfigured()).isFalse();
        assertThat(r.getVersion()).isEqualTo(1);
        assertThat(r.getAvailableActions()).extracting(a -> a.getCode()).doesNotContain("reset-mfa");
        assertThat(idpCalls).containsExactly("reset-mfa");
    }

    @Test
    void closeSessionsKeepsVersionAndIdentityFailureRollsBack() {
        assertThat(act(USER, "close-sessions", 0, null).getVersion()).isZero();
        assertThat(idpCalls).containsExactly("close-sessions");
        idpDown = true;
        assertThat(failure(USER, "reset-mfa", 0, null)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void actionIsAudited() {
        Integer before = jt.queryForObject("SELECT count(*) FROM audit_event WHERE object_id = ?",
                Integer.class, USER.toString());
        act(USER, "lock", 0, "motif");
        assertThat(jt.queryForObject("SELECT count(*) FROM audit_event WHERE object_id = ?",
                Integer.class, USER.toString())).isEqualTo(before + 1);
    }
}
