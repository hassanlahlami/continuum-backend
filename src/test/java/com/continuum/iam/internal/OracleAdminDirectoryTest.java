package com.continuum.iam.internal;

import com.continuum.iam.api.AdminDirectory;
import com.continuum.testsupport.OracleTestSchema;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link AdminDirectoryContract} on a real Oracle ({@code -Dit.oracle.url=jdbc:oracle:…}). */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleAdminDirectoryTest extends AdminDirectoryContract {

    private static DataSource dataSource;
    private static JdbcTemplate entity;
    private static JdbcTemplate platform;

    @BeforeAll
    static void setUp() {
        OracleTestSchema db = OracleTestSchema.connect().reset();
        dataSource = db.dataSource();
        entity = new JdbcTemplate(dataSource);
        platform = db.platformJdbc();
        db.platform("V202609300900__platform_feature_catalog.sql");
        db.platform("V202609301200__platform_tenant.sql");
        db.platform("V202610271000__platform_tenant_status_version.sql");
        db.platform("V202610271100__platform_user_account.sql");
        db.entity("V202609301000__entity_iam_structure.sql");
        db.entity("V202609301100__entity_audit_trail.sql");
    }

    @Override
    JdbcTemplate platformJdbc() {
        return platform;
    }

    @Override
    JdbcTemplate entityJdbc() {
        return entity;
    }

    @Override
    boolean oracle() {
        return true;
    }

    @Override
    AdminDirectory directory() {
        return new JdbcAdminDirectory(dataSource, true);
    }

    @Override
    void clean() {
        entityTables().forEach(t -> entity.execute("DELETE FROM " + t));
        platformTables().forEach(t -> platform.execute("DELETE FROM " + t));
    }
}
