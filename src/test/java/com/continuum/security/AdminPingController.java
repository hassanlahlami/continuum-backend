package com.continuum.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint de test sous {@code /api/admin/**} pour éprouver la chaîne admin (§4.3.3). */
@RestController
public class AdminPingController {

    @GetMapping("/api/admin/ping")
    public String ping() {
        return "admin";
    }
}
