package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.continuum.iam.api.SodConflictView;
import com.continuum.iam.api.SodRuleDirectory;
import com.continuum.iam.api.SodRuleView;
import com.continuum.testsupport.OracleTestSchema;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfSystemProperty(named = "it.oracle.url", matches = "jdbc:oracle:.+")
class OracleSodConflictDirectoryTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AA = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID RULE = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void init() {
        OracleTestSchema db = OracleTestSchema.connect().reset();
        dataSource = db.dataSource();
        db.platform("V202609300900__platform_feature_catalog.sql", "V202609301200__platform_tenant.sql",
                "V202610271000__platform_tenant_status_version.sql", "R__feature_catalog.sql");
        db.entity("V202609301000__entity_iam_structure.sql");
        db.entity("V202610261000__sod_rule_from_pack.sql");
        db.seed(new ClassPathResource("iam-oracle-sod-seed.sql"));
    }

    @Test
    void conflictSqlOnly_withStubRules() {
        SodRuleDirectory stub = t -> List.of(
                new SodRuleView(RULE, "finding.write", "finding.validate", "STATIC", "BLOCK", false));
        List<SodConflictView> out = new JdbcSodConflictDirectory(dataSource, stub, true).listStaticConflicts(TENANT);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).userId()).isEqualTo(AA);
        assertThat(out.get(0).viaAssignments()).hasSize(2);
    }

    @Test
    void conflictWithRealRuleDirectory() {
        SodRuleDirectory real = new JdbcSodRuleDirectory(dataSource, "oracle");
        List<SodConflictView> out = new JdbcSodConflictDirectory(dataSource, real, true).listStaticConflicts(TENANT);
        assertThat(out).hasSize(1);
    }
}
