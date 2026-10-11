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
 * Matérialisation PostgreSQL des anomalies CBS en constats (source {@code CCM}), §5. Lit via
 * {@link CcmPopulationGateway} (CBS Oracle) et écrit dans la table {@code finding} de la base plateforme
 * — deux sources distinctes. L'unicité {@code finding(tenant_id, reference)} rend le contrôle idempotent.
 */
public class JdbcPopulationControlMonitoring implements PopulationControlMonitoring {

    private static final String INSERT_SQL = """
            INSERT INTO finding (id, tenant_id, mission_id, org_unit_id, reference, title,
                                 status_code, status_label, criticality_code, criticality_label, source,
                                 fact, criterion)
            VALUES (gen_random_uuid(), :tenant, NULL, NULL, :reference, :title,
                    'OUVERT', 'Ouvert', :critCode, :critLabel, 'CCM', :fact, :criterion)
            ON CONFLICT (tenant_id, reference) DO NOTHING
            """;

    private final CcmPopulationGateway gateway;
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPopulationControlMonitoring(CcmPopulationGateway gateway, DataSource platformDataSource) {
        this.gateway = gateway;
        this.jdbc = new NamedParameterJdbcTemplate(platformDataSource);
    }

    @Override
    @Transactional
    public int recordDormantExceptions(UUID tenantId, DormantAccountCriteria criteria, BigDecimal largeOutflowAmount) {
        int created = 0;
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
            created += insert(tenantId, "CCM-DORM-" + a.movementId(), title, critCode, critLabel, fact, criterion);
        }
        return created;
    }

    @Override
    @Transactional
    public int recordOutOfBoundsExceptions(UUID tenantId, OutOfBoundsCriteria criteria) {
        int created = 0;
        for (MovementAnomaly a : gateway.outOfBoundsMovements(criteria)) {
            boolean amount = "AMOUNT".equals(a.triggerReason());
            String critCode = amount ? "HIGH" : "MEDIUM";
            String critLabel = amount ? "Élevée" : "Moyenne";
            String title = "Écriture hors cadre (" + a.triggerReason() + ") : " + a.amount() + " MAD";
            String fact = "Mouvement n°" + a.movementId() + " (" + a.amount() + " MAD, canal " + a.channel()
                    + ") le " + a.operationDate() + " sur le compte " + a.accountId()
                    + " — motif : " + a.triggerReason() + ".";
            String criterion = "Opération hors seuil de délégation ou hors heures ouvrées (pack CCM-2).";
            created += insert(tenantId, "CCM-OOB-" + a.movementId(), title, critCode, critLabel, fact, criterion);
        }
        return created;
    }

    private int insert(UUID tenantId, String reference, String title, String critCode, String critLabel,
            String fact, String criterion) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("reference", reference)
                .addValue("title", title)
                .addValue("critCode", critCode)
                .addValue("critLabel", critLabel)
                .addValue("fact", fact)
                .addValue("criterion", criterion);
        return jdbc.update(INSERT_SQL, params);
    }
}
