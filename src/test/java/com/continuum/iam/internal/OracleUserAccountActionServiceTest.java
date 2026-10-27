package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.continuum.audittrail.internal.OracleAuditTrailService;
import com.continuum.iam.api.IdentityProviderAdmin;
import com.continuum.iam.api.UserAccountActionService.IllegalUserActionException;
import com.continuum.iam.api.UserAccountActionService.StaleUserException;
import com.continuum.iam.api.UserAccountActionService.UnknownUserException;
import com.continuum.iam.api.UserAccountActionService.UserAccountView;
import com.continuum.iam.api.UserAccountActionService.UserActionCommand;
import com.continuum.testsupport.OracleTestSchema;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Actions de compte (D40) sur une <strong>vraie base Oracle</strong>, à parité avec {@code UsersControllerTest}.
 * Activé seulement si {@code -Dit.oracle.url=jdbc:oracle:…}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleUserAccountActionServiceTest {

    private static final UUID ACTOR_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final String USER_HEX = "000000000000000000000000000000b1";
    private static final String TENANT_HEX = "00000000000000000000000000000001";

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate platform;
    private static OracleAuditTrailService auditTrail;
    private static final List<String> idpCalls = new ArrayList<>();

    @BeforeAll
    static void setUp() {
        OracleTestSchema db = OracleTestSchema.connect().reset();
        dataSource = db.dataSource();
        platform = db.platformJdbc();
        db.platform("V202609300900__platform_feature_catalog.sql");
        db.platform("V202609301200__platform_tenant.sql");
        db.platform("V202610271000__platform_tenant_status_version.sql");
        db.platform("V202610271100__platform_user_account.sql");
        db.entity("V202609301000__entity_iam_structure.sql");
        db.entity("V202609301100__entity_audit_trail.sql");
        auditTrail = new OracleAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
        platform.update("INSERT INTO platform.tenant(id, code, label) VALUES (HEXTORAW(?), 'ADMIN', 'Admin')",
                TENANT_HEX);
    }

    @BeforeEach
    void resetUser() {
        idpCalls.clear();
        platform.update("DELETE FROM platform.user_account WHERE id = HEXTORAW(?)", USER_HEX);
        platform.update("INSERT INTO platform.user_account(id, tenant_id, username, source, mfa_configured)"
                + " VALUES (HEXTORAW(?), HEXTORAW(?), 'insp1.demo', 'LOCAL', 1)", USER_HEX, TENANT_HEX);
    }

    private static UserAccountView act(String action, Long version, String comment) {
        IdentityProviderAdmin idp = new IdentityProviderAdmin() {
            @Override
            public void resetMfa(UUID id) {
                idpCalls.add("reset-mfa");
            }

            @Override
            public void closeSessions(UUID id) {
                idpCalls.add("close-sessions");
            }
        };
        return new JdbcUserAccountActionService(dataSource, auditTrail, idp, true)
                .perform(new UserActionCommand(ACTOR_TENANT, ACTOR, USER, action, version, comment));
    }

    @Test
    void lockUnlockResetMfaAndAudit() {
        UserAccountView l = act("lock", 0L, "suspicious");
        assertThat(l.status()).isEqualTo("LOCKED");
        assertThat(l.version()).isEqualTo(1);
        assertThat(act("unlock", 1L, null).status()).isEqualTo("ACTIVE");
        UserAccountView r = act("reset-mfa", 2L, null);
        assertThat(r.mfaConfigured()).isFalse();
        assertThat(r.version()).isEqualTo(3);
        assertThat(idpCalls).containsExactly("reset-mfa");
        assertThat(act("close-sessions", 3L, null).version()).isEqualTo(3);
        assertThat(auditTrail.hasActed(ACTOR_TENANT, ACTOR, "admin.user.manage", "USER", USER.toString())).isTrue();
    }

    @Test
    void rejections() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> act("lock", 0L, null));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> act("frobnicate", 0L, null));
        assertThatExceptionOfType(StaleUserException.class).isThrownBy(() -> act("lock", 7L, "x"));
        assertThatExceptionOfType(IllegalUserActionException.class).isThrownBy(() -> act("unlock", 0L, null));
        assertThat(act("disable", 0L, "x").status()).isEqualTo("DISABLED");
        assertThatExceptionOfType(IllegalUserActionException.class).isThrownBy(() -> act("lock", 1L, "x"));
        platform.update("DELETE FROM platform.user_account WHERE id = HEXTORAW(?)", USER_HEX);
        assertThatExceptionOfType(UnknownUserException.class).isThrownBy(() -> act("lock", 0L, "x"));
    }
}
