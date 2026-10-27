package com.continuum.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.continuum.iam.api.IdentityProviderAdmin;
import com.continuum.iam.api.UserAccountActionService;
import com.continuum.iam.api.UserAccountActionService.IllegalUserActionException;
import com.continuum.iam.api.UserAccountActionService.StaleUserException;
import com.continuum.iam.api.UserAccountActionService.UserActionCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Concurrent user actions sharing the same {@code version} (contract {@code performUserAction}: 409 on a stale
 * version, comment required for lock/disable). Same assertions on PostgreSQL and Oracle: exactly one writer wins,
 * the others are rejected as stale (or as an illegal transition if they read the already-committed status).
 */
abstract class UserActionConcurrencyContract {

    static final UUID ACTOR_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final int WRITERS = 8;

    static final IdentityProviderAdmin NOOP_IDP = new IdentityProviderAdmin() {
        @Override
        public void resetMfa(UUID id) {
        }

        @Override
        public void closeSessions(UUID id) {
        }
    };

    /** Fresh service bound to the real database under test. */
    abstract UserAccountActionService service();

    /** Re-creates {@link #USER} as ACTIVE, version 0, MFA configured. */
    abstract void resetUser();

    abstract long version();

    abstract String status();

    @BeforeEach
    void reset() {
        resetUser();
    }

    private Callable<Object> act(String action, Long version, String comment, CountDownLatch start) {
        UserAccountActionService svc = service();
        return () -> {
            start.await();
            try {
                return svc.perform(new UserActionCommand(ACTOR_TENANT, ACTOR, USER, action, version, comment));
            } catch (RuntimeException e) {
                return e;
            }
        };
    }

    private static List<Object> race(List<Callable<Object>> tasks, CountDownLatch start) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            tasks.forEach(t -> futures.add(pool.submit(t)));
            start.countDown();
            List<Object> out = new ArrayList<>();
            for (Future<Object> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @RepeatedTest(5)
    void sameVersionSameActionHasExactlyOneWinner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < WRITERS; i++) {
            tasks.add(act("lock", 0L, "suspicious", start));
        }
        List<Object> results = race(tasks, start);

        assertThat(results.stream().filter(r -> !(r instanceof RuntimeException))).hasSize(1);
        assertThat(results.stream().filter(r -> r instanceof RuntimeException))
                .hasSize(WRITERS - 1)
                .allSatisfy(e -> assertThat(e).isInstanceOfAny(StaleUserException.class,
                        IllegalUserActionException.class));
        assertThat(version()).isEqualTo(1);
        assertThat(status()).isEqualTo("LOCKED");
    }

    @RepeatedTest(5)
    void sameVersionDifferentActionsHasExactlyOneWinner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Object> results = race(List.of(act("lock", 0L, "x", start), act("disable", 0L, "y", start)), start);

        assertThat(results.stream().filter(r -> !(r instanceof RuntimeException))).hasSize(1);
        assertThat(results.stream().filter(r -> r instanceof RuntimeException))
                .hasSize(1)
                .allSatisfy(e -> assertThat(e).isInstanceOfAny(StaleUserException.class,
                        IllegalUserActionException.class));
        assertThat(version()).isEqualTo(1);
        assertThat(status()).isIn("LOCKED", "DISABLED");
    }

    @Test
    void staleVersionIsRejectedAndLeavesAccountUntouched() {
        service().perform(new UserActionCommand(ACTOR_TENANT, ACTOR, USER, "lock", 0L, "x"));
        assertThatThrownBy(() -> service()
                .perform(new UserActionCommand(ACTOR_TENANT, ACTOR, USER, "unlock", 0L, null)))
                .isInstanceOf(StaleUserException.class);
        assertThat(version()).isEqualTo(1);
        assertThat(status()).isEqualTo("LOCKED");
        assertThat(service()
                .perform(new UserActionCommand(ACTOR_TENANT, ACTOR, USER, "unlock", 1L, null)).version())
                .isEqualTo(2);
    }

    @Test
    void commentIsRequiredForLockAndDisable() {
        for (String action : List.of("lock", "disable")) {
            for (String comment : new String[] {null, "", "   "}) {
                assertThatThrownBy(() -> service()
                        .perform(new UserActionCommand(ACTOR_TENANT, ACTOR, USER, action, 0L, comment)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThat(version()).isZero();
        assertThat(status()).isEqualTo("ACTIVE");
    }
}
