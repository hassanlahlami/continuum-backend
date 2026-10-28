package com.continuum.iam.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read models of the System Admin screens (contract {@code admin/tenancy-iam.yaml}: users, positions, org tree).
 * Read-only, tenant-isolated; writes go through the dedicated services.
 */
public interface AdminDirectory {

    /** Filters of {@link #listUsers}; any filter may be {@code null}. Paging is 0-based. */
    record UserQuery(String text, UUID unitId, String source, String accountStatus, UUID positionId,
                     int page, int size) { }

    record UserRow(UUID id, String username, String source, String status, boolean mfaConfigured,
                   Instant lastLogin, long version) { }

    record UserPageView(List<UserRow> items, long totalItems) { }

    record RoleRef(UUID id, String code, Map<String, String> label, String kind, boolean fromPack,
                   List<String> featureCodes) { }

    record PositionRow(UUID id, String code, Map<String, String> label, int rank, UUID unitTypeId,
                       String unitTypeLabel, String scope, UUID reportsToId, String reportsToLabel,
                       Integer maxHoldersPerUnit, boolean active, List<RoleRef> roles,
                       List<String> resultingFeatures, int holdersCount) { }

    record OrgUnitRow(UUID id, String code, String label, UUID unitTypeId, String typeLabel,
                      int assignmentsCount, List<OrgUnitRow> children) { }

    UserPageView listUsers(UUID tenantId, UserQuery query);

    Optional<UserRow> findUser(UUID tenantId, UUID userId);

    List<PositionRow> listPositions(UUID tenantId, boolean includeInactive);

    /** Root units with nested children; labels in {@code locale} (fallback {@code fr}). */
    List<OrgUnitRow> orgTree(UUID tenantId, String locale);

    /** Enabled (non-disabled) accounts holding no currently valid assignment. */
    long countUsersWithoutAssignment(UUID tenantId);
}
