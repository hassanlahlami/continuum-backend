package com.continuum.iam.internal;

import com.continuum.iam.api.DirectoryFederation;
import com.continuum.iam.api.DirectorySyncService;
import com.continuum.testsupport.OracleTestSchema;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link DirectorySyncContract} on a real Oracle ({@code -Dit.oracle.url=jdbc:oracle:...}). */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleDirectorySyncServiceTest extends DirectorySyncContract {

    private static DataSource dataSource;
    private static DataSource platformDataSource;
    private static JdbcTemplate platform;

    @BeforeAll
    static void setUp() {
        OracleTestSchema db = OracleTestSchema.connect().reset();
        dataSource = db.dataSource();
        platformDataSource = db.platformDataSource();
        platform = db.platformJdbc();
        // One call: the grant callback runs after it and requires directory_sync_state to exist.
        db.platform("V202609300900__platform_feature_catalog.sql", "V202609301200__platform_tenant.sql",
                "V202610271000__platform_tenant_status_version.sql",
                "V202610281000__platform_directory_sync_state.sql");
    }

    @Override
    JdbcTemplate platformJdbc() {
        return platform;
    }

    @Override
    boolean oracle() {
        return true;
    }

    @Override
    DirectorySyncService service(DirectoryFederation federation, Executor executor) {
        return new JdbcDirectorySyncService(platformDataSource, true, federation, executor, SOURCE);
    }
}
