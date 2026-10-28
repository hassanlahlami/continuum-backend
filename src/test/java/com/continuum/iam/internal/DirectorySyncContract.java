package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.continuum.iam.api.DirectoryFederation;
import com.continuum.iam.api.DirectorySyncService;
import com.continuum.iam.api.DirectorySyncService.State;
import com.continuum.iam.api.IdentityProviderAdmin.IdentityProviderUnavailableException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Behaviour of {@link JdbcDirectorySyncService}, run on PostgreSQL and Oracle by the concrete subclasses. */
abstract class DirectorySyncContract {

    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    static final String SOURCE = "ad-demo";

    abstract JdbcTemplate platformJdbc();

    abstract boolean oracle();

    abstract DirectorySyncService service(DirectoryFederation federation, Executor executor);

    private Object key(UUID id) {
        return oracle() ? ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array() : id;
    }

    @BeforeEach
    void reset() {
        platformJdbc().update("DELETE FROM platform.directory_sync_state");
        platformJdbc().update("DELETE FROM platform.tenant WHERE id = ?", key(TENANT));
        platformJdbc().update("INSERT INTO platform.tenant(id, code, label) VALUES (?, 'SYNC', 'Sync')", key(TENANT));
    }

    @Test
    void neverSynchronisedHasNoStatus() {
        assertThat(service(s -> 0, Runnable::run).status(TENANT)).isEmpty();
    }

    @Test
    void successRecordsLastSyncAtAndBumpsVersion() {
        DirectorySyncService svc = service(s -> 0, Runnable::run);
        var st = svc.runNow(TENANT);
        assertThat(st.state()).isEqualTo(State.SUCCESS);
        assertThat(st.sourceCode()).isEqualTo(SOURCE);
        assertThat(st.lastSyncAt()).isNotNull();
        assertThat(st.lastError()).isNull();
        assertThat(st.version()).isEqualTo(2);
        assertThat(svc.status(TENANT)).get().extracting(DirectorySyncService.SyncState::state)
                .isEqualTo(State.SUCCESS);
    }

    @Test
    void failureKeepsPreviousLastSyncAtAndTruncatesError() {
        var first = service(s -> 0, Runnable::run).runNow(TENANT);
        DirectorySyncService ko = service(s -> {
            throw new IdentityProviderUnavailableException("x".repeat(1500), null);
        }, Runnable::run);
        var st = ko.runNow(TENANT);
        assertThat(st.state()).isEqualTo(State.FAILED);
        assertThat(st.lastError()).hasSize(1000);
        assertThat(st.lastSyncAt()).isEqualTo(first.lastSyncAt());
    }

    @Test
    void failedUsersAreReportedButRunSucceeds() {
        var st = service(s -> 3, Runnable::run).runNow(TENANT);
        assertThat(st.state()).isEqualTo(State.SUCCESS);
        assertThat(st.lastError()).contains("3 user(s)");
    }

    @Test
    void secondTriggerWhileRunningDoesNotStartAnotherRun() {
        List<Runnable> queued = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        DirectorySyncService svc = service(s -> {
            calls.add(s);
            return 0;
        }, queued::add);
        assertThat(svc.trigger(TENANT).state()).isEqualTo(State.RUNNING);
        assertThat(svc.trigger(TENANT).state()).isEqualTo(State.RUNNING);
        assertThat(svc.runNow(TENANT).state()).isEqualTo(State.RUNNING);
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        assertThat(calls).containsExactly(SOURCE);
        assertThat(svc.status(TENANT)).get().extracting(DirectorySyncService.SyncState::state)
                .isEqualTo(State.SUCCESS);
        assertThat(svc.trigger(TENANT).state()).isEqualTo(State.RUNNING);
    }

    @Test
    void unconfiguredSourceRefusesToRun() {
        DirectorySyncService svc = new JdbcDirectorySyncService(
                platformJdbc().getDataSource(), oracle(), s -> 0, Runnable::run, "");
        assertThat(svc.configured()).isFalse();
        assertThat(svc.status(TENANT)).isEmpty();
        assertThatThrownBy(() -> svc.trigger(TENANT)).isInstanceOf(IllegalStateException.class);
    }
}
