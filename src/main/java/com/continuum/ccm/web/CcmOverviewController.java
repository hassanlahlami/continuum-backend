package com.continuum.ccm.web;

import com.continuum.ccm.api.CcmExceptionDirectory;
import com.continuum.iam.api.AuthorizationService;
import gen.app.ccm.api.CcmOverviewApi;
import gen.app.ccm.model.CcmControl;
import gen.app.ccm.model.CcmOverview;
import gen.app.ccm.model.CcmOverviewExceptionTrendInner;
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
 * Vue d'ensemble du cockpit CCM (contrat {@code app/ccm.yaml} {@code getCcmOverview}, §5). Implémente
 * l'interface <strong>générée</strong> (D32) : compte les contrôles par état de santé (depuis
 * {@link CcmControlAssembler}) et agrège les exceptions dans le périmètre {@code ccm.read}
 * ({@link CcmExceptionDirectory#overviewStats} : ouvertes, à trier, tendance). Lecture seule. La santé
 * reste {@code NEVER_RUN} (donc {@code failing}/{@code drifting} à 0) tant que les exécutions
 * ({@code ccm_run}) ne sont pas historisées.
 */
@RestController
@RequestMapping("/api/app")
public class CcmOverviewController implements CcmOverviewApi {

    private static final String CLAIM_TENANT = "tenant_id";
    private static final String CLAIM_USER = "continuum_user_id";
    private static final String FEATURE_READ = "ccm.read";

    private final CcmControlAssembler assembler;
    private final CcmExceptionDirectory exceptions;
    private final AuthorizationService authorization;

    public CcmOverviewController(CcmControlAssembler assembler, CcmExceptionDirectory exceptions,
            AuthorizationService authorization) {
        this.assembler = assembler;
        this.exceptions = exceptions;
        this.authorization = authorization;
    }

    @Override
    public ResponseEntity<CcmOverview> getCcmOverview() {
        Principal who = authorized();
        List<CcmControl> controls = assembler.list(who.tenant(), null, null, null);
        CcmExceptionDirectory.Stats stats = exceptions.overviewStats(who.tenant(), who.user());

        CcmOverview overview = new CcmOverview()
                .activeControls((int) controls.stream().filter(CcmControl::getActive).count())
                .failingControls(countHealth(controls, CcmControl.HealthEnum.FAILING))
                .driftingControls(countHealth(controls, CcmControl.HealthEnum.DRIFT))
                .openExceptions(stats.openExceptions())
                .exceptionsToTriage(stats.toTriage())
                .controls(controls)
                .exceptionTrend(stats.trend().stream()
                        .map(p -> new CcmOverviewExceptionTrendInner().date(p.date()).count(p.count()))
                        .toList());
        return ResponseEntity.ok(overview);
    }

    private static int countHealth(List<CcmControl> controls, CcmControl.HealthEnum health) {
        return (int) controls.stream().filter(c -> c.getHealth() == health).count();
    }

    /** Vérifie le droit {@code ccm.read} (niveau tenant) et renvoie tenant + utilisateur courants. */
    private Principal authorized() {
        Jwt jwt = currentJwt();
        UUID tenant = uuidClaim(jwt, CLAIM_TENANT);
        UUID user = uuidClaim(jwt, CLAIM_USER);
        boolean canRead = authorization.permissions(tenant, user).stream()
                .anyMatch(sf -> FEATURE_READ.equals(sf.featureCode()));
        if (!canRead) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Fonctionnalité ccm.read absente.");
        }
        return new Principal(tenant, user);
    }

    private record Principal(UUID tenant, UUID user) {
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
