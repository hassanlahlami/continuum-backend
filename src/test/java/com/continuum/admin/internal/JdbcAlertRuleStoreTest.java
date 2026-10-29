package com.continuum.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.continuum.admin.AlertRuleStore.Rule;
import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** {@link JdbcAlertRuleStore} on a real PostgreSQL ({@code -Dit.db.url}), schema from continuum-migrator. */
class JdbcAlertRuleStoreTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static DataSource dataSource;
    private final AuditTrailService audit = mock(AuditTrailService.class);

    @BeforeAll
    static void apply() throws SQLException {
        String url = System.getProperty("it.db.url");
        Assumptions.assumeTrue(url != null && !url.isBlank() && !url.startsWith("${"), "no it.db.url");
        Path script = Path.of("..", "continuum-migrator", "src", "main", "resources", "db", "postgresql",
                "V202610291000__platform_alert_rule.sql").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.exists(script), "migrator script missing: " + script);
        dataSource = new DriverManagerDataSource(
                url, System.getProperty("it.db.user"), System.getProperty("it.db.password"));
        JdbcTemplate jt = new JdbcTemplate(dataSource);
        jt.execute("DROP SCHEMA IF EXISTS platform CASCADE");
        jt.execute("CREATE SCHEMA platform");
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(new FileSystemResource(script), StandardCharsets.UTF_8));
        }
    }

    @Test
    void createListToggleUpdateDelete() {
        JdbcAlertRuleStore s = new JdbcAlertRuleStore(new PlatformWriteConfig.PlatformWrite(dataSource), audit, "postgresql");
        Rule created = s.create(new Rule(null, "CBS down", "CTN-INT-0102", 5, "PT10M",
                List.of("IN_APP", "EMAIL", "EMAIL"), true), TENANT, USER);
        assertThat(created.id()).isNotNull();
        assertThat(created.channels()).containsExactly("IN_APP", "EMAIL");

        assertThat(s.list()).anySatisfy(r -> {
            assertThat(r.id()).isEqualTo(created.id());
            assertThat(r.channels()).containsExactlyInAnyOrder("IN_APP", "EMAIL");
            assertThat(r.enabled()).isTrue();
        });

        Rule toggled = s.update(created.id(), new Rule(null, "CBS down", "CTN-INT-0102", 5, "PT10M",
                List.of("SIEM"), false), TENANT, USER).orElseThrow();
        assertThat(toggled.enabled()).isFalse();
        assertThat(s.list().stream().filter(r -> r.id().equals(created.id())).findFirst().orElseThrow().channels())
                .containsExactly("SIEM");

        assertThat(s.update(UUID.randomUUID(), created, TENANT, USER)).isEmpty();
        assertThat(s.delete(created.id(), TENANT, USER)).isTrue();
        assertThat(s.delete(created.id(), TENANT, USER)).isFalse();
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT count(*) FROM platform.alert_rule_channel WHERE alert_rule_id = ?", Integer.class, created.id()))
                .isZero();
        verify(audit, times(3)).append(ArgumentMatchers.any(AuditEntry.class));
    }
}
