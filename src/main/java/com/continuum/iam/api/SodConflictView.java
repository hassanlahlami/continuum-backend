package com.continuum.iam.api;

import java.util.List;
import java.util.UUID;

/**
 * Conflit SoD statique constaté sur les affectations actuelles : un utilisateur détient, avec des
 * portées qui se recoupent, les deux fonctionnalités d'une règle {@code STATIC}.
 *
 * @param userId         utilisateur en conflit
 * @param rule           règle enfreinte
 * @param viaAssignments affectations qui portent l'une ou l'autre des deux fonctionnalités
 */
public record SodConflictView(UUID userId, SodRuleView rule, List<UUID> viaAssignments) {
}
