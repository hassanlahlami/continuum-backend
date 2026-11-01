package com.continuum.ccm.web;

import com.continuum.ccm.api.CcmExceptionConversionService;
import com.continuum.ccm.api.CcmExceptionConversionService.AlreadyConvertedException;
import com.continuum.ccm.api.CcmExceptionConversionService.ConversionResult;
import com.continuum.ccm.api.CcmExceptionConversionService.ConvertCommand;
import com.continuum.ccm.api.CcmExceptionConversionService.ConvertNotPermittedException;
import com.continuum.ccm.api.CcmExceptionConversionService.ExceptionNotVisibleException;
import com.continuum.ccm.api.CcmExceptionConversionService.MissionNotFoundException;
import com.continuum.ccm.api.CcmExceptionConversionService.NotRetainedException;
import com.continuum.ccm.api.CcmExceptionDirectory;
import com.continuum.ccm.api.CcmExceptionTriageService;
import com.continuum.ccm.api.CcmExceptionTriageService.CcmExceptionNotVisibleException;
import com.continuum.ccm.api.CcmExceptionTriageService.CcmExceptionView;
import com.continuum.ccm.api.CcmExceptionTriageService.IllegalTriageTransitionException;
import com.continuum.ccm.api.CcmExceptionTriageService.JustificationRequiredException;
import com.continuum.ccm.api.CcmExceptionTriageService.StaleCcmExceptionException;
import com.continuum.ccm.api.CcmExceptionTriageService.TriageCommand;
import com.continuum.ccm.api.CcmExceptionTriageService.TriageNotPermittedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gen.app.ccm.api.CcmExceptionsApi;
import gen.app.ccm.model.ActionRequest;
import gen.app.ccm.model.AvailableAction;
import gen.app.ccm.model.CcmException;
import gen.app.ccm.model.CcmExceptionPage;
import gen.app.ccm.model.ConvertCcmExceptionToFinding201Response;
import gen.app.ccm.model.ConvertCcmExceptionToFindingRequest;
import gen.app.ccm.model.Ref;
import gen.app.ccm.model.UnitSummary;
import gen.app.ccm.model.UserSummary;
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
 * Exposition REST du cycle « audit par exception » (contrat {@code app/ccm.yaml}, §5.3, D34). Implémente
 * l'interface <strong>générée</strong> (D32) {@link CcmExceptionsApi} pour les actions décidées par le
 * serveur : <strong>triage</strong> d'une exception ({@code performCcmExceptionAction}) et sa
 * <strong>conversion</strong> en constat candidat ({@code convertCcmExceptionToFinding}). La lecture
 * ({@code getCcmException}/{@code listCcmExceptions}) reste à câbler avec le read model (défaut 501).
 *
 * <p>Le front ne déduit aucun droit : il n'affiche que {@code availableActions} renvoyé par le serveur.
 * Les refus métier deviennent 403 (droit), 409 (transition/verrou/état) ou 422 (justification/argument).
 */
@RestController
@RequestMapping("/api/app")
public class CcmController implements CcmExceptionsApi {

    private static final String CLAIM_TENANT = "tenant_id";
    private static final String CLAIM_USER = "continuum_user_id";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CcmExceptionTriageService triage;
    private final CcmExceptionConversionService conversion;
    private final CcmExceptionDirectory exceptions;

    public CcmController(CcmExceptionTriageService triage, CcmExceptionConversionService conversion,
            CcmExceptionDirectory exceptions) {
        this.triage = triage;
        this.conversion = conversion;
        this.exceptions = exceptions;
    }

