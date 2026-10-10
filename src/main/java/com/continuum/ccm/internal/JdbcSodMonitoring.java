package com.continuum.ccm.internal;

import com.continuum.ccm.api.SodMonitoring;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contrôle CCM de cumul SoD (plan §5), PostgreSQL. Un seul {@code INSERT … SELECT … ON CONFLICT DO
 * NOTHING} : il calcule les fonctionnalités détenues par utilisateur (même logique que §2.1.6), joint
 * les règles {@code STATIC}/{@code BLOCK} dont les deux fonctionnalités sont détenues par le même
 * utilisateur, et crée un constat (source {@code CCM}) par cumul. La contrainte d'unicité
 * {@code finding(tenant_id, reference)} rend le contrôle idempotent : le relancer ne duplique rien.
 *
 * <p>Écrit dans la table {@code finding} du module {@code audit} au niveau base (pas de dépendance de
 * code entre modules) ; une API d'écriture de constat côté {@code audit} est un raffinement ultérieur.
 */
@Service
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
            INSERT INTO finding (id, tenant_id, mission_id, org_unit_id, reference, title,
                                 status_code, status_label, criticality_code, criticality_label, source,
                                 fact, criterion)
            SELECT gen_random_uuid(), :tenant, NULL, MIN(ua.unit_id::text)::uuid,
                   'CCM-SOD-' || left(r.id::text, 8) || '-' || left(ua.user_id::text, 8),
                   'Cumul SoD : ' || r.feature_a || ' + ' || r.feature_b,
                   'OUVERT', 'Ouvert', 'HIGH', 'Élevée', 'CCM',
                   'L''utilisateur détient simultanément ' || r.feature_a || ' et ' || r.feature_b || '.',
                   'Règle SoD (§2.2) : ces deux fonctionnalités ne doivent pas être cumulées.'
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
