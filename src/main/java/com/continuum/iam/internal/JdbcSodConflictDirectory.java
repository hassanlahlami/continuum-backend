package com.continuum.iam.internal;

import com.continuum.iam.api.SodConflictDirectory;
import com.continuum.iam.api.SodConflictView;
import com.continuum.iam.api.SodRuleDirectory;
import com.continuum.iam.api.SodRuleView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Implémentation de {@link SodConflictDirectory} (plan §2.2) pour les deux dialectes (D4). Même
 * logique que la détection statique par utilisateur : fonctionnalités portées par une affectation
 * valide (position active, module activé, restrictions respectées). Deux affectations ne se cumulent
 * que si leurs portées se recoupent (TENANT, unité identique, ou unité dans l'arbre de l'autre).
 * Seul le SQL (ltree vs table de fermeture, booléens, UUID) change selon le dialecte.
 */
public class JdbcSodConflictDirectory implements SodConflictDirectory {

    private static final String PG_SQL = """
            WITH grants AS (
              SELECT a.id AS aid, a.user_id, p.scope, hu.id AS unit_id, hu.path AS path, rf.feature_code AS code
              FROM assignment a
              JOIN position p          ON p.id = a.position_id AND p.active
              JOIN position_role pr    ON pr.position_id = p.id
              JOIN role_feature rf     ON rf.role_id = pr.role_id
              JOIN platform.feature f  ON f.code = rf.feature_code AND NOT f.deprecated
              JOIN platform.tenant_module tm
                                       ON tm.tenant_id = a.tenant_id AND tm.module = f.module AND tm.enabled
              LEFT JOIN org_unit hu    ON hu.id = a.org_unit_id
              WHERE a.tenant_id = :tenant
                AND CURRENT_DATE >= a.valid_from
                AND (a.valid_to IS NULL OR CURRENT_DATE <= a.valid_to)
                AND (NOT EXISTS (SELECT 1 FROM assignment_feature_restriction r WHERE r.assignment_id = a.id)
                     OR EXISTS  (SELECT 1 FROM assignment_feature_restriction r
                                 WHERE r.assignment_id = a.id AND r.feature_code = rf.feature_code))
            )
            SELECT DISTINCT s.id AS rule_id, g1.user_id, g1.aid AS aid1, g2.aid AS aid2
            FROM sod_rule s
            JOIN grants g1 ON g1.code = s.feature_a
            JOIN grants g2 ON g2.code = s.feature_b AND g2.user_id = g1.user_id
            WHERE s.tenant_id = :tenant AND s.type = 'STATIC'
              AND (   g1.scope = 'TENANT' OR g2.scope = 'TENANT'
                   OR (g1.scope = 'UNIT' AND g2.scope = 'UNIT' AND g1.unit_id = g2.unit_id)
                   OR (g1.scope = 'UNIT' AND g2.scope = 'UNIT_TREE' AND g1.path <@ g2.path)
                   OR (g1.scope = 'UNIT_TREE' AND g2.scope = 'UNIT' AND g2.path <@ g1.path)
                   OR (g1.scope = 'UNIT_TREE' AND g2.scope = 'UNIT_TREE'
                       AND (g1.path <@ g2.path OR g2.path <@ g1.path)))
            """;

