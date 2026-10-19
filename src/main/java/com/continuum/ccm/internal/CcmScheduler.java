package com.continuum.ccm.internal;

import com.continuum.ccm.api.CcmControlDirectory;
import com.continuum.ccm.api.CcmControlDirectory.CcmControl;
import com.continuum.ccm.api.CcmRunDirectory;
import com.continuum.ccm.api.CcmRunDirectory.RunRecord;
import com.continuum.ccm.api.CcmRunDirectory.Trigger;
import com.continuum.ccm.api.PopulationControlMonitoring;
import com.continuum.ccm.api.SodMonitoring;
import com.continuum.DeploymentSurface;
import com.continuum.tenancy.api.Tenant;
import com.continuum.tenancy.api.TenancyService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Exécution <strong>continue</strong> des contrôles CCM (plan §5) : périodiquement, pour chaque tenant
 * actif, les contrôles tournent et matérialisent leurs détections en <strong>exceptions</strong>
 * (idempotent, D34 — jamais directement en constats). C'est ce qui rend le monitoring « continu »
 * plutôt qu'un simple audit ponctuel.
 *
 * <p>Deux familles : le cumul SoD interne ({@link SodMonitoring}, toujours actif) et les contrôles sur
 * population CBS ({@link PopulationControlMonitoring}, actifs seulement si un CBS est configuré —
 * {@code continuum.cbs.url}). Les paramètres de chaque contrôle viennent de {@link CcmControlDirectory}
 * (CCM-03), jamais du code.
 *
 * <p>Actif uniquement sur la <strong>surface {@code worker}</strong> (ou en tout-en-un {@code all}) —
 * {@link DeploymentSurface} : ainsi, quand l'API est répliquée (surfaces {@code app}/{@code admin}),
 * un seul type d'instance exécute les travaux de fond, sans doublon d'exceptions ni de {@code ccm_run}.
 */
@Component
@ConditionalOnExpression(DeploymentSurface.ON_WORKER)
class CcmScheduler {

    private static final Logger log = LoggerFactory.getLogger(CcmScheduler.class);

    private final TenancyService tenancy;
    private final SodMonitoring sodMonitoring;
    private final Optional<PopulationControlMonitoring> populationMonitoring;
    private final CcmControlDirectory controlDirectory;
    private final CcmRunDirectory runDirectory;
    private final int lookbackDays;

    CcmScheduler(TenancyService tenancy, SodMonitoring sodMonitoring,
            Optional<PopulationControlMonitoring> populationMonitoring,
            CcmControlDirectory controlDirectory, CcmRunDirectory runDirectory,
            @Value("${continuum.ccm.lookback-days:90}") int lookbackDays) {
        this.tenancy = tenancy;
        this.sodMonitoring = sodMonitoring;
        this.populationMonitoring = populationMonitoring;
        this.controlDirectory = controlDirectory;
        this.runDirectory = runDirectory;
        this.lookbackDays = lookbackDays;
    }

    /** 15 s après le démarrage puis toutes les 5 min (paramètres techniques — §env, ajustables). */
    @Scheduled(initialDelay = 15_000, fixedDelay = 300_000)
    void runSodControls() {
        for (Tenant tenant : tenancy.listActive()) {
            OffsetDateTime startedAt = OffsetDateTime.now();
            try {
                int created = sodMonitoring.detectCumuls(tenant.id());
                recordRun(tenant, CcmCriteriaMapper.CONTROL_SOD, startedAt, null, created,
                        "Base plateforme (habilitations)", null);
                if (created > 0) {
                    log.info("CCM SoD : {} nouvelle(s) exception(s) pour le tenant {}", created, tenant.code());
                }
            } catch (RuntimeException e) {
                recordRun(tenant, CcmCriteriaMapper.CONTROL_SOD, startedAt, null, 0,
                        "Base plateforme (habilitations)", e.getMessage());
                log.warn("CCM SoD en échec pour le tenant {} : {}", tenant.code(), e.getMessage());
            }
        }
    }

    /** Enregistre une exécution de contrôle dans l'historique ({@code ccm_run}) ; {@code problem} ⇒ échec. */
    private void recordRun(Tenant tenant, String controlCode, OffsetDateTime startedAt,
            Long populationSize, int exceptionsCount, String dataSource, String problem) {
        runDirectory.record(new RunRecord(tenant.id(), controlCode, Trigger.SCHEDULE, startedAt,
                OffsetDateTime.now(), populationSize, exceptionsCount, dataSource, null, problem));
    }

    /**
     * Contrôles sur population CBS (CCM-2, CCM-3) : seulement si un connecteur CBS est configuré. Les
     * paramètres (seuils, listes) viennent du paramétrage du tenant (CCM-03) ; la fenêtre d'analyse est
     * {@code continuum.ccm.lookback-days} jusqu'à aujourd'hui.
     */
    @Scheduled(initialDelay = 20_000, fixedDelay = 300_000)
    void runPopulationControls() {
        if (populationMonitoring.isEmpty()) {
            return;
        }
        PopulationControlMonitoring monitoring = populationMonitoring.get();
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(lookbackDays);
        for (Tenant tenant : tenancy.listActive()) {
            for (CcmControl control : controlDirectory.activeControls(tenant.id())) {
                if (!CcmCriteriaMapper.CONTROL_DORMANT.equals(control.code())
                        && !CcmCriteriaMapper.CONTROL_OUT_OF_BOUNDS.equals(control.code())) {
                    continue;
                }
                OffsetDateTime startedAt = OffsetDateTime.now();
                try {
                    int created = switch (control.code()) {
                        case CcmCriteriaMapper.CONTROL_DORMANT -> monitoring.recordDormantExceptions(
                                tenant.id(), control.code(), CcmCriteriaMapper.dormant(control, from, to),
                                CcmCriteriaMapper.largeOutflow(control));
                        case CcmCriteriaMapper.CONTROL_OUT_OF_BOUNDS -> monitoring.recordOutOfBoundsExceptions(
                                tenant.id(), control.code(), CcmCriteriaMapper.outOfBounds(control, from, to));
                        default -> 0;
                    };
                    recordRun(tenant, control.code(), startedAt, null, created, null, null);
                    if (created > 0) {
                        log.info("CCM {} : {} nouvelle(s) exception(s) pour le tenant {}",
                                control.code(), created, tenant.code());
                    }
                } catch (RuntimeException e) {
                    recordRun(tenant, control.code(), startedAt, null, 0, null, e.getMessage());
                    log.warn("CCM {} en échec pour le tenant {} : {}", control.code(), tenant.code(),
                            e.getMessage());
                }
            }
        }
    }
}
