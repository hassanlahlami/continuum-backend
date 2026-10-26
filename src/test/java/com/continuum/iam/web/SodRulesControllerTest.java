package com.continuum.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.ScopedFeature;
import com.continuum.iam.internal.JdbcSodConflictDirectory;
import com.continuum.iam.internal.JdbcSodRuleDirectory;
import gen.admin.iam.model.ListSodConflicts200ResponseInner;
import gen.admin.iam.model.SodRule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Vérifie {@link SodRulesController} (contrat {@code admin/tenancy-iam.yaml}, §2.2) sur base réelle :
 * {@code GET /sod-rules} liste les règles SoD du tenant du jeton (statiques et dynamiques), mappées
 * vers le modèle généré. Base via {@code -Dit.db.url} (sinon test neutralisé).
 */
class SodRulesControllerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private static DataSource dataSource;
    private static final UUID PACK_RULE = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static SodRulesController controller;
    private static AuthorizationService authz;

    @BeforeAll
    static void applySchemaAndSeed() {
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
        run(new FileSystemResource(pg.resolve("V202610261000__sod_rule_from_pack.sql")));
        run(new ClassPathResource("iam-sod-seed.sql"));

        jt.update("UPDATE sod_rule SET from_pack = TRUE WHERE id = ?", PACK_RULE);
        authz = mock(AuthorizationService.class);
        JdbcSodRuleDirectory ruleDirectory = new JdbcSodRuleDirectory(dataSource);
        controller = new SodRulesController(
                ruleDirectory, new JdbcSodConflictDirectory(dataSource, ruleDirectory, false), authz);
    }

    private static void run(Resource script) {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("Échec d'application du script " + script, e);
        }
    }

    private static void authenticateFor(UUID tenantId) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none")
                .claim("tenant_id", tenantId.toString())
                .claim("continuum_user_id", ACTOR.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsSodRulesOfTenantFromToken() {
        authenticateFor(TENANT);

        ResponseEntity<List<SodRule>> response = controller.listSodRules();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<SodRule> rules = response.getBody();
        assertThat(rules).isNotNull().hasSize(3);
        assertThat(rules).allSatisfy(r -> assertThat(r.getId()).isNotNull());

        assertThat(rules)
                .extracting(SodRule::getFeatureA, SodRule::getFeatureB, SodRule::getType, SodRule::getSeverity)
                .containsExactlyInAnyOrder(
                        tuple("finding.write", "finding.validate",
                                SodRule.TypeEnum.STATIC, SodRule.SeverityEnum.BLOCK),
                        tuple("finding.write", "finding.review",
                                SodRule.TypeEnum.STATIC, SodRule.SeverityEnum.WARN),
                        tuple("finding.validate", "finding.review",
                                SodRule.TypeEnum.DYNAMIC, SodRule.SeverityEnum.BLOCK));

        assertThat(rules).filteredOn(r -> r.getType() == SodRule.TypeEnum.STATIC).hasSize(2);
        assertThat(rules).filteredOn(r -> r.getType() == SodRule.TypeEnum.DYNAMIC).hasSize(1);
    }

    @Test
    void returnsEmptyForTenantWithoutRules() {
        authenticateFor(OTHER_TENANT);

        ResponseEntity<List<SodRule>> response = controller.listSodRules();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isEmpty();
    }

    @Test
    void exposesFromPackAndActionsForManager() {
        authenticateFor(TENANT);
        when(authz.permissions(TENANT, ACTOR))
                .thenReturn(List.of(new ScopedFeature("admin.sod.manage", "TENANT", null)));

        List<SodRule> rules = controller.listSodRules().getBody();

        SodRule pack = rules.stream().filter(r -> PACK_RULE.equals(r.getId())).findFirst().orElseThrow();
        assertThat(pack.getFromPack()).isTrue();
        assertThat(pack.getAvailableActions()).extracting("code").containsExactly("edit");
        assertThat(rules).filteredOn(r -> !PACK_RULE.equals(r.getId())).allSatisfy(r -> {
            assertThat(r.getFromPack()).isFalse();
            assertThat(r.getAvailableActions()).extracting("code").containsExactly("edit", "delete");
        });
    }

    @Test
    void noActionsWithoutManageRight() {
        authenticateFor(TENANT);
        when(authz.permissions(TENANT, ACTOR))
                .thenReturn(List.of(new ScopedFeature("admin.sod.read", "TENANT", null)));

        assertThat(controller.listSodRules().getBody())
                .allSatisfy(r -> assertThat(r.getAvailableActions()).isEmpty());
    }

    @Test
    void listsStaticConflictsOnlyForOverlappingScopes() {
        authenticateFor(TENANT);
        UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        JdbcTemplate jt = new JdbcTemplate(dataSource);
        String t = TENANT.toString();
        // Deux unités sœurs, deux rôles (écriture / validation), deux positions UNIT, un utilisateur :
        // il détient les deux fonctionnalités mais sur des unités distinctes -> pas de conflit.
        jt.update("INSERT INTO org_unit(id, tenant_id, type_id, parent_id, code, label, path) VALUES "
                + "('b0000000-0000-0000-0000-000000000002', ?, 'a0000000-0000-0000-0000-000000000001', "
                + "'b0000000-0000-0000-0000-000000000001', 'U1', '{\"fr\":\"U1\"}', 'root.u1'), "
                + "('b0000000-0000-0000-0000-000000000003', ?, 'a0000000-0000-0000-0000-000000000001', "
                + "'b0000000-0000-0000-0000-000000000001', 'U2', '{\"fr\":\"U2\"}', 'root.u2')",
                TENANT, TENANT);
        jt.update("INSERT INTO role(id, tenant_id, code, label, kind) VALUES "
                + "('c0000000-0000-0000-0000-000000000002', ?, 'W', '{\"fr\":\"W\"}', 'ORGANISATIONAL'), "
                + "('c0000000-0000-0000-0000-000000000003', ?, 'V', '{\"fr\":\"V\"}', 'ORGANISATIONAL')",
                TENANT, TENANT);
        jt.update("INSERT INTO role_feature(role_id, feature_code) VALUES "
                + "('c0000000-0000-0000-0000-000000000002', 'finding.write'), "
                + "('c0000000-0000-0000-0000-000000000003', 'finding.validate')");
        jt.update("INSERT INTO position(id, tenant_id, code, label, rank, scope) VALUES "
                + "('d0000000-0000-0000-0000-000000000002', ?, 'PW', '{\"fr\":\"PW\"}', 30, 'UNIT'), "
                + "('d0000000-0000-0000-0000-000000000003', ?, 'PV', '{\"fr\":\"PV\"}', 31, 'UNIT')",
                TENANT, TENANT);
        jt.update("INSERT INTO position_role(position_id, role_id) VALUES "
                + "('d0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000002'), "
                + "('d0000000-0000-0000-0000-000000000003', 'c0000000-0000-0000-0000-000000000003')");
        jt.update("INSERT INTO assignment(id, tenant_id, user_id, position_id, org_unit_id, kind, valid_from) VALUES "
                + "('e0000000-0000-0000-0000-000000000002', ?, ?, 'd0000000-0000-0000-0000-000000000002', "
                + "'b0000000-0000-0000-0000-000000000002', 'HOLDER', CURRENT_DATE), "
                + "('e0000000-0000-0000-0000-000000000003', ?, ?, 'd0000000-0000-0000-0000-000000000003', "
                + "'b0000000-0000-0000-0000-000000000003', 'HOLDER', CURRENT_DATE)",
                TENANT, other, TENANT, other);
        try {
            List<ListSodConflicts200ResponseInner> conflicts = controller.listSodConflicts().getBody();

            // Seul ACTOR (portée TENANT) est en conflit, sur les deux règles statiques, jamais la dynamique.
            assertThat(conflicts).hasSize(2);
            assertThat(conflicts).allSatisfy(c -> {
                assertThat(c.getUser().getId()).isEqualTo(ACTOR);
                assertThat(c.getRule().getType()).isEqualTo(SodRule.TypeEnum.STATIC);
                assertThat(c.getViaAssignments())
                        .containsExactly(UUID.fromString("e0000000-0000-0000-0000-000000000001"));
            });
            assertThat(conflicts).extracting(c -> c.getRule().getSeverity())
                    .containsExactlyInAnyOrder(SodRule.SeverityEnum.BLOCK, SodRule.SeverityEnum.WARN);

            // Même unité : le conflit apparaît avec les deux affectations.
            jt.update("UPDATE assignment SET org_unit_id = 'b0000000-0000-0000-0000-000000000002' "
                    + "WHERE id = 'e0000000-0000-0000-0000-000000000003'");
            List<ListSodConflicts200ResponseInner> same = controller.listSodConflicts().getBody();
            assertThat(same).filteredOn(c -> other.equals(c.getUser().getId())).hasSize(1)
                    .allSatisfy(c -> assertThat(c.getViaAssignments()).hasSize(2));
        } finally {
            jt.update("DELETE FROM assignment WHERE user_id = ?", other);
            jt.update("DELETE FROM position_role WHERE position_id IN "
                    + "('d0000000-0000-0000-0000-000000000002','d0000000-0000-0000-0000-000000000003')");
            jt.update("DELETE FROM position WHERE id IN "
                    + "('d0000000-0000-0000-0000-000000000002','d0000000-0000-0000-0000-000000000003')");
            jt.update("DELETE FROM role_feature WHERE role_id IN "
                    + "('c0000000-0000-0000-0000-000000000002','c0000000-0000-0000-0000-000000000003')");
            jt.update("DELETE FROM role WHERE id IN "
                    + "('c0000000-0000-0000-0000-000000000002','c0000000-0000-0000-0000-000000000003')");
            jt.update("DELETE FROM org_unit WHERE id IN "
                    + "('b0000000-0000-0000-0000-000000000002','b0000000-0000-0000-0000-000000000003')");
        }
    }

    @Test
    void noConflictsForTenantWithoutAssignments() {
        authenticateFor(OTHER_TENANT);
        assertThat(controller.listSodConflicts().getBody()).isEmpty();
    }
}
