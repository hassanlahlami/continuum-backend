package com.continuum.iam.internal;

import com.continuum.audittrail.internal.OracleAuditTrailService;
import com.continuum.iam.api.UserAccountActionService;
import com.continuum.testsupport.OracleTestSchema;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link UserActionConcurrencyContract} on a real Oracle ({@code -Dit.oracle.url=jdbc:oracle:…}). */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleUserActionConcurrencyTest extends UserActionConcurrencyContract {

    private static final String USER_HEX = "000000000000000000000000000000b1";
    private static final String TENANT_HEX = "00000000000000000000000000000001";

    private static DataSource dataSource;
    private static DataSource platformDataSource;
    private static JdbcTemplate platform;
    private static OracleAuditTrailService audit;

    @BeforeAll
    static void setUp() {
        OracleTestSchema db = OracleTestSchema.connect().reset();
        dataSource = db.dataSource();
        platformDataSource = db.platformDataSource();
        platform = db.platformJdbc();
        db.platform("V202609300900__platform_feature_catalog.sql");
        db.platform("V202609301200__platform_tenant.sql");
        db.platform("V202610271000__platform_tenant_status_version.sql");
        db.platform("V202610271100__platform_user_account.sql");
        db.entity("V202609301000__entity_iam_structure.sql");
        db.entity("V202609301100__entity_audit_trail.sql");
        audit = new OracleAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
        platform.update("INSERT INTO platform.tenant(id, code, label) VALUES (HEXTORAW(?), 'ADMIN', 'Admin')",
                TENANT_HEX);
    }

    @Override
    UserAccountActionService service() {
        return new JdbcUserAccountActionService(platformDataSource, audit, NOOP_IDP, true);
    }

    @Override
    void resetUser() {
        platform.update("DELETE FROM platform.user_account WHERE id = HEXTORAW(?)", USER_HEX);
        platform.update("INSERT INTO platform.user_account(id, tenant_id, username, source, mfa_configured)"
                + " VALUES (HEXTORAW(?), HEXTORAW(?), 'insp1.demo', 'LOCAL', 1)", USER_HEX, TENANT_HEX);
    }

    @Override
    long version() {
        return platform.queryForObject("SELECT version FROM platform.user_account WHERE id = HEXTORAW(?)",
                Long.class, USER_HEX);
    }

    @Override
    String status() {
        return platform.queryForObject("SELECT status FROM platform.user_account WHERE id = HEXTORAW(?)",
                String.class, USER_HEX);
    }
}
