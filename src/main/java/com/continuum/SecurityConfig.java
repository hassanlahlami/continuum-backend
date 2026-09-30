package com.continuum;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Sécurité de l'API partagée (plan §4.3.3) : <em>resource server</em> OAuth2 (JWT Keycloak), API
 * sans état, et <strong>sécurité de méthode</strong> activée pour {@code @PreAuthorize("@authz.can(...)")}
 * (§2.1.7).
 *
 * <p>MVP : une seule chaîne. Les deux chaînes {@code /api/admin/**} (audience {@code continuum-admin},
 * {@code acr} ≥ 2) et {@code /api/app/**} (audience {@code continuum-app}) du §4.3.3 seront séparées
 * ensuite.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info", "/api/public/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
