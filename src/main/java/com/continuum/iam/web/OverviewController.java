package com.continuum.iam.web;

import com.continuum.iam.api.AdminDirectory;
import com.continuum.iam.api.DirectorySyncService;
import com.continuum.iam.api.SodConflictDirectory;
import gen.admin.iam.api.OverviewApi;
import gen.admin.iam.model.AdminOverview;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * System Admin home (contract {@code admin/tenancy-iam.yaml}). {@code usersWithoutAssignment} and
 * {@code staticSodConflicts} have a source today. {@code lastDirectorySyncAt} comes from the directory sync state
 * (absent until a synchronisation succeeded). The other KPIs (change requests,
 * publications, alerts, licence, connectors) have no backing table and are left absent rather than invented.
 */
@RestController
@RequestMapping("/api/admin")
public class OverviewController implements OverviewApi {

    private final AdminDirectory directory;
    private final SodConflictDirectory sodConflicts;
    private final DirectorySyncService sync;

    public OverviewController(AdminDirectory directory, SodConflictDirectory sodConflicts,
                              DirectorySyncService sync) {
        this.sync = sync;
        this.directory = directory;
        this.sodConflicts = sodConflicts;
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
        AdminOverview out = new AdminOverview()
                .health(AdminOverview.HealthEnum.OK)
                .usersWithoutAssignment((int) directory.countUsersWithoutAssignment(tenant))
                .staticSodConflicts(sodConflicts.listStaticConflicts(tenant).size());
        sync.status(tenant).map(DirectorySyncService.SyncState::lastSyncAt).ifPresent(
                at -> out.lastDirectorySyncAt(at.atOffset(ZoneOffset.UTC)));
        return ResponseEntity.ok(out);
    }
}
