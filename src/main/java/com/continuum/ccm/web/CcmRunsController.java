package com.continuum.ccm.web;

import com.continuum.ccm.api.CcmControlCatalog;
import com.continuum.ccm.api.CcmRunDirectory;
import com.continuum.ccm.api.CcmRunDirectory.RunView;
import com.continuum.iam.api.AuthorizationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gen.app.ccm.api.CcmRunsApi;
import gen.app.ccm.model.CcmRun;
import gen.app.ccm.model.CcmRunPage;
import gen.app.ccm.model.Problem;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Historique des exécutions CCM exposé sur l'API (contrat {@code app/ccm.yaml} {@code listCcmControlRuns}/
 * {@code getCcmRun}, §5). Implémente l'interface <strong>générée</strong> (D32) : lit {@link CcmRunDirectory}
 * (table {@code ccm_run}) et mappe vers le contrat. Lecture seule ; droit {@code ccm.read} (tenant). Le
 * {@code controlId} du contrat est l'identifiant de catalogue ; en interne les exécutions sont indexées par
 * {@code control_code} ({@link CcmControlCatalog}).
 */
@RestController
@RequestMapping("/api/app")
public class CcmRunsController implements CcmRunsApi {

    private static final String CLAIM_TENANT = "tenant_id";
    private static final String CLAIM_USER = "continuum_user_id";
    private static final String FEATURE_READ = "ccm.read";
    private static final TypeReference<Map<String, Object>> PARAMS = new TypeReference<>() {
    };

    private final CcmRunDirectory runs;
    private final AuthorizationService authorization;
    private final ObjectMapper mapper;

    public CcmRunsController(CcmRunDirectory runs, AuthorizationService authorization, ObjectMapper mapper) {
        this.runs = runs;
        this.authorization = authorization;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<CcmRunPage> listCcmControlRuns(UUID controlId, Integer page, Integer size) {
        UUID tenant = authorizedTenant();
        String code = CcmControlCatalog.byId(controlId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contrôle inconnu"))
                .code();
        int p = page == null ? 0 : page;
        int s = size == null ? 25 : size;
        CcmRunDirectory.RunPage result = runs.listByControl(tenant, code, p, s);
        List<CcmRun> items = result.items().stream().map(this::toModel).toList();
        int totalPages = s == 0 ? 0 : (int) Math.ceil((double) result.totalItems() / s);
        return ResponseEntity.ok(new CcmRunPage()
                .page(p).size(s).totalItems(result.totalItems()).totalPages(totalPages).items(items));
    }

    @Override
    public ResponseEntity<CcmRun> getCcmRun(UUID runId) {
        UUID tenant = authorizedTenant();
        return runs.findById(tenant, runId)
                .map(this::toModel)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exécution inconnue"));
    }

    private CcmRun toModel(RunView v) {
        CcmRun model = new CcmRun(v.id(), CcmControlCatalog.idOf(v.controlCode()),
                CcmRun.StatusEnum.fromValue(v.status().name()), v.startedAt())
                .trigger(v.trigger() == null ? null : CcmRun.TriggerEnum.fromValue(v.trigger().name()))
                .finishedAt(v.finishedAt())
                .populationSize(v.populationSize() == null ? null : v.populationSize().intValue())
                .exceptionsCount(v.exceptionsCount())
                .dataSource(v.dataSource())
                .parametersUsed(parse(v.parametersUsedJson()));
        CcmControlCatalog.byCode(v.controlCode()).ifPresent(d -> model.controlLabel(d.label()));
        if (v.problemDetail() != null) {
            model.problem(new Problem().title("Échec d'exécution").detail(v.problemDetail()));
        }
        return model;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, PARAMS);
        } catch (Exception e) {
            return Map.of();
        }
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
