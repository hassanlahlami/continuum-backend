package com.continuum.iam.web;

import com.continuum.iam.api.SodRuleDirectory;
import com.continuum.iam.api.SodRuleView;
import gen.admin.iam.api.SodApi;
import gen.admin.iam.model.SodRule;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consultation des règles de séparation des tâches côté Admin Système (contrat
 * {@code admin/tenancy-iam.yaml}, §2.2). Implémente l'interface <strong>générée</strong> (D32) et
 * délègue au read model {@link SodRuleDirectory}. Le tenant vient du jeton, jamais de la requête.
 * Seule {@code listSodRules} est surchargée ici ; les opérations d'écriture (create/update/delete)
 * seront ajoutées avec leur double validation (ADM-14).
 */
@RestController
@RequestMapping("/api/admin")
public class SodRulesController implements SodApi {

    private static final String CLAIM_TENANT = "tenant_id";

    private final SodRuleDirectory sodRules;

    public SodRulesController(SodRuleDirectory sodRules) {
        this.sodRules = sodRules;
    }

    @Override
    public ResponseEntity<List<SodRule>> listSodRules() {
        UUID tenant = uuidClaim(currentJwt(), CLAIM_TENANT);
        List<SodRule> body = sodRules.listRules(tenant).stream().map(SodRulesController::toModel).toList();
        return ResponseEntity.ok(body);
    }

    private static SodRule toModel(SodRuleView v) {
        return new SodRule(
                v.featureA(),
                v.featureB(),
                SodRule.TypeEnum.fromValue(v.type()),
                SodRule.SeverityEnum.fromValue(v.severity()))
                .id(v.id());
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
