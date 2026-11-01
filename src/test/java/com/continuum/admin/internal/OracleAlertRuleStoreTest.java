package com.continuum.admin.internal;

import com.continuum.PlatformWriteConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.continuum.admin.AlertRuleStore.Rule;
import com.continuum.audittrail.api.AuditEntry;
import com.continuum.audittrail.api.AuditTrailService;
import com.continuum.testsupport.OracleTestSchema;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link JdbcAlertRuleStore} on a real Oracle ({@code -Dit.oracle.url=jdbc:oracle:…}); same assertions as the PostgreSQL test. */
@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleAlertRuleStoreTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static DataSource dataSource;
    private final AuditTrailService audit = mock(AuditTrailService.class);

    @BeforeAll
    static void setUp() {
        OracleTestSchema db = OracleTestSchema.connect().reset();
        // platform.* is written by the PLATFORM owner (the entity account only gets SELECT/REFERENCES, ADR 0012)
        dataSource = db.platformJdbc().getDataSource();
        db.platform("V202610291000__platform_alert_rule.sql");
    }

    @Test
    void createListToggleUpdateDelete() {
        JdbcAlertRuleStore s = new JdbcAlertRuleStore(new PlatformWriteConfig.PlatformWrite(dataSource), audit, "oracle");
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
                "SELECT count(*) FROM platform.alert_rule_channel", Integer.class)).isZero();
        verify(audit, times(3)).append(ArgumentMatchers.any(AuditEntry.class));
    }
}
