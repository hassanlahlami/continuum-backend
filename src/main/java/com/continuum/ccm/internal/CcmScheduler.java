package com.continuum.ccm.internal;

import com.continuum.ccm.api.CcmControlDirectory;
import com.continuum.ccm.api.CcmControlDirectory.CcmControl;
import com.continuum.ccm.api.PopulationControlMonitoring;
import com.continuum.ccm.api.SodMonitoring;
import com.continuum.tenancy.api.Tenant;
import com.continuum.tenancy.api.TenancyService;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Exécution <strong>continue</strong> des contrôles CCM (plan §5) : périodiquement, pour chaque tenant
 * actif, les contrôles tournent et matérialisent leurs détections en constats (idempotent). C'est ce
 * qui rend le monitoring « continu » plutôt qu'un simple audit ponctuel.
 *
 * <p>Deux familles : le cumul SoD interne ({@link SodMonitoring}, toujours actif) et les contrôles sur
 * population CBS ({@link PopulationControlMonitoring}, actifs seulement si un CBS est configuré —
 * {@code continuum.cbs.url}). Les paramètres de chaque contrôle viennent de {@link CcmControlDirectory}
 * (CCM-03), jamais du code.
 */
@Component
class CcmScheduler {

    private static final Logger log = LoggerFactory.getLogger(CcmScheduler.class);

    private final TenancyService tenancy;
    private final SodMonitoring sodMonitoring;
    private final Optional<PopulationControlMonitoring> populationMonitoring;
    private final CcmControlDirectory controlDirectory;
    private final int lookbackDays;

    CcmScheduler(TenancyService tenancy, SodMonitoring sodMonitoring,
            Optional<PopulationControlMonitoring> populationMonitoring,
            CcmControlDirectory controlDirectory,
            @Value("${continuum.ccm.lookback-days:90}") int lookbackDays) {
        this.tenancy = tenancy;
        this.sodMonitoring = sodMonitoring;
        this.populationMonitoring = populationMonitoring;
        this.controlDirectory = controlDirectory;
        this.lookbackDays = lookbackDays;
    }

    /** 15 s après le démarrage puis toutes les 5 min (paramètres techniques — §env, ajustables). */
    @Scheduled(initialDelay = 15_000, fixedDelay = 300_000)
    void runSodControls() {
        for (Tenant tenant : tenancy.listActive()) {
            int created = sodMonitoring.detectCumuls(tenant.id());
            if (created > 0) {
                log.info("CCM SoD : {} nouveau(x) constat(s) pour le tenant {}", created, tenant.code());
            }
        }
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
                int created = switch (control.code()) {
                    case CcmCriteriaMapper.CONTROL_DORMANT -> monitoring.recordDormantExceptions(
                            tenant.id(), CcmCriteriaMapper.dormant(control, from, to),
                            CcmCriteriaMapper.largeOutflow(control));
                    case CcmCriteriaMapper.CONTROL_OUT_OF_BOUNDS -> monitoring.recordOutOfBoundsExceptions(
                            tenant.id(), CcmCriteriaMapper.outOfBounds(control, from, to));
                    default -> 0;
                };
                if (created > 0) {
                    log.info("CCM {} : {} nouveau(x) constat(s) pour le tenant {}",
                            control.code(), created, tenant.code());
                }
            }
        }
    }
}
