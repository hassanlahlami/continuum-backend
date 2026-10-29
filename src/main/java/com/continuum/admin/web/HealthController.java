package com.continuum.admin.web;

import gen.admin.ops.api.HealthApi;
import gen.admin.ops.model.ComponentHealth;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform health (contract {@code admin/operations.yaml}, {@code GET /health}). Only components that this
 * instance can actually probe are reported: the database (portable {@link Connection#isValid} — no
 * vendor SQL, so PostgreSQL and Oracle behave alike). Broker, Keycloak, workflow engine, OpenSearch and
 * providers have no probe yet and are left out rather than invented. The other operations of the
 * {@code health} tag (scheduled jobs) keep the generated 501 default.
 */
@RestController
@RequestMapping("/api/admin")
public class HealthController implements HealthApi {

    private final DataSource dataSource;
    private final int probeTimeoutSeconds;

    public HealthController(DataSource dataSource,
                            @Value("${continuum.health.probe-timeout-seconds:2}") int probeTimeoutSeconds) {
        this.dataSource = dataSource;
        this.probeTimeoutSeconds = probeTimeoutSeconds;
    }

    @Override
    @PreAuthorize("@authz.can('ops.health.read')")
    public ResponseEntity<List<ComponentHealth>> getPlatformHealth() {
        return ResponseEntity.ok(List.of(probeDatabase()));
    }

    private ComponentHealth probeDatabase() {
        ComponentHealth.StatusEnum status;
        String detail = null;
        try (Connection c = dataSource.getConnection()) {
            if (c.isValid(probeTimeoutSeconds)) {
                status = ComponentHealth.StatusEnum.UP;
            } else {
                status = ComponentHealth.StatusEnum.DOWN;
                detail = "connection validation failed";
            }
        } catch (Exception e) {
            status = ComponentHealth.StatusEnum.DOWN;
            detail = e.getClass().getSimpleName();
        }
        return new ComponentHealth().component("database").status(status).detail(detail)
                .checkedAt(OffsetDateTime.now(ZoneOffset.UTC));
    }
}
