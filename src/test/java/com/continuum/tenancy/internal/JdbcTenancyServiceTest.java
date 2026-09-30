package com.continuum.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.tenancy.api.Tenant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie {@link JdbcTenancyService} sur base réelle (plan §4.7). Base via {@code -Dit.db.url}.
 */
class JdbcTenancyServiceTest {

    private static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID T2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID UNKNOWN = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private static DataSource dataSource;

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
        JdbcTemplate jt = new JdbcTemplate(dataSource);
        jt.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jt.execute("DROP SCHEMA public CASCADE");
        jt.execute("CREATE SCHEMA public");

        run(pg.resolve("V202609301200__platform_tenant.sql"));

        jt.update("INSERT INTO platform.tenant(id, code, label, active) VALUES (?, 'BANQUE-DEMO', 'Banque Démo', true)", T1);
        jt.update("INSERT INTO platform.tenant(id, code, label, active) VALUES (?, 'OLD-CORP', 'Ancienne entité', false)", T2);
    }

    private static void run(Path script) {
        try (Connection c = dataSource.getConnection()) {
            Resource r = new FileSystemResource(script);
            ScriptUtils.executeSqlScript(c, new EncodedResource(r, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    private static JdbcTenancyService service() {
        return new JdbcTenancyService(dataSource);
    }

    @Test
    void findReturnsTenant() {
        assertThat(service().find(T1)).contains(new Tenant(T1, "BANQUE-DEMO", "Banque Démo", true));
    }

    @Test
    void findUnknownReturnsEmpty() {
        assertThat(service().find(UNKNOWN)).isEmpty();
    }

    @Test
    void listActiveExcludesInactive() {
        assertThat(service().listActive())
                .extracting(Tenant::code)
                .containsExactly("BANQUE-DEMO");
    }
}
