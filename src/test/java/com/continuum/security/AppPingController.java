package com.continuum.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint de test sous {@code /api/app/**} pour éprouver la chaîne app (§4.3.3). */
@RestController
public class AppPingController {

    @GetMapping("/api/app/ping")
    public String ping() {
        return "app";
    }
}
