package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.iam.api.AccountLoginStatusService.Decision;
import com.continuum.iam.api.AccountLoginStatusService.Reason;
import com.continuum.testsupport.OracleTestSchema;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Vérifie {@link OracleAccountLoginStatusService} sur une vraie base Oracle (D4). Activé seulement si
 * {@code -Dit.oracle.url=jdbc:oracle:thin:@…} est fourni.
 */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleAccountLoginStatusServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SUSPENDED = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID UNKNOWN_TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID NOBODY = UUID.fromString("00000000-0000-0000-0000-0000000000ee");

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void applySchemaAndSeed() {
        OracleTestSchema db = OracleTestSchema.connect().reset();
        dataSource = db.dataSource();

        db.platform("V202609300900__platform_feature_catalog.sql");
        db.platform("R__feature_catalog.sql");
        db.entity("V202609301000__entity_iam_structure.sql");
        db.platform("V202609301200__platform_tenant.sql");
        db.seed(new ClassPathResource("iam-oracle-authz-seed.sql"));

        // Données du schéma partagé : écrites par PLATFORM, le backend n'a que SELECT (ADR 0012).
        JdbcTemplate jt = db.platformJdbc();
        jt.update("INSERT INTO platform.tenant(id, code, label, active) VALUES"
                + " (HEXTORAW('00000000000000000000000000000001'), 'T1', 'Actif', 1)");
        jt.update("INSERT INTO platform.tenant(id, code, label, active) VALUES"
                + " (HEXTORAW('00000000000000000000000000000002'), 'T2', 'Suspendu', 0)");
    }

    private static Decision status(UUID tenant, UUID user) {
        return new OracleAccountLoginStatusService(dataSource).loginStatus(tenant, user);
    }

    @Test
    void allowsUserWithActiveAssignment() {
        assertThat(status(TENANT, USER)).isEqualTo(Decision.allow());
    }

    @Test
    void deniesUserWithoutAssignment() {
        assertThat(status(TENANT, NOBODY)).isEqualTo(Decision.deny(Reason.NO_ACTIVE_ASSIGNMENT));
    }

    @Test
    void deniesWhenTenantSuspended() {
        assertThat(status(SUSPENDED, USER)).isEqualTo(Decision.deny(Reason.TENANT_SUSPENDED));
    }

    @Test
    void deniesUnknownTenant() {
        assertThat(status(UNKNOWN_TENANT, USER)).isEqualTo(Decision.deny(Reason.UNKNOWN_ACCOUNT));
    }
}
