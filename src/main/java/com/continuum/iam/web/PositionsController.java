package com.continuum.iam.web;

import com.continuum.iam.api.AdminDirectory;
import com.continuum.iam.api.AdminDirectory.PositionRow;
import com.continuum.iam.api.AdminDirectory.RoleRef;
import gen.admin.iam.api.PositionsApi;
import gen.admin.iam.model.LocalizedText;
import gen.admin.iam.model.Position;
import gen.admin.iam.model.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Positions catalogue for the System Admin (contract {@code admin/tenancy-iam.yaml}); read side. */
@RestController
@RequestMapping("/api/admin")
public class PositionsController implements PositionsApi {

    private final AdminDirectory directory;

    public PositionsController(AdminDirectory directory) {
        this.directory = directory;
    }

    @Override
    @PreAuthorize("@authz.can('admin.position.read')")
    public ResponseEntity<List<Position>> listPositions(Boolean includeInactive) {
        UUID tenant = tenant();
        return ResponseEntity.ok(directory.listPositions(tenant, Boolean.TRUE.equals(includeInactive)).stream()
                .map(PositionsController::toModel).toList());
    }

    private static Position toModel(PositionRow p) {
        List<Role> roles = p.roles().stream().map(PositionsController::toModel).toList();
        return new Position(p.code(), text(p.label()), p.rank(), Position.ScopeEnum.fromValue(p.scope()),
                p.id(), p.active())
                .unitTypeId(p.unitTypeId())
                .unitTypeLabel(p.unitTypeLabel())
                .reportsToId(p.reportsToId())
                .reportsToLabel(p.reportsToLabel())
                .maxHoldersPerUnit(p.maxHoldersPerUnit())
                .roleIds(p.roles().stream().map(RoleRef::id).toList())
                .roles(roles)
                .resultingFeatures(p.resultingFeatures())
                .holdersCount(p.holdersCount());
    }

    private static Role toModel(RoleRef r) {
        return new Role(r.code(), text(r.label()), Role.KindEnum.fromValue(r.kind()), r.id())
                .fromPack(r.fromPack())
                .featureCodes(r.featureCodes());
    }

    static LocalizedText text(Map<String, String> label) {
        return new LocalizedText(label.get("fr")).ar(label.get("ar")).en(label.get("en"));
    }

    private static UUID tenant() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Aucun jeton JWT dans le contexte de sécurité");
        }
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}
