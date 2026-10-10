package com.continuum.ccm.internal;

import com.continuum.ccm.api.SodMonitoring;
import com.continuum.tenancy.api.Tenant;
import com.continuum.tenancy.api.TenancyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Exécution <strong>continue</strong> des contrôles CCM (plan §5) : périodiquement, pour chaque tenant
 * actif, le contrôle de cumul SoD tourne et matérialise les cumuls en constats (idempotent). C'est ce
 * qui rend le monitoring « continu » plutôt qu'un simple audit ponctuel.
 */
@Component
class CcmScheduler {

    private static final Logger log = LoggerFactory.getLogger(CcmScheduler.class);

    private final TenancyService tenancy;
    private final SodMonitoring sodMonitoring;

    CcmScheduler(TenancyService tenancy, SodMonitoring sodMonitoring) {
        this.tenancy = tenancy;
        this.sodMonitoring = sodMonitoring;
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
}
