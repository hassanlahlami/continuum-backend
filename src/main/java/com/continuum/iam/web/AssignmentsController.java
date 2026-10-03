package com.continuum.iam.web;

import com.continuum.iam.api.AssignPositionCommand;
import com.continuum.iam.api.AssignmentService;
import com.continuum.iam.api.AssignmentService.SelfGrantNotAllowedException;
import com.continuum.iam.api.AssignmentService.StaticSodViolationException;
import gen.admin.iam.api.AssignmentsApi;
import gen.admin.iam.model.Assignment;
import gen.admin.iam.model.AssignmentInput;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gestion des affectations côté Admin Système (contrat {@code admin/tenancy-iam.yaml}, §2.1.3/§2.1.8).
 * Implémente l'interface <strong>générée</strong> depuis le contrat (D32) et délègue à
 * {@link AssignmentService} (anti-auto-octroi + contrôle SoD statique + journalisation déjà portés
 * par le service). Le <em>maker</em> (auteur) est l'utilisateur du jeton, jamais le corps de requête.
 */
@RestController
@RequestMapping("/api/admin")
public class AssignmentsController implements AssignmentsApi {

    private static final String CLAIM_TENANT = "tenant_id";
    private static final String CLAIM_USER = "continuum_user_id";

    private final AssignmentService assignments;

    public AssignmentsController(AssignmentService assignments) {
        this.assignments = assignments;
    }

    @Override
    public ResponseEntity<Assignment> createAssignment(AssignmentInput input) {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID actor = uuidClaim(jwt, CLAIM_USER);

        UUID id;
        try {
            id = assignments.assign(new AssignPositionCommand(
                    tenant, actor, input.getUserId(), input.getPositionId(), input.getUnitId(),
                    input.getKind().getValue(), input.getValidFrom()));
        } catch (SelfGrantNotAllowedException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (StaticSodViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }

        Assignment body = new Assignment(input.getUserId(), input.getPositionId(),
                Assignment.KindEnum.fromValue(input.getKind().getValue()),
                input.getValidFrom(), id, Assignment.StateEnum.ACTIVE)
                .unitId(input.getUnitId());
        if (input.getValidTo() != null) {
            body.validTo(input.getValidTo());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
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
