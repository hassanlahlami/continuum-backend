package com.continuum.ccm.internal;

import com.continuum.ccm.api.CcmControlDirectory.CcmControl;
import com.continuum.ccm.api.CcmPopulationGateway.DormantAccountCriteria;
import com.continuum.ccm.api.CcmPopulationGateway.OutOfBoundsCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Traduit le paramétrage d'un contrôle (CCM-03) en critères de requête du connecteur CBS (§5). Les
 * valeurs viennent des paramètres du tenant ; les repli sont les défauts documentés du pack
 * ({@code CONTROLES-CCM.md}). Fonctions pures, testables sans base.
 */
final class CcmCriteriaMapper {

    static final String CONTROL_DORMANT = "COMPTES-DORMANTS";
    static final String CONTROL_OUT_OF_BOUNDS = "SEUIL-HORAIRE";
    /** Contrôle SoD du pack (CCM-1) dont le moteur de cumul d'habilitations réalise le « volet B » (Q14). */
    static final String CONTROL_SOD = "SOD-VIREMENTS";

    private CcmCriteriaMapper() {
    }

    /** Critères du contrôle « comptes dormants » (CCM-3) depuis les paramètres. */
    static DormantAccountCriteria dormant(CcmControl control, LocalDate from, LocalDate to) {
        return new DormantAccountCriteria(
                control.integer("dormancyMonths", 12),
                control.amount("minAmount", new BigDecimal("10000")),
                control.strings("excludeChannels"),
                from, to);
    }

    /** Seuil de « sortie importante » élevant la criticité (CCM-3). */
    static BigDecimal largeOutflow(CcmControl control) {
        return control.amount("largeOutflowAmount", new BigDecimal("100000"));
    }

    /** Critères du contrôle « hors seuil / hors heures ouvrées » (CCM-2) depuis les paramètres. */
    static OutOfBoundsCriteria outOfBounds(CcmControl control, LocalDate from, LocalDate to) {
        List<String> nonWorkingDays = control.strings("nonWorkingDays");
        return new OutOfBoundsCriteria(
                control.amount("thresholdAmount", new BigDecimal("1000000")),
                nonWorkingDays.isEmpty() ? List.of("SAT", "SUN") : nonWorkingDays,
                control.integer("maxBackValueDays", 2),
                control.strings("excludeChannels"),
                from, to);
    }
}
