package com.continuum.audit.api;

import com.continuum.iam.api.SodViolation;
import java.util.List;
import java.util.UUID;

/**
 * Actions d'écriture sur un constat (plan §2.1.4, §2.2). Le serveur décide et contrôle : droit
 * ({@code finding.validate}, §2.1.6), <strong>SoD dynamique</strong> (l'auteur d'un constat ne peut
 * pas le valider, §2.2) et journalisation signée (§2.3). Backend-first (D32).
 */
public interface FindingActionService {

    /** Code d'action (contrat {@code actionCode}) : validation d'un constat. */
    String ACTION_VALIDATE = "validate";

    /**
     * Codes des actions que l'utilisateur courant peut exécuter <strong>maintenant</strong> sur ce constat
     * (plan §2.1.4 ; alimente {@code availableActions}). Le front n'en déduit aucun droit : il n'affiche
     * que ce que le serveur renvoie.
     *
     * @return les codes d'action disponibles (ex. {@link #ACTION_VALIDATE}), vide si aucune
     */
    List<String> availableActions(UUID tenantId, UUID userId, UUID missionId, FindingView finding);

    /**
     * Valide un constat visible dans la mission.
     *
     * <p>Contrôles, dans l'ordre : visibilité ({@code finding.read}, §2.1.6) ; droit de validation
     * ({@code finding.validate} sur l'unité auditée de la mission, §2.1.6) ; SoD <strong>dynamique</strong>
     * (§2.2) interdisant la validation par l'auteur du constat ; verrou optimiste ({@code version}).
     * En cas de succès, le statut passe à {@code VALIDATED} et l'action est journalisée (§2.3).
     *
     * @return la projection du constat après validation
     * @throws FindingNotVisibleException   le constat n'existe pas dans la mission ou n'est pas visible
     * @throws ActionNotPermittedException  l'utilisateur n'a pas {@code finding.validate} sur la mission
     * @throws DynamicSodViolationException la validation viole une règle SoD dynamique bloquante (§2.2)
     * @throws StaleFindingException        la version attendue ne correspond pas (modifié entre-temps)
     */
    FindingView validate(ValidateFindingCommand command);

    /**
     * Commande de validation d'un constat.
     *
     * @param tenantId        entité
     * @param actorUserId     utilisateur qui valide
     * @param missionId       mission portant le constat
     * @param findingId       constat à valider
     * @param expectedVersion version attendue (verrou optimiste) ; {@code null} = pas de contrôle
     * @param comment         commentaire facultatif journalisé
     */
    record ValidateFindingCommand(
            UUID tenantId,
            UUID actorUserId,
            UUID missionId,
            UUID findingId,
            Integer expectedVersion,
            String comment) {
    }

    /** Constat absent de la mission ou hors périmètre de l'utilisateur (§2.1.6) → 404. */
    class FindingNotVisibleException extends RuntimeException {
        public FindingNotVisibleException() {
            super("Constat inconnu ou hors périmètre.");
        }
    }

    /** L'utilisateur n'a pas {@code finding.validate} (ni par l'unité, ni par l'équipe de mission, §2.1.6) → 403. */
    class ActionNotPermittedException extends RuntimeException {
        public ActionNotPermittedException() {
            super("Validation refusée : fonctionnalité finding.validate absente sur ce constat (§2.1.6).");
        }
    }

    /** La validation est bloquée par une règle SoD dynamique (ex. auteur du constat, §2.2) → 409. */
    class DynamicSodViolationException extends RuntimeException {
        private final transient List<SodViolation> conflicts;

        public DynamicSodViolationException(List<SodViolation> conflicts) {
            super("Validation refusée : conflit SoD dynamique (§2.2) — " + conflicts);
            this.conflicts = List.copyOf(conflicts);
        }

        /** Les règles SoD dynamiques bloquantes violées par la validation. */
        public List<SodViolation> conflicts() {
            return conflicts;
        }
    }

    /** La version attendue ne correspond plus (constat modifié entre-temps) → 409. */
    class StaleFindingException extends RuntimeException {
        public StaleFindingException(int expected, int actual) {
            super("Constat modifié entre-temps : version attendue " + expected + ", version actuelle " + actual + ".");
        }
    }
}
