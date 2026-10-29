package com.continuum.admin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Platform-scope operational alert rules (contract {@code AlertRule}); {@code window} is an ISO-8601 duration. */
public interface AlertRuleStore {

    record Rule(UUID id, String label, String eventCode, int threshold, String window,
                List<String> channels, boolean enabled) {
    }

    List<Rule> list();

    Rule create(Rule rule, UUID actorTenantId, UUID actorUserId);

    /** @return the updated rule, empty when {@code id} is unknown */
    Optional<Rule> update(UUID id, Rule rule, UUID actorTenantId, UUID actorUserId);

    /** @return {@code false} when {@code id} is unknown */
    boolean delete(UUID id, UUID actorTenantId, UUID actorUserId);
}
