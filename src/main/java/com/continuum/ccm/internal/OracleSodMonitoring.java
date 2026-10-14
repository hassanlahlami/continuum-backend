package com.continuum.ccm.internal;

import com.continuum.ccm.api.SodMonitoring;
import java.nio.ByteBuffer;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contrôle CCM de cumul SoD (plan §5) — implémentation <strong>Oracle</strong> (D4), à parité avec
 * {@link JdbcSodMonitoring}. Même logique (fonctionnalités détenues par utilisateur × règles
 * {@code STATIC}/{@code BLOCK}) ; crée une <strong>exception</strong> (table {@code ccm_exception}, D34)
 * par cumul. Spécifique au dialecte : idempotence par {@code MERGE} (pas d'{@code ON CONFLICT}),
 * {@code id} via {@code SYS_GUID()}, UUID en {@code RAW(16)}, JSON via {@code JSON_OBJECT}.
 */
public class OracleSodMonitoring implements SodMonitoring {

    private static final String SQL = """
            MERGE INTO ccm_exception d
            USING (
              WITH user_feature AS (
                SELECT DISTINCT a.user_id, rf.feature_code AS code, a.org_unit_id AS unit_id
                FROM assignment a
                JOIN position p          ON p.id = a.position_id AND p.active = 1
                JOIN position_role pr    ON pr.position_id = p.id
                JOIN role_feature rf     ON rf.role_id = pr.role_id
                JOIN platform.feature f  ON f.code = rf.feature_code AND f.deprecated = 0
                JOIN platform.tenant_module tm
                                         ON tm.tenant_id = a.tenant_id AND tm.module = f.module AND tm.enabled = 1
                WHERE a.tenant_id = :tenant
                  AND a.valid_from <= CURRENT_DATE AND (a.valid_to IS NULL OR a.valid_to >= CURRENT_DATE)
              )
              SELECT HEXTORAW(MIN(RAWTOHEX(ua.unit_id))) AS org_unit_id,
                     'CCM-SOD-' || SUBSTR(RAWTOHEX(r.id), 1, 8) || '-' || SUBSTR(RAWTOHEX(ua.user_id), 1, 8) AS reference,
                     'Cumul SoD : ' || r.feature_a || ' + ' || r.feature_b AS title,
                     'L''utilisateur détient simultanément ' || r.feature_a || ' et ' || r.feature_b || '.' AS fact,
                     'Règle SoD (§2.2) : ces deux fonctionnalités ne doivent pas être cumulées.' AS criterion,
                     JSON_OBJECT('userId' VALUE RAWTOHEX(ua.user_id), 'ruleId' VALUE RAWTOHEX(r.id),
                                 'featureA' VALUE r.feature_a, 'featureB' VALUE r.feature_b) AS source_record,
                     JSON_OBJECT('ruleType' VALUE 'STATIC', 'severity' VALUE 'BLOCK') AS parameters_used
              FROM sod_rule r
              JOIN user_feature ua ON ua.code = r.feature_a
              JOIN user_feature ub ON ub.code = r.feature_b AND ub.user_id = ua.user_id
              WHERE r.tenant_id = :tenant AND r.type = 'STATIC' AND r.severity = 'BLOCK'
              GROUP BY r.id, r.feature_a, r.feature_b, ua.user_id
            ) src
            ON (d.tenant_id = :tenant AND d.reference = src.reference)
            WHEN NOT MATCHED THEN
              INSERT (id, tenant_id, control_code, org_unit_id, reference, title,
                      criticality_code, criticality_label, fact, criterion,
                      source_record, parameters_used, data_source)
              VALUES (SYS_GUID(), :tenant, 'SOD-VIREMENTS', src.org_unit_id, src.reference, src.title,
                      'HIGH', 'Élevée', src.fact, src.criterion,
                      src.source_record, src.parameters_used, 'Base plateforme (habilitations)')
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OracleSodMonitoring(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    @Transactional
    public int detectCumuls(UUID tenantId) {
        return jdbc.update(SQL, new MapSqlParameterSource("tenant", raw(tenantId)));
    }

    private static byte[] raw(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }
}
