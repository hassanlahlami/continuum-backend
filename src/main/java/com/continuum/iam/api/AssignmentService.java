package com.continuum.iam.api;

import java.util.UUID;

/** Écriture des affectations (plan §2.1.3, §2.1.8). */
public interface AssignmentService {

    /**
     * Affecte une position à un utilisateur et journalise l'octroi (§2.3).
     *
     * <p>Applique l'<strong>interdiction d'auto-octroi</strong> (§2.1.8) : un utilisateur ne peut pas
     * s'affecter lui-même une position.
     *
     * @return l'identifiant de l'affectation créée
     * @throws SelfGrantNotAllowedException si l'auteur est le bénéficiaire
     */
    UUID assign(AssignPositionCommand command);

    /** Levée quand l'auteur d'un octroi tente de se l'attribuer à lui-même (§2.1.8). */
    class SelfGrantNotAllowedException extends RuntimeException {
        public SelfGrantNotAllowedException() {
            super("Auto-octroi interdit : l'auteur d'une affectation ne peut pas en être le bénéficiaire (§2.1.8).");
        }
    }
}
