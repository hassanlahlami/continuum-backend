package com.continuum.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.continuum.audittrail.internal.OracleAuditTrailService;
import com.continuum.tenancy.api.EntityActionService.EntityActionCommand;
import com.continuum.tenancy.api.EntityActionService.EntityView;
import com.continuum.tenancy.api.EntityActionService.IllegalEntityTransitionException;
import com.continuum.tenancy.api.EntityActionService.StaleEntityException;
import com.continuum.tenancy.api.EntityActionService.UnknownEntityException;
import com.continuum.testsupport.OracleTestSchema;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Cycle de vie d'une entité (§4.7) sur une <strong>vraie base Oracle</strong>, à parité avec le test
 * PostgreSQL ({@code EntitiesControllerTest}) : transitions, verrou optimiste, {@code active}, journal.
 * Activé seulement si {@code -Dit.oracle.url=jdbc:oracle:…}.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleEntityActionServiceTest {

    private static final UUID ACTOR_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String ENTITY_HEX = "00000000000000000000000000000002";

    private static DriverManagerDataSource dataSource;
    private static DriverManagerDataSource platformDataSource;
    private static JdbcTemplate platform;
    private static OracleAuditTrailService auditTrail;

    @BeforeAll
    static void setUp() {
        OracleTestSchema db = OracleTestSchema.connect().reset();
        dataSource = db.dataSource();
        platformDataSource = db.platformDataSource();
        platform = db.platformJdbc();
        db.platform("V202609300900__platform_feature_catalog.sql");
        db.platform("V202609301200__platform_tenant.sql");
        db.platform("V202610271000__platform_tenant_status_version.sql");
        db.entity("V202609301000__entity_iam_structure.sql");
        db.entity("V202609301100__entity_audit_trail.sql");
        auditTrail = new OracleAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void resetEntity() {
        platform.update("DELETE FROM platform.tenant WHERE id = HEXTORAW(?)", ENTITY_HEX);
        platform.update("INSERT INTO platform.tenant(id, code, label) VALUES (HEXTORAW(?), 'FILIALE-01', 'Filiale 01')",
                ENTITY_HEX);
    }

    private static EntityView act(String action, Integer version) {
        return new OracleEntityActionService(platformDataSource, auditTrail)
                .perform(new EntityActionCommand(ACTOR_TENANT, ACTOR, ENTITY, action, version));
    }

    private static int activeFlag() {
        return platform.queryForObject("SELECT active FROM platform.tenant WHERE id = HEXTORAW(?)",
                Integer.class, ENTITY_HEX);
    }

    @Test
    void suspendDeactivatesThenReactivateRestores() {
        EntityView s = act("suspend", 0);
        assertThat(s.status()).isEqualTo("SUSPENDED");
        assertThat(s.version()).isEqualTo(1);
        assertThat(activeFlag()).isZero();
        EntityView r = act("reactivate", 1);
        assertThat(r.status()).isEqualTo("ACTIVE");
        assertThat(activeFlag()).isOne();
        assertThat(auditTrail.hasActed(ACTOR_TENANT, ACTOR, "admin.entity.manage", "ENTITY", ENTITY.toString()))
                .isTrue();
    }

    @Test
    void maintenanceKeepsEntityActive() {
        assertThat(act("maintenance", 0).status()).isEqualTo("MAINTENANCE");
        assertThat(activeFlag()).isOne();
    }

    @Test
    void invalidTransitionStaleAndUnknown() {
        assertThatExceptionOfType(IllegalEntityTransitionException.class).isThrownBy(() -> act("reactivate", 0));
        assertThatExceptionOfType(StaleEntityException.class).isThrownBy(() -> act("suspend", 7));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> act("frobnicate", 0));
        assertThatExceptionOfType(UnknownEntityException.class).isThrownBy(() -> new OracleEntityActionService(
                dataSource, auditTrail).perform(
                new EntityActionCommand(ACTOR_TENANT, ACTOR, UUID.randomUUID(), "suspend", 0)));
    }
}
