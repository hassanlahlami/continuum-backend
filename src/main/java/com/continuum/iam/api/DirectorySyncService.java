package com.continuum.iam.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Directory synchronisation state and execution, per tenant (table {@code platform.directory_sync_state}).
 * One row per tenant and source; the source is the Keycloak user-storage component name.
 */
public interface DirectorySyncService {

    /** NEVER = no attempt yet; the others mirror the last attempt. */
    enum State { NEVER, RUNNING, SUCCESS, FAILED }

    /** {@code lastSyncAt} is the end of the last <em>successful</em> run. */
    record SyncState(UUID id, String sourceCode, State state, Instant lastSyncAt, String lastError,
                     long version) { }

    /** Whether a directory source is configured ({@code continuum.directory-sync.source-code}). */
    boolean configured();

    /** Last known state of the configured source; empty when not configured or never attempted. */
    Optional<SyncState> status(UUID tenantId);

    /**
     * Claims the run (RUNNING) and returns at once; the synchronisation executes in the background.
     * If a run is already in progress its state is returned unchanged.
     *
     * @throws IllegalStateException if no directory source is configured
     */
    SyncState trigger(UUID tenantId);

    /** Same as {@link #trigger} but synchronous: returns the final state. Used by the scheduled job. */
    SyncState runNow(UUID tenantId);
}