    @Override
    public ResponseEntity<CcmException> getCcmException(UUID exceptionId) {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID user = uuidClaim(jwt, CLAIM_USER);
        CcmExceptionDirectory.Row row = exceptions.findVisible(tenant, user, exceptionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exception inconnue"));
        return ResponseEntity.ok(toModel(tenant, user, row));
    }

    @Override
    public ResponseEntity<CcmExceptionPage> listCcmExceptions(Integer page, Integer size, List<String> sort,
            List<String> status, UUID unitId, UUID controlId, UUID runId, String criticality, Boolean assignedToMe) {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID user = uuidClaim(jwt, CLAIM_USER);
        int p = page == null ? 0 : page;
        int s = size == null ? 25 : size;
        CcmExceptionDirectory.Filter filter = new CcmExceptionDirectory.Filter(
                status, criticality, unitId, Boolean.TRUE.equals(assignedToMe));
        CcmExceptionDirectory.Page result = exceptions.search(tenant, user, filter, p, s);
        int totalPages = (int) Math.ceil(result.totalItems() / (double) s);
        List<CcmException> items = result.items().stream().map(r -> toModel(tenant, user, r)).toList();
        return ResponseEntity.ok(new CcmExceptionPage(p, s, result.totalItems(), totalPages, items));
    }

    @Override
    public ResponseEntity<CcmException> performCcmExceptionAction(
            UUID exceptionId, String actionCode, ActionRequest actionRequest) {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID user = uuidClaim(jwt, CLAIM_USER);
        TriageCommand command = new TriageCommand(tenant, user, exceptionId, actionCode,
                actionRequest == null ? null : actionRequest.getVersion(),
                actionRequest == null ? null : actionRequest.getComment(),
                assigneeFrom(actionRequest));
        try {
            CcmExceptionView view = triage.triage(command);
            return ResponseEntity.ok(toModel(view, triage.availableActions(tenant, user, view)));
        } catch (CcmExceptionNotVisibleException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (TriageNotPermittedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (JustificationRequiredException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (IllegalTriageTransitionException | StaleCcmExceptionException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @Override
    public ResponseEntity<ConvertCcmExceptionToFinding201Response> convertCcmExceptionToFinding(
            UUID exceptionId, ConvertCcmExceptionToFindingRequest request) {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID user = uuidClaim(jwt, CLAIM_USER);
        try {
            ConversionResult result = conversion.convert(
                    new ConvertCommand(tenant, user, exceptionId, request.getMissionId()));
            ConvertCcmExceptionToFinding201Response body =
                    new ConvertCcmExceptionToFinding201Response(result.findingId(), result.missionId())
                            .route("/app/missions/" + result.missionId() + "/findings/" + result.findingId());
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        } catch (ExceptionNotVisibleException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (ConvertNotPermittedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (NotRetainedException | AlreadyConvertedException | MissionNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** Traduit les codes d'action de triage décidés par le serveur en actions présentables. */
    private static List<AvailableAction> toActions(List<String> codes) {
        return codes.stream().map(code -> switch (code) {
            case CcmExceptionTriageService.ACTION_RETAIN ->
                new AvailableAction(code, "Retenir").style(AvailableAction.StyleEnum.PRIMARY);
            case CcmExceptionTriageService.ACTION_DISMISS -> new AvailableAction(code, "Écarter");
            case CcmExceptionTriageService.ACTION_ASSIGN -> new AvailableAction(code, "Affecter");
            case CcmExceptionTriageService.ACTION_REOPEN -> new AvailableAction(code, "Rouvrir");
            default -> new AvailableAction(code, code);
        }).toList();
    }

    /**
     * Projection de triage → modèle de contrat (sous-ensemble). Les champs enrichis (unité, affecté,
     * montant, enregistrement source, date de détection) viennent de {@code getCcmException} (read model),
     * pas de la réponse d'action : ce qui change au triage (statut, justification, constat, actions) est ici.
     */
    private static CcmException toModel(CcmExceptionView v, List<String> actions) {
        CcmException model = new CcmException()
                .id(v.id())
                .controlCode(v.controlCode())
                .summary(v.title())
                .status(new Ref(v.statusCode(), v.statusLabel()))
                .dismissalJustification(v.dismissalJustification())
                .findingId(v.findingId())
                .availableActions(toActions(actions));
        if (v.criticalityCode() != null) {
            model.criticality(new Ref(v.criticalityCode(), null));
        }
        return model;
    }

    /** Projection du read model → modèle de contrat complet, avec {@code availableActions} décidées par le serveur. */
    private CcmException toModel(UUID tenant, UUID user, CcmExceptionDirectory.Row r) {
        CcmExceptionView view = new CcmExceptionView(r.id(), tenant, r.orgUnitId(), r.reference(), r.controlCode(),
                r.title(), r.statusCode(), r.statusLabel(), r.criticalityCode(), r.assigneeUserId(),
                r.dismissalJustification(), r.findingId(), 0);
        CcmException model = new CcmException()
                .id(r.id())
                .controlCode(r.controlCode())
                .summary(r.title())
                .status(new Ref(r.statusCode(), r.statusLabel()))
                .detectedAt(r.detectedAt())
                .dismissalJustification(r.dismissalJustification())
                .findingId(r.findingId())
                .runId(r.runId())
                .sourceRecord(parseJson(r.sourceRecordJson()))
                .availableActions(toActions(triage.availableActions(tenant, user, view)));
        if (r.criticalityCode() != null) {
            model.criticality(new Ref(r.criticalityCode(), r.criticalityLabel()));
        }
        if (r.orgUnitId() != null) {
            model.unit(new UnitSummary(r.orgUnitId(), r.unitLabel()).code(r.unitCode()));
        }
        if (r.assigneeUserId() != null) {
            model.assignee(new UserSummary().id(r.assigneeUserId()));
        }
        return model;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static UUID assigneeFrom(ActionRequest actionRequest) {
        if (actionRequest == null || actionRequest.getPayload() == null) {
            return null;
        }
        Map<String, Object> payload = actionRequest.getPayload();
        Object value = payload.get("assigneeUserId");
        return value == null ? null : UUID.fromString(value.toString());
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
