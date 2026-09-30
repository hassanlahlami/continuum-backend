package com.continuum.iam.api;

import java.util.List;
import java.util.UUID;

/** Consultation des affectations d'un utilisateur (plan §2.1.3). */
public interface AssignmentDirectory {

    /**
     * Affectations actives de l'utilisateur (dates valides), triées par rang de position.
     *
     * @param locale locale de résolution du libellé de position (ex. {@code fr})
     */
    List<AssignmentView> activeAssignments(UUID tenantId, UUID userId, String locale);
}
