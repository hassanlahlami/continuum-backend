package com.continuum.ccm.api;

import java.util.List;
import java.util.UUID;

/**
 * Triage d'une exception CCM (plan §5.3, D34 ; contrat {@code app/ccm.yaml} {@code performCcmExceptionAction}).
 * Audit <strong>par exception</strong> : le moteur produit des exceptions, un auditeur habilité décide —
 * <strong>retenir</strong> (candidate à un constat), <strong>écarter</strong> (justification obligatoire),
 * <strong>affecter</strong> (prise en charge) ou <strong>rouvrir</strong> une exception écartée. Le serveur
 * décide et contrôle : droit {@code ccm.exception.triage} (§2.1.6), transition de statut valide, verrou
 * optimiste, puis journalisation signée (§2.3). Backend-first (D32).
 */
public interface CcmExceptionTriageService {

    /** Codes d'action (contrat {@code actionCode}). */
    String ACTION_RETAIN = "retain";
    String ACTION_DISMISS = "dismiss";
    String ACTION_ASSIGN = "assign";
    String ACTION_REOPEN = "reopen";

    /** Statuts d'une exception (libellés inline en attendant le module de paramétrage, §1.6). */
    String STATUS_NEW = "NEW";
    String STATUS_RETAINED = "RETAINED";
    String STATUS_DISMISSED = "DISMISSED";

    /**
     * Codes des actions de triage que l'utilisateur courant peut exécuter <strong>maintenant</strong> sur
     * cette exception (alimente {@code availableActions}). Dépend du statut et du droit de triage.
     */
    List<String> availableActions(UUID tenantId, UUID userId, CcmExceptionView exception);

    /**
     * Applique une action de triage à une exception.
     *
     * <p>Contrôles, dans l'ordre : existence ({@link CcmExceptionNotVisibleException} → 404) ; droit
     * {@code ccm.exception.triage} sur l'unité de l'exception, ou à la racine du tenant pour une exception
     * sans unité ({@link TriageNotPermittedException} → 403) ; justification présente pour
     * {@value #ACTION_DISMISS} ({@link JustificationRequiredException} → 422) ; transition de statut valide
     * ({@link IllegalTriageTransitionException} → 409) ; verrou optimiste ({@link StaleCcmExceptionException}
     * → 409). En cas de succès, le statut/affectation change et l'action est journalisée (§2.3).
     *
     * @return la projection de l'exception après triage
     */
    CcmExceptionView triage(TriageCommand command);

    /**
     * Commande de triage.
     *
     * @param tenantId        entité
     * @param actorUserId     utilisateur qui trie
     * @param exceptionId     exception visée
     * @param actionCode      action ({@value #ACTION_RETAIN} / {@value #ACTION_DISMISS} /
     *                        {@value #ACTION_ASSIGN} / {@value #ACTION_REOPEN})
     * @param expectedVersion version attendue (verrou optimiste) ; {@code null} = pas de contrôle
     * @param justification   motif, obligatoire pour {@value #ACTION_DISMISS}
     * @param assigneeUserId  destinataire, requis pour {@value #ACTION_ASSIGN}
     */
    record TriageCommand(
            UUID tenantId,
            UUID actorUserId,
            UUID exceptionId,
            String actionCode,
            Integer expectedVersion,
            String justification,
            UUID assigneeUserId) {
    }

    /** Projection d'une exception pour le triage (sous-ensemble du contrat {@code CcmException}). */
    record CcmExceptionView(
            UUID id,
            UUID tenantId,
            UUID orgUnitId,
            String reference,
            String controlCode,
            String title,
            String statusCode,
            String statusLabel,
            String criticalityCode,
            UUID assigneeUserId,
            String dismissalJustification,
            UUID findingId,
            int version) {
    }

    /** Exception inconnue pour ce tenant → 404. */
    class CcmExceptionNotVisibleException extends RuntimeException {
        public CcmExceptionNotVisibleException() {
            super("Exception inconnue ou hors périmètre.");
        }
    }

    /** L'utilisateur n'a pas {@code ccm.exception.triage} sur cette exception (§2.1.6) → 403. */
    class TriageNotPermittedException extends RuntimeException {
        public TriageNotPermittedException() {
            super("Triage refusé : fonctionnalité ccm.exception.triage absente sur cette exception (§2.1.6).");
        }
    }

    /** Écarter une exception sans justification → 422. */
    class JustificationRequiredException extends RuntimeException {
        public JustificationRequiredException() {
            super("Écarter une exception exige une justification (§5.3).");
        }
    }

    /** Action incompatible avec le statut courant (ex. rouvrir une exception non écartée) → 409. */
    class IllegalTriageTransitionException extends RuntimeException {
        public IllegalTriageTransitionException(String from, String action) {
            super("Action « " + action + " » impossible depuis le statut " + from + ".");
        }
    }

    /** La version attendue ne correspond plus (exception modifiée entre-temps) → 409. */
    class StaleCcmExceptionException extends RuntimeException {
        public StaleCcmExceptionException(int expected, int actual) {
            super("Exception modifiée entre-temps : version attendue " + expected + ", actuelle " + actual + ".");
        }
    }
}
