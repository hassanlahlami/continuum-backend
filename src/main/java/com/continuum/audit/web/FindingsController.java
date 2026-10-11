package com.continuum.audit.web;

import com.continuum.audit.api.FindingActionService;
import com.continuum.audit.api.FindingActionService.ActionNotPermittedException;
import com.continuum.audit.api.FindingActionService.DynamicSodViolationException;
import com.continuum.audit.api.FindingActionService.FindingNotVisibleException;
import com.continuum.audit.api.FindingActionService.StaleFindingException;
import com.continuum.audit.api.FindingActionService.ValidateFindingCommand;
import com.continuum.audit.api.FindingDirectory;
import com.continuum.audit.api.FindingView;
import gen.app.findings.api.FindingsApi;
import gen.app.findings.model.ActionRequest;
import gen.app.findings.model.AuditMeta;
import gen.app.findings.model.Finding;
import gen.app.findings.model.FindingPage;
import gen.app.findings.model.Ref;
import java.time.LocalDate;
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
 * Vue transverse des constats de la Plateforme Fonctionnelle (contrat {@code app/findings.yaml}, §6) :
 * {@code GET /findings} (toutes missions du périmètre). Implémente l'interface <strong>générée</strong>
 * (D32) ; le périmètre ({@code finding.read}) est appliqué par le read model {@link FindingDirectory}.
 */
@RestController
@RequestMapping("/api/app")
public class FindingsController implements FindingsApi {

    private static final String CLAIM_TENANT = "tenant_id";
    private static final String CLAIM_USER = "continuum_user_id";

    private static final String ACTION_VALIDATE = "validate";

    private final FindingDirectory findings;
    private final FindingActionService findingActions;

    public FindingsController(FindingDirectory findings, FindingActionService findingActions) {
        this.findings = findings;
        this.findingActions = findingActions;
    }

    @Override
    public ResponseEntity<FindingPage> searchFindings(Integer page, Integer size, List<String> sort, String q,
            List<String> status, UUID unitId, LocalDate from, LocalDate to, String criticality,
            String riskFamilyCode, String source) {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID user = uuidClaim(jwt, CLAIM_USER);
        int p = page == null ? 0 : page;
        int s = size == null ? 25 : size;

        FindingDirectory.Page result = findings.searchVisible(tenant, user, q, source, criticality, p, s);
        int totalPages = (int) Math.ceil(result.totalItems() / (double) s);
        List<Finding> items = result.items().stream().map(FindingsController::toModel).toList();
        return ResponseEntity.ok(new FindingPage(p, s, result.totalItems(), totalPages, items));
    }

    @Override
    public ResponseEntity<List<Finding>> listMissionFindings(UUID missionId, List<String> status,
            String criticality) {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID user = uuidClaim(jwt, CLAIM_USER);
        List<Finding> items = findings.listByMission(tenant, user, missionId).stream()
                .map(FindingsController::toModel).toList();
        return ResponseEntity.ok(items);
    }

    @Override
    public ResponseEntity<Finding> getFinding(UUID missionId, UUID findingId) {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID user = uuidClaim(jwt, CLAIM_USER);
        return findings.findVisible(tenant, user, missionId, findingId)
                .map(FindingsController::toModel)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Constat inconnu"));
    }

    /**
     * Exécute une action sur un constat (contrat {@code app/findings.yaml}, §2.1.4). Le serveur décide :
     * la seule action câblée est {@code validate} (droit {@code finding.validate} + SoD dynamique, §2.2) ;
     * une action inconnue est refusée (422). Les refus métier deviennent 403 (droit) ou 409 (SoD, verrou).
     */
    @Override
    public ResponseEntity<Finding> performFindingAction(UUID missionId, UUID findingId, String actionCode,
            ActionRequest actionRequest) {
        if (!ACTION_VALIDATE.equals(actionCode)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Action non supportée : " + actionCode);
        }
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID user = uuidClaim(jwt, CLAIM_USER);
        try {
            FindingView validated = findingActions.validate(new ValidateFindingCommand(tenant, user, missionId,
                    findingId, actionRequest.getVersion(), actionRequest.getComment()));
            return ResponseEntity.ok(toModel(validated));
        } catch (FindingNotVisibleException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (ActionNotPermittedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (DynamicSodViolationException | StaleFindingException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private static Finding toModel(FindingView v) {
        Finding finding = new Finding(v.title(), v.id(), v.reference(),
                new Ref(v.statusCode(), v.statusLabel()),
                new AuditMeta(v.createdAt(), v.version()))
                .missionId(v.missionId())
                .missionTitle(v.missionTitle())
                .fact(v.fact())
                .criterion(v.criterion())
                .cause(v.cause())
                .consequence(v.consequence());
        if (v.criticalityCode() != null) {
            finding.criticality(new Ref(v.criticalityCode(), v.criticalityLabel()));
        }
        if (v.source() != null) {
            finding.source(Finding.SourceEnum.fromValue(v.source()));
        }
        return finding;
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
