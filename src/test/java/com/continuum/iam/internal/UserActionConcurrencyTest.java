package com.continuum.iam.internal;

import com.continuum.audittrail.internal.JdbcAuditTrailService;
import com.continuum.iam.api.UserAccountActionService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** {@link UserActionConcurrencyContract} on a real PostgreSQL ({@code -Dit.db.url}). */
class UserActionConcurrencyTest extends UserActionConcurrencyContract {

    private static DataSource dataSource;
    private static JdbcTemplate jt;
    private static JdbcAuditTrailService audit;

    @BeforeAll
    static void applyAndSeed() {
        String url = System.getProperty("it.db.url");
        Assumptions.assumeTrue(url != null && !url.isBlank() && !url.startsWith("${"),
                "Base de test absente : lancer avec -Dit.db.url=… -Dit.db.user=… -Dit.db.password=…");
        Path pg = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "postgresql")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(pg), "Scripts du continuum-migrator introuvables : " + pg);
        dataSource = new DriverManagerDataSource(
                url, System.getProperty("it.db.user"), System.getProperty("it.db.password"));
        jt = new JdbcTemplate(dataSource);
        jt.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jt.execute("DROP SCHEMA public CASCADE");
        jt.execute("CREATE SCHEMA public");
        for (String s : List.of("V202609300900__platform_feature_catalog.sql",
                "V202609301000__entity_iam_structure.sql", "V202609301100__entity_audit_trail.sql",
                "V202609301200__platform_tenant.sql", "V202610271000__platform_tenant_status_version.sql",
                "V202610271100__platform_user_account.sql")) {
            try (Connection c = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(c,
                        new EncodedResource(new FileSystemResource(pg.resolve(s)), StandardCharsets.UTF_8));
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        jt.update("INSERT INTO platform.tenant(id, code, label) VALUES (?, 'ADMIN', 'Admin')", ACTOR_TENANT);
        audit = new JdbcAuditTrailService(dataSource, "k".getBytes(StandardCharsets.UTF_8));
    }

    @Override
    UserAccountActionService service() {
        return new JdbcUserAccountActionService(dataSource, audit, NOOP_IDP, false);
    }

    @Override
    void resetUser() {
        jt.update("DELETE FROM platform.user_account WHERE id = ?", USER);
        jt.update("INSERT INTO platform.user_account(id, tenant_id, username, source, mfa_configured)"
                + " VALUES (?, ?, 'insp1.demo', 'LOCAL', TRUE)", USER, ACTOR_TENANT);
    }

    @Override
    long version() {
        return jt.queryForObject("SELECT version FROM platform.user_account WHERE id = ?", Long.class, USER);
    }

    @Override
    String status() {
        return jt.queryForObject("SELECT status FROM platform.user_account WHERE id = ?", String.class, USER);
    }
}
