package com.continuum.iam.api;

import java.util.List;
import java.util.UUID;

/** Écriture des affectations (plan §2.1.3, §2.1.8). */
public interface AssignmentService {

    /**
     * Affecte une position à un utilisateur et journalise l'octroi (§2.3).
     *
     * <p>Applique l'<strong>interdiction d'auto-octroi</strong> (§2.1.8) : un utilisateur ne peut pas
     * s'affecter lui-même une position.
     *
     * <p>Applique le <strong>contrôle SoD statique à l'affectation</strong> (§2.1.8) : si l'affectation
     * ferait cumuler à l'utilisateur, sur l'unité, une paire de fonctionnalités interdite par une règle
     * {@link SodViolation.Severity#BLOCK}, l'octroi est refusé et la transaction annulée.
     *
     * @return l'identifiant de l'affectation créée
     * @throws SelfGrantNotAllowedException si l'auteur est le bénéficiaire
     * @throws StaticSodViolationException  si l'affectation crée un conflit SoD statique bloquant
     */
    UUID assign(AssignPositionCommand command);

    /** Levée quand l'auteur d'un octroi tente de se l'attribuer à lui-même (§2.1.8). */
    class SelfGrantNotAllowedException extends RuntimeException {
        public SelfGrantNotAllowedException() {
            super("Auto-octroi interdit : l'auteur d'une affectation ne peut pas en être le bénéficiaire (§2.1.8).");
        }
    }

    /** Levée quand une affectation créerait un cumul de fonctionnalités interdit par une règle SoD (§2.1.8). */
    class StaticSodViolationException extends RuntimeException {
        private final transient List<SodViolation> conflicts;

        public StaticSodViolationException(List<SodViolation> conflicts) {
            super("Affectation refusée : conflit SoD statique (§2.1.8) — " + conflicts);
            this.conflicts = List.copyOf(conflicts);
        }

        /** Les règles SoD bloquantes violées par l'affectation. */
        public List<SodViolation> conflicts() {
            return conflicts;
        }
    }
}
