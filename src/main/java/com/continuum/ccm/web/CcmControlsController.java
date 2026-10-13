package com.continuum.ccm.web;

import com.continuum.ccm.api.CcmControlCatalog;
import com.continuum.ccm.api.CcmControlCatalog.ControlDescriptor;
import com.continuum.ccm.api.CcmControlCatalog.ParamDef;
import com.continuum.ccm.api.CcmControlDirectory;
import com.continuum.iam.api.AuthorizationService;
import gen.app.ccm.api.CcmControlsApi;
import gen.app.ccm.model.CcmControl;
import gen.app.ccm.model.CcmControlParameterDefinitionsInner;
import gen.app.ccm.model.CcmControlSettings;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    private final CcmControlDirectory controls;
    private final AuthorizationService authorization;

    public CcmControlsController(CcmControlDirectory controls, AuthorizationService authorization) {
        this.controls = controls;
        this.authorization = authorization;
    }

    @Override
    public ResponseEntity<List<CcmControl>> listCcmControls(String q, String category, Boolean active) {
        Context ctx = authorized();
        String needle = q == null ? null : q.strip().toLowerCase(Locale.ROOT);
        List<CcmControl> items = CcmControlCatalog.all().stream()
                .map(d -> toModel(d, ctx.config.get(d.code())))
                .filter(c -> category == null || category.equals(c.getCategory().getValue()))
                .filter(c -> active == null || active.equals(c.getActive()))
                .filter(c -> needle == null || c.getCode().toLowerCase(Locale.ROOT).contains(needle)
                        || c.getLabel().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        return ResponseEntity.ok(items);
    }

    @Override
    public ResponseEntity<CcmControl> getCcmControl(UUID controlId) {
        Context ctx = authorized();
        ControlDescriptor descriptor = CcmControlCatalog.byId(controlId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contrôle inconnu"));
        return ResponseEntity.ok(toModel(descriptor, ctx.config.get(descriptor.code())));
    }

    private CcmControl toModel(ControlDescriptor d, CcmControlDirectory.CcmControl config) {
        boolean enabled = config != null && config.enabled();
        CcmControl model = new CcmControl(d.id(), d.code(), d.label(),
                CcmControl.CategoryEnum.fromValue(d.category()), enabled, CcmControl.HealthEnum.NEVER_RUN)
                .description(d.description())
                .normativeReference(d.normativeReference())
                .dataset(d.dataset())
                .parameterDefinitions(d.parameterDefinitions().stream().map(CcmControlsController::toParamDef).toList());
        if (config != null) {
            model.settings(new CcmControlSettings(1).parameters(config.params()));
        }
        return model;
    }

    private static CcmControlParameterDefinitionsInner toParamDef(ParamDef p) {
        return new CcmControlParameterDefinitionsInner(p.name(),
                CcmControlParameterDefinitionsInner.TypeEnum.fromValue(p.type()))
                .label(p.label())
                ._default(p.defaultValue());
    }

    /** Charge le paramétrage du tenant et vérifie le droit {@code ccm.read} (niveau tenant). */
    private Context authorized() {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID user = uuidClaim(jwt, CLAIM_USER);
        boolean canRead = authorization.permissions(tenant, user).stream()
                .anyMatch(sf -> FEATURE_READ.equals(sf.featureCode()));
        if (!canRead) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Fonctionnalité ccm.read absente.");
        }
        Map<String, CcmControlDirectory.CcmControl> config = controls.allControls(tenant).stream()
                .collect(Collectors.toMap(CcmControlDirectory.CcmControl::code, Function.identity()));
        return new Context(config);
    }

    private record Context(Map<String, CcmControlDirectory.CcmControl> config) {
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
