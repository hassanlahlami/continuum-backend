package com.continuum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * Continuum — monolithe modulaire (plan §4.3).
 *
 * <p>Une seule application, une seule API partagee par les deux frontends
 * (Plateforme Fonctionnelle et Admin Systeme). La separation passe par la
 * securite (audiences {@code continuum-app} / {@code continuum-admin}), pas par
 * la duplication du code (§4.3.3).
 *
 * <p>Les frontieres entre modules ({@code tenancy}, {@code iam}, {@code audit}…)
 * sont verifiees par les tests Spring Modulith / ArchUnit (§4.3.1).
 */
@SpringBootApplication
@Modulithic(systemName = "Continuum")
public class ContinuumApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContinuumApplication.class, args);
    }
}
