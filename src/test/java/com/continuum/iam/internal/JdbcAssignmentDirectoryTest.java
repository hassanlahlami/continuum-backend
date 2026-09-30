package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.iam.api.AssignmentView;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Vérifie {@link JdbcAssignmentDirectory} sur base réelle (plan §2.1.3). Base via {@code -Dit.db.url}.
 */
class JdbcAssignmentDirectoryTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID DIR_AUDIT = UUID.fromString("b0000000-0000-0000-0000-000000000002");

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

        run(new FileSystemResource(pg.resolve("V202609300900__platform_feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("R__feature_catalog.sql")));
        run(new FileSystemResource(pg.resolve("V202609301000__entity_iam_structure.sql")));
        run(new ClassPathResource("iam-authz-seed.sql"));
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("script " + script, e);
        }
    }

    @Test
    void listsActiveAssignmentWithResolvedLabelAndUnit() {
        assertThat(new JdbcAssignmentDirectory(dataSource).activeAssignments(TENANT, USER, "fr"))
                .extracting(AssignmentView::positionCode, AssignmentView::positionLabel,
                        AssignmentView::unitId, AssignmentView::unitCode, AssignmentView::kind)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "CHEF_DIR", "Chef de direction", DIR_AUDIT, "DIR_AUDIT", "HOLDER"));
    }

    @Test
    void emptyForUserWithoutAssignments() {
        assertThat(new JdbcAssignmentDirectory(dataSource)
                .activeAssignments(TENANT, UUID.fromString("00000000-0000-0000-0000-0000000000ee"), "fr"))
                .isEmpty();
    }
}
