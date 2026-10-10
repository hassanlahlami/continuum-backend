package com.continuum.ccm.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Active la planification pour les contrôles CCM continus (§5). */
@Configuration
@EnableScheduling
class CcmSchedulingConfig {
}
