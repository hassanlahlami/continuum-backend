package com.continuum.ccm.internal;

import com.continuum.ccm.api.SodMonitoring;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contrôle CCM de cumul SoD (plan §5), PostgreSQL. Un seul {@code INSERT … SELECT … ON CONFLICT DO
 * NOTHING} : il calcule les fonctionnalités détenues par utilisateur (même logique que §2.1.6), joint
 * les règles {@code STATIC}/{@code BLOCK} dont les deux fonctionnalités sont détenues par le même
 * utilisateur, et crée une <strong>exception</strong> (table {@code ccm_exception}, D34) par cumul. La
 * contrainte d'unicité {@code ccm_exception(tenant_id, reference)} rend le contrôle idempotent : le
 * relancer ne duplique rien.
 *
 * <p>Audit par exception (D34, §5.3) : aucun constat n'est créé ; l'exception porte sa chaîne de preuve
 * ({@code source_record} = cumul détecté, {@code parameters_used} = règle appliquée). Le bean est
 * sélectionné par dialecte dans {@link SodMonitoringConfig} (variante Oracle : {@code MERGE},
 * {@code RAW(16)}, {@code JSON_OBJECT}).
 */
public class JdbcSodMonitoring implements SodMonitoring {

    private static final String SQL = """
            WITH user_feature AS (
              SELECT DISTINCT a.user_id, rf.feature_code AS code, a.org_unit_id AS unit_id
              FROM assignment a
              JOIN position p          ON p.id = a.position_id AND p.active
              JOIN position_role pr    ON pr.position_id = p.id
              JOIN role_feature rf     ON rf.role_id = pr.role_id
              JOIN platform.feature f  ON f.code = rf.feature_code AND NOT f.deprecated
              JOIN platform.tenant_module tm
                                       ON tm.tenant_id = a.tenant_id AND tm.module = f.module AND tm.enabled
              WHERE a.tenant_id = :tenant
                AND CURRENT_DATE >= a.valid_from AND (a.valid_to IS NULL OR CURRENT_DATE <= a.valid_to)
            )
            INSERT INTO ccm_exception (id, tenant_id, control_code, org_unit_id, reference, title,
                                       criticality_code, criticality_label, fact, criterion,
                                       source_record, parameters_used, data_source)
            SELECT gen_random_uuid(), :tenant, 'SOD-CUMUL', MIN(ua.unit_id::text)::uuid,
                   'CCM-SOD-' || left(r.id::text, 8) || '-' || left(ua.user_id::text, 8),
                   'Cumul SoD : ' || r.feature_a || ' + ' || r.feature_b,
                   'HIGH', 'Élevée',
                   'L''utilisateur détient simultanément ' || r.feature_a || ' et ' || r.feature_b || '.',
                   'Règle SoD (§2.2) : ces deux fonctionnalités ne doivent pas être cumulées.',
                   jsonb_build_object('userId', ua.user_id::text, 'ruleId', r.id::text,
                                      'featureA', r.feature_a, 'featureB', r.feature_b),
                   jsonb_build_object('ruleType', 'STATIC', 'severity', 'BLOCK'),
                   'Base plateforme (habilitations)'
            FROM sod_rule r
            JOIN user_feature ua ON ua.code = r.feature_a
            JOIN user_feature ub ON ub.code = r.feature_b AND ub.user_id = ua.user_id
            WHERE r.tenant_id = :tenant AND r.type = 'STATIC' AND r.severity = 'BLOCK'
            GROUP BY r.id, r.feature_a, r.feature_b, ua.user_id
            ON CONFLICT (tenant_id, reference) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSodMonitoring(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    @Transactional
    public int detectCumuls(UUID tenantId) {
        return jdbc.update(SQL, new MapSqlParameterSource("tenant", tenantId));
    }
}
