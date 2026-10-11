package com.continuum.ccm.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Accès en lecture au CBS (« Amplitude ») pour les contrôles continus sur population (plan §5, pack
 * {@code docs/pack-banque/CONTROLES-CCM.md}). Le connecteur lit les <strong>vues d'audit</strong>
 * {@code V_AUDIT_*} en lecture seule et applique les règles paramétrées des contrôles ; il ne décide
 * pas : il remonte des <em>anomalies</em> candidates qu'un moteur transforme en exceptions (D34, §5.3).
 *
 * <p>Les champs sont ceux du modèle canonique (pack {@code MODELE-CANONIQUE.md}) ; la correspondance
 * avec le dictionnaire Amplitude est faite par l'implémentation (OPS-02). Le CBS est Oracle (D3) :
 * une seule implémentation {@code Oracle...} est fournie.
 */
public interface CcmPopulationGateway {

    /** Anomalie remontée sur un mouvement (§5) ; {@code triggerReason} explicite la règle déclenchée. */
    record MovementAnomaly(
            long movementId,
            String accountId,
            LocalDate operationDate,
            LocalDate valueDate,
            BigDecimal amount,
            String direction,
            String operationCode,
            String enteredBy,
            String validatedBy,
            String channel,
            String triggerReason) {
    }

    /** Paramètres du contrôle « mouvements sur comptes dormants » (CCM-3). */
    record DormantAccountCriteria(
            int dormancyMonths,
            BigDecimal minAmount,
            List<String> excludeChannels,
            LocalDate from,
            LocalDate to) {
    }

    /** Paramètres du contrôle « écritures hors seuil / hors heures ouvrées » (CCM-2). */
    record OutOfBoundsCriteria(
            BigDecimal thresholdAmount,
            List<String> nonWorkingDays,
            int maxBackValueDays,
            List<String> excludeChannels,
            LocalDate from,
            LocalDate to) {
    }

    /**
     * Mouvements sur comptes dormants (CCM-3) : compte au statut {@code DORMANT} ou sans mouvement
     * client depuis {@code dormancyMonths} mois, mouvementé pour au moins {@code minAmount}, hors
     * canaux exclus. {@code triggerReason = DORMANT}.
     */
    List<MovementAnomaly> dormantAccountMovements(DormantAccountCriteria criteria);

    /**
     * Mouvements hors cadre (CCM-2) : montant au-delà du seuil, saisie un jour non ouvré, ou date de
     * valeur trop antérieure à la date comptable. {@code triggerReason ∈ {AMOUNT, NON_WORKING_DAY, BACK_VALUE}}.
     */
    List<MovementAnomaly> outOfBoundsMovements(OutOfBoundsCriteria criteria);
}
