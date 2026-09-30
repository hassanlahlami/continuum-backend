package com.continuum.iam.web;

import com.continuum.iam.api.AssignmentDirectory;
import com.continuum.iam.api.AssignmentView;
import com.continuum.iam.api.AuthorizationService;
import com.continuum.iam.api.ScopedFeature;
import com.continuum.tenancy.api.Tenant;
import com.continuum.tenancy.api.TenancyService;
import gen.app.me.api.MeApi;
import gen.app.me.model.AssignmentSummary;
import gen.app.me.model.EntitySummary;
import gen.app.me.model.Me;
import gen.app.me.model.Permissions;
import gen.app.me.model.PermissionsFeaturesInner;
import gen.app.me.model.PermissionsFeaturesInnerScopesInner;
import gen.app.me.model.PermissionsFeaturesInnerScopesInner.TypeEnum;
import gen.app.me.model.UnitSummary;
import gen.app.me.model.UserSummary;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Coquille utilisateur courant (contrat {@code app/me.yaml}, §2.1.7) : {@code GET /me/permissions}
 * (droits effectifs) et {@code GET /me} (identité, entité, affectations). Implémente l'interface
 * <strong>générée</strong> depuis le contrat (D32) ; le serveur décide, le front affiche.
 */
@RestController
@RequestMapping("/api/app")
public class MeController implements MeApi {

    private static final String CLAIM_TENANT = "tenant_id";
    private static final String CLAIM_USER = "continuum_user_id";
    private static final String CLAIM_AUTHZ_VERSION = "authz_version";
    private static final Set<String> LOCALES = Set.of("fr", "ar", "en");

    private final AuthorizationService authorization;
    private final TenancyService tenancy;
    private final AssignmentDirectory assignments;

    public MeController(AuthorizationService authorization, TenancyService tenancy,
            AssignmentDirectory assignments) {
        this.authorization = authorization;
        this.tenancy = tenancy;
        this.assignments = assignments;
    }

    @Override
    public ResponseEntity<Permissions> getMyPermissions() {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID user = uuidClaim(jwt, CLAIM_USER);

        Map<String, List<ScopedFeature>> byCode = authorization.permissions(tenant, user).stream()
                .collect(Collectors.groupingBy(ScopedFeature::featureCode));

        List<PermissionsFeaturesInner> features = byCode.entrySet().stream()
                .map(e -> new PermissionsFeaturesInner(e.getKey(), e.getValue().stream()
                        .map(sf -> new PermissionsFeaturesInnerScopesInner(TypeEnum.fromValue(sf.scopeType()))
                                .id(sf.scopeId()))
                        .toList()))
                .toList();

        return ResponseEntity.ok(new Permissions(authzVersion(jwt), features));
    }

    @Override
    public ResponseEntity<Me> getCurrentUser() {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID user = uuidClaim(jwt, CLAIM_USER);
        String locale = locale(jwt);

        Tenant t = tenancy.find(tenant)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entité inconnue"));

        UserSummary userSummary = new UserSummary(user, displayName(jwt));
        List<AssignmentSummary> assignmentSummaries = assignments.activeAssignments(tenant, user, locale).stream()
                .map(MeController::toSummary)
                .toList();

        Me me = new Me(userSummary, toEntity(t), assignmentSummaries)
                .email(jwt.getClaimAsString("email"))
                .availableEntities(tenancy.listActive().stream().map(MeController::toEntity).toList());
        if (LOCALES.contains(locale)) {
            me.locale(Me.LocaleEnum.fromValue(locale));
        }
        return ResponseEntity.ok(me);
    }

    private static AssignmentSummary toSummary(AssignmentView v) {
        AssignmentSummary a = new AssignmentSummary(v.positionLabel(), AssignmentSummary.KindEnum.fromValue(v.kind()));
        if (v.unitId() != null) {
            a.unit(new UnitSummary(v.unitId(), v.unitCode()).code(v.unitCode()));
        }
        if (v.validTo() != null) {
            a.validTo(v.validTo());
        }
        return a;
    }

    private static EntitySummary toEntity(Tenant t) {
        return new EntitySummary(t.id(), t.code(), t.label());
    }

    private static String displayName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (name != null && !name.isBlank()) {
            return name;
        }
        String username = jwt.getClaimAsString("preferred_username");
        return username != null ? username : jwt.getSubject();
    }

    private static String locale(Jwt jwt) {
        String locale = jwt.getClaimAsString("locale");
        return locale == null || locale.isBlank() ? "fr" : locale;
    }

    private static int authzVersion(Jwt jwt) {
        Object claim = jwt.getClaim(CLAIM_AUTHZ_VERSION);
        return claim instanceof Number n ? n.intValue() : 0;
    }

    private static UUID uuidClaim(Jwt jwt, String claim) {
        return UUID.fromString(jwt.getClaimAsString(claim));
    }

    private static Jwt currentJwt() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Aucun jeton JWT dans le contexte de sécurité");
        }
        return jwt;
    }
}
