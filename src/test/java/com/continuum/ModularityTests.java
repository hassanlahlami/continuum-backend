package com.continuum;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifie les frontieres de modules (plan §4.3.1) : un module n'accede qu'a l'API publique
 * ({@code api}) ou aux evenements d'un autre module, jamais a ses entites ni a ses repositories.
 */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(ContinuumApplication.class);

    @Test
    void verifiesModuleBoundaries() {
        MODULES.verify();
    }
}
