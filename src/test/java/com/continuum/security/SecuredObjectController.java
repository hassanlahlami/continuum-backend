package com.continuum.security;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Contrôleur de test pour {@code @authz.canDo(...)} (autorisation + SoD dynamique, §2.1.4). */
@RestController
public class SecuredObjectController {

    @GetMapping("/test/unit/{unitId}/finding/{objectId}/validate")
    @PreAuthorize("@authz.canDo('finding.validate', #unitId, 'FINDING', #objectId)")
    public String validate(@PathVariable UUID unitId, @PathVariable String objectId) {
        return "ok";
    }
}
