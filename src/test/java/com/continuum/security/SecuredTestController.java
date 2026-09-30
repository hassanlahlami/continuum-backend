package com.continuum.security;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Contrôleur de test pour vérifier le câblage {@code @PreAuthorize("@authz.can(...)")} (§2.1.7). */
@RestController
public class SecuredTestController {

    @GetMapping("/test/unit/{unitId}/approve")
    @PreAuthorize("@authz.can('mission.order.approve', #unitId)")
    public String approve(@PathVariable UUID unitId) {
        return "ok";
    }
}
