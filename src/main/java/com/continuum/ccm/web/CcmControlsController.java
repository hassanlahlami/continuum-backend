package com.continuum.ccm.web;

import com.continuum.iam.api.AuthorizationService;
import gen.app.ccm.api.CcmControlsApi;
import gen.app.ccm.model.CcmControl;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bibliothèque des contrôles CCM (cockpit, contrat {@code app/ccm.yaml} {@code listCcmControls}/
 * {@code getCcmControl}, §5). Implémente l'interface <strong>générée</strong> (D32) : fusionne le
 * <strong>catalogue du pack</strong> ({@link CcmControlCatalog}) avec le paramétrage du tenant
 * ({@link CcmControlDirectory}, CCM-03) — activation et valeurs de paramètres. Droit {@code ccm.read}
 * (tenant). La santé/les métriques resteront {@code NEVER_RUN} tant que les exécutions ({@code ccm_run})
 * ne sont pas historisées ; {@code updateCcmControlSettings}/{@code performCcmControlAction} (maker-checker,
 * exécution) restent à câbler (défaut 501).
 */
@RestController
@RequestMapping("/api/app")
public class CcmControlsController implements CcmControlsApi {

    private static final String CLAIM_TENANT = "tenant_id";
    private static final String CLAIM_USER = "continuum_user_id";
    private static final String FEATURE_READ = "ccm.read";

    private final CcmControlAssembler assembler;
    private final AuthorizationService authorization;

    public CcmControlsController(CcmControlAssembler assembler, AuthorizationService authorization) {
        this.assembler = assembler;
        this.authorization = authorization;
    }

    @Override
    public ResponseEntity<List<CcmControl>> listCcmControls(String q, String category, Boolean active) {
        UUID tenant = authorizedTenant();
        return ResponseEntity.ok(assembler.list(tenant, q, category, active));
    }

    @Override
    public ResponseEntity<CcmControl> getCcmControl(UUID controlId) {
        UUID tenant = authorizedTenant();
        return assembler.byId(tenant, controlId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contrôle inconnu"));
    }

    /** Vérifie le droit {@code ccm.read} (niveau tenant) et renvoie le tenant courant. */
    private UUID authorizedTenant() {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID user = uuidClaim(jwt, CLAIM_USER);
        boolean canRead = authorization.permissions(tenant, user).stream()
                .anyMatch(sf -> FEATURE_READ.equals(sf.featureCode()));
        if (!canRead) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Fonctionnalité ccm.read absente.");
        }
        return tenant;
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
