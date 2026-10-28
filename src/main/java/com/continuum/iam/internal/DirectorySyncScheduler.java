package com.continuum.iam.internal;

import com.continuum.DeploymentSurface;
import com.continuum.iam.api.DirectorySyncService;
import com.continuum.tenancy.api.TenancyService;
import com.continuum.tenancy.api.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic directory synchronisation for every active tenant. Runs on the {@code worker} surface only and only when
 * {@code continuum.directory-sync.enabled=true} and a source is configured; the interval is a technical property.
 */
@Component
@ConditionalOnExpression("${continuum.directory-sync.enabled:false} and (" + DeploymentSurface.ON_WORKER + ")")
class DirectorySyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(DirectorySyncScheduler.class);

    private final DirectorySyncService sync;
    private final TenancyService tenancy;

    DirectorySyncScheduler(DirectorySyncService sync, TenancyService tenancy) {
        this.sync = sync;
        this.tenancy = tenancy;
    }

    @Scheduled(initialDelayString = "${continuum.directory-sync.initial-delay:PT1M}",
            fixedDelayString = "${continuum.directory-sync.interval:PT1H}")
    void run() {
        if (!sync.configured()) {
            return;
        }
        for (Tenant tenant : tenancy.listActive()) {
            var state = sync.runNow(tenant.id());
            log.info("Directory sync for tenant {}: {}", tenant.code(), state.state());
        }
    }
}
