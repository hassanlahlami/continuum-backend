package com.continuum.audit.web;

import com.continuum.audit.api.FindingDirectory;
import com.continuum.audit.api.FindingView;
import gen.app.findings.api.FindingsApi;
import gen.app.findings.model.AuditMeta;
import gen.app.findings.model.Finding;
import gen.app.findings.model.FindingPage;
import gen.app.findings.model.Ref;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    private final FindingDirectory findings;

    public FindingsController(FindingDirectory findings) {
        this.findings = findings;
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

        FindingDirectory.Page result = findings.searchVisible(tenant, user, q, p, s);
        int totalPages = (int) Math.ceil(result.totalItems() / (double) s);
        List<Finding> items = result.items().stream().map(FindingsController::toModel).toList();
        return ResponseEntity.ok(new FindingPage(p, s, result.totalItems(), totalPages, items));
    }

    private static Finding toModel(FindingView v) {
        Finding finding = new Finding(v.title(), v.id(), v.reference(),
                new Ref(v.statusCode(), v.statusLabel()),
                new AuditMeta(v.createdAt(), v.version()))
                .missionId(v.missionId())
                .missionTitle(v.missionTitle());
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