    private static final String ORACLE_SQL = """
            WITH grants AS (
              SELECT a.id AS aid, a.user_id, p.scope, a.org_unit_id AS unit_id, rf.feature_code AS code
              FROM assignment a
              JOIN position p          ON p.id = a.position_id AND p.active = 1
              JOIN position_role pr    ON pr.position_id = p.id
              JOIN role_feature rf     ON rf.role_id = pr.role_id
              JOIN platform.feature f  ON f.code = rf.feature_code AND f.deprecated = 0
              JOIN platform.tenant_module tm
                                       ON tm.tenant_id = a.tenant_id AND tm.module = f.module AND tm.enabled = 1
              WHERE a.tenant_id = :tenant
                AND a.valid_from <= CURRENT_DATE AND (a.valid_to IS NULL OR a.valid_to >= CURRENT_DATE)
                AND (NOT EXISTS (SELECT 1 FROM assignment_feature_restriction r WHERE r.assignment_id = a.id)
                     OR EXISTS  (SELECT 1 FROM assignment_feature_restriction r
                                 WHERE r.assignment_id = a.id AND r.feature_code = rf.feature_code))
            )
            SELECT DISTINCT s.id AS rule_id, g1.user_id, g1.aid AS aid1, g2.aid AS aid2
            FROM sod_rule s
            JOIN grants g1 ON g1.code = s.feature_a
            JOIN grants g2 ON g2.code = s.feature_b AND g2.user_id = g1.user_id
            WHERE s.tenant_id = :tenant AND s.type = 'STATIC'
              AND (   g1.scope = 'TENANT' OR g2.scope = 'TENANT'
                   OR (g1.scope = 'UNIT' AND g2.scope = 'UNIT' AND g1.unit_id = g2.unit_id)
                   OR (g1.scope = 'UNIT' AND g2.scope = 'UNIT_TREE' AND EXISTS (
                         SELECT 1 FROM org_unit_closure c
                         WHERE c.ancestor_id = g2.unit_id AND c.descendant_id = g1.unit_id))
                   OR (g1.scope = 'UNIT_TREE' AND g2.scope = 'UNIT' AND EXISTS (
                         SELECT 1 FROM org_unit_closure c
                         WHERE c.ancestor_id = g1.unit_id AND c.descendant_id = g2.unit_id))
                   OR (g1.scope = 'UNIT_TREE' AND g2.scope = 'UNIT_TREE' AND EXISTS (
                         SELECT 1 FROM org_unit_closure c
                         WHERE (c.ancestor_id = g1.unit_id AND c.descendant_id = g2.unit_id)
                            OR (c.ancestor_id = g2.unit_id AND c.descendant_id = g1.unit_id))))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final SodRuleDirectory rules;
    private final boolean oracle;

    public JdbcSodConflictDirectory(DataSource dataSource, SodRuleDirectory rules, boolean oracle) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.rules = rules;
        this.oracle = oracle;
    }

    private record Hit(UUID ruleId, UUID userId, UUID aid1, UUID aid2) {
    }

    @Override
    public List<SodConflictView> listStaticConflicts(UUID tenantId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", oracle ? OracleAuthorizationService.raw(tenantId) : tenantId);
        List<Hit> hits = jdbc.query(oracle ? ORACLE_SQL : PG_SQL, params, (rs, i) -> new Hit(
                uuid(rs.getObject("rule_id")),
                uuid(rs.getObject("user_id")),
                uuid(rs.getObject("aid1")),
                uuid(rs.getObject("aid2"))));

        Map<UUID, SodRuleView> byId = new LinkedHashMap<>();
        rules.listRules(tenantId).forEach(r -> byId.put(r.id(), r));
        List<UUID> order = new ArrayList<>(byId.keySet());

        // Regroupe par (règle, utilisateur) : les affectations impliquées sont fusionnées.
        record Key(UUID ruleId, UUID userId) {
        }
        Map<Key, Set<UUID>> grouped = new LinkedHashMap<>();
        for (Hit h : hits) {
            Set<UUID> via = grouped.computeIfAbsent(new Key(h.ruleId(), h.userId()), k -> new LinkedHashSet<>());
            via.add(h.aid1());
            via.add(h.aid2());
        }
        List<SodConflictView> out = new ArrayList<>();
        grouped.forEach((k, via) -> out.add(new SodConflictView(
                k.userId(), byId.get(k.ruleId()), via.stream().sorted().toList())));
        out.sort(Comparator.comparingInt((SodConflictView c) -> order.indexOf(c.rule().id()))
                .thenComparing(SodConflictView::userId));
        return out;
    }

    private static UUID uuid(Object o) {
        return o instanceof byte[] b ? OracleAuthorizationService.uuid(b) : (UUID) o;
    }
}
