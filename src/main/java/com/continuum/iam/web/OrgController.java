package com.continuum.iam.web;

import com.continuum.iam.api.AdminDirectory;
import com.continuum.iam.api.AdminDirectory.OrgUnitRow;
import gen.admin.iam.api.OrgApi;
import gen.admin.iam.model.OrgUnitNode;
import java.util.List;
import java.util.UUID;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Organisation tree for the System Admin (contract {@code admin/tenancy-iam.yaml}); read side. */
@RestController
@RequestMapping("/api/admin")
public class OrgController implements OrgApi {

    private final AdminDirectory directory;

    public OrgController(AdminDirectory directory) {
        this.directory = directory;
    }

    /** {@code includeClosed} is accepted for contract conformance; units have no closed state yet. */
    @Override
    @PreAuthorize("@authz.can('admin.org.read')")
    public ResponseEntity<List<OrgUnitNode>> getOrgTree(Boolean includeClosed) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Aucun jeton JWT dans le contexte de sécurité");
        }
        UUID tenant = UUID.fromString(jwt.getClaimAsString("tenant_id"));
        String locale = LocaleContextHolder.getLocale().getLanguage();
        return ResponseEntity.ok(directory.orgTree(tenant, locale).stream().map(OrgController::toModel).toList());
    }

    private static OrgUnitNode toModel(OrgUnitRow u) {
        return new OrgUnitNode(u.id(), u.code(), u.label(), u.typeLabel())
                .unitTypeId(u.unitTypeId())
                .closed(false)
                .assignmentsCount(u.assignmentsCount())
                .children(u.children().stream().map(OrgController::toModel).toList());
    }
}
