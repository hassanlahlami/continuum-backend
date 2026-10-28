package com.continuum.iam.web;

import com.continuum.iam.api.AdminDirectory;
import gen.admin.iam.api.OverviewApi;
import gen.admin.iam.model.AdminOverview;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * System Admin home (contract {@code admin/tenancy-iam.yaml}). Only {@code usersWithoutAssignment} has a source
 * today; the other KPIs (change requests, publications, alerts, static SoD conflicts, directory sync, licence,
 * connectors) have no backing table yet and are left absent rather than invented.
 */
@RestController
@RequestMapping("/api/admin")
public class OverviewController implements OverviewApi {

    private final AdminDirectory directory;

    public OverviewController(AdminDirectory directory) {
        this.directory = directory;
    }

    @Override
    @PreAuthorize("@authz.can('admin.overview.read')")
    public ResponseEntity<AdminOverview> getAdminOverview() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Aucun jeton JWT dans le contexte de sécurité");
        }
        UUID tenant = UUID.fromString(jwt.getClaimAsString("tenant_id"));
        // Reaching this point means the database answered, hence OK; no probe exists for DEGRADED/DOWN yet.
        return ResponseEntity.ok(new AdminOverview()
                .health(AdminOverview.HealthEnum.OK)
                .usersWithoutAssignment((int) directory.countUsersWithoutAssignment(tenant)));
    }
}
