package com.continuum.ccm.internal;

import com.continuum.ccm.api.CcmPopulationGateway;
import com.continuum.ccm.api.CcmPopulationGateway.DormantAccountCriteria;
import com.continuum.ccm.api.CcmPopulationGateway.MovementAnomaly;
import com.continuum.ccm.api.CcmPopulationGateway.OutOfBoundsCriteria;
import com.continuum.ccm.api.PopulationControlMonitoring;
import java.math.BigDecimal;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Matérialisation PostgreSQL des anomalies CBS en <strong>exceptions</strong> (table {@code ccm_exception},
 * D34), §5. Lit via {@link CcmPopulationGateway} (CBS Oracle) et écrit dans la base plateforme — deux
 * sources distinctes. L'unicité {@code ccm_exception(tenant_id, reference)} rend le contrôle idempotent.
 *
 * <p>Aucun constat n'est créé (D34, §5.3) : l'exception porte sa chaîne de preuve — enregistrement source
 * du CBS ({@code source_record}), paramètres d'exécution ({@code parameters_used}), source de données.
 */
public class JdbcPopulationControlMonitoring implements PopulationControlMonitoring {

    private static final String INSERT_SQL = """
            INSERT INTO ccm_exception (id, tenant_id, control_code, org_unit_id, reference, title,
                                       criticality_code, criticality_label, fact, criterion,
                                       source_record, parameters_used, data_source)
            VALUES (gen_random_uuid(), :tenant, :controlCode, NULL, :reference, :title,
                    :critCode, :critLabel, :fact, :criterion,
                    CAST(:sourceRecord AS jsonb), CAST(:params AS jsonb), :dataSource)
            ON CONFLICT (tenant_id, reference) DO NOTHING
            """;

    private final CcmPopulationGateway gateway;
    private final NamedParameterJdbcTemplate jdbc;
    private final String dataSourceLabel;

    public JdbcPopulationControlMonitoring(
            CcmPopulationGateway gateway, DataSource platformDataSource, String dataSourceLabel) {
        this.gateway = gateway;
        this.jdbc = new NamedParameterJdbcTemplate(platformDataSource);
        this.dataSourceLabel = dataSourceLabel;
    }

    @Override
    @Transactional
    public int recordDormantExceptions(
            UUID tenantId, String controlCode, DormantAccountCriteria criteria, BigDecimal largeOutflowAmount) {
        int created = 0;
        String params = CcmExceptionJson.dormantParameters(criteria, largeOutflowAmount);
        for (MovementAnomaly a : gateway.dormantAccountMovements(criteria)) {
            boolean largeOutflow = "D".equals(a.direction())
                    && largeOutflowAmount != null && a.amount().compareTo(largeOutflowAmount) >= 0;
            String critCode = largeOutflow ? "CRITICAL" : "HIGH";
            String critLabel = largeOutflow ? "Critique" : "Élevée";
            String title = "Mouvement sur compte dormant : " + a.amount() + " MAD sur le compte " + a.accountId();
            String fact = "Mouvement n°" + a.movementId() + " (" + a.direction() + ", " + a.amount()
                    + " MAD, canal " + a.channel() + ") le " + a.operationDate()
                    + " sur le compte dormant " + a.accountId()
                    + ", validé par " + a.validatedBy() + ".";
            String criterion = "Tout mouvement sur un compte dormant doit être justifié (LCB-FT, pack CCM-3).";
            created += insert(tenantId, controlCode, "CCM-DORM-" + a.movementId(), title, critCode, critLabel,
                    fact, criterion, CcmExceptionJson.movement(a), params);
        }
        return created;
    }

    @Override
    @Transactional
    public int recordOutOfBoundsExceptions(UUID tenantId, String controlCode, OutOfBoundsCriteria criteria) {
        int created = 0;
        String params = CcmExceptionJson.outOfBoundsParameters(criteria);
        for (MovementAnomaly a : gateway.outOfBoundsMovements(criteria)) {
            boolean amount = "AMOUNT".equals(a.triggerReason());
            String critCode = amount ? "HIGH" : "MEDIUM";
            String critLabel = amount ? "Élevée" : "Moyenne";
            String title = "Écriture hors cadre (" + a.triggerReason() + ") : " + a.amount() + " MAD";
            String fact = "Mouvement n°" + a.movementId() + " (" + a.amount() + " MAD, canal " + a.channel()
                    + ") le " + a.operationDate() + " sur le compte " + a.accountId()
                    + " — motif : " + a.triggerReason() + ".";
            String criterion = "Opération hors seuil de délégation ou hors heures ouvrées (pack CCM-2).";
            created += insert(tenantId, controlCode, "CCM-OOB-" + a.movementId(), title, critCode, critLabel,
                    fact, criterion, CcmExceptionJson.movement(a), params);
        }
        return created;
    }

    private int insert(UUID tenantId, String controlCode, String reference, String title, String critCode,
            String critLabel, String fact, String criterion, String sourceRecord, String params) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("controlCode", controlCode)
                .addValue("reference", reference)
                .addValue("title", title)
                .addValue("critCode", critCode)
                .addValue("critLabel", critLabel)
                .addValue("fact", fact)
                .addValue("criterion", criterion)
                .addValue("sourceRecord", sourceRecord)
                .addValue("params", params)
                .addValue("dataSource", dataSourceLabel);
        return jdbc.update(INSERT_SQL, p);
    }
}
