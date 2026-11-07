package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class SupervisorCodeSlotTest {
    @Test void stableLeaseAndNoPrematureRetirementOnUpgrade() {
        AtomicInteger freed = new AtomicInteger();
        try (var slot = new SupervisorCodeSlot<>("v1", "Daddy-v1", freed::incrementAndGet, 3)) {
            var old = slot.acquire();
            assertEquals("Daddy-v1", old.code());
            var activated = slot.activate(1, "v1", "Daddy-v2", freed::incrementAndGet);
            assertEquals(2L, activated.revision());
            assertEquals(0, freed.get(), "old active policy turn still pins code");
            try (var fresh = slot.acquire()) {
                assertEquals("Daddy-v2", fresh.code());
                assertEquals(2L, fresh.descriptor().revision());
            }
            assertEquals("Daddy-v1", old.code(), "upgrade rewrote a pinned frame");
            old.close();
            old.close();
            assertEquals(1, freed.get(), "retired revision must clean exactly once");
            assertEquals(1, slot.liveRevisions());
        }
        assertEquals(2, freed.get());
    }

    @Test void failClosedOnAbiMismatchAndStaleUpgrade() {
        AtomicInteger freed = new AtomicInteger();
        try (var slot = new SupervisorCodeSlot<>("abi-v1", "old", freed::incrementAndGet, 2)) {
            assertThrows(IllegalArgumentException.class,
                    () -> slot.activate(1, "abi-v2", "bad", freed::incrementAndGet));
            assertThrows(IllegalStateException.class,
                    () -> slot.activate(2, "abi-v1", "stale", freed::incrementAndGet));
            assertEquals(1, slot.current().revision());
            assertEquals(0, freed.get());
        }
    }

    @Test void boundedPinnedOldPolicyPreventsUnboundedHotReloadRetention() {
        AtomicInteger freed = new AtomicInteger();
        try (var slot = new SupervisorCodeSlot<>("a", "one", freed::incrementAndGet, 2)) {
            var old = slot.acquire();
            slot.activate(1, "a", "two", freed::incrementAndGet);
            try (var current = slot.acquire()) {
                assertEquals("two", current.code(), "active code remains pinned during bounded reload");
                assertThrows(IllegalStateException.class,
                        () -> slot.activate(2, "a", "three", freed::incrementAndGet));
                assertEquals(2, slot.liveRevisions());
            }
            old.close();
            assertEquals(1, freed.get());
            slot.activate(2, "a", "three", freed::incrementAndGet);
            assertEquals(2, freed.get());
        }
        assertEquals(3, freed.get());
    }

    @Test void reclaimFailureAfterUpgradeReportsCommittedRevision() {
        AtomicInteger failedCleanup = new AtomicInteger();
        try (var slot = new SupervisorCodeSlot<>("abi", "old", () -> {
            failedCleanup.incrementAndGet();
            throw new IllegalArgumentException("reclamation failed");
        }, 2)) {
            var failure = assertThrows(
                    SupervisorCodeSlot.PostCommitReclamationException.class,
                    () -> slot.activate(1, "abi", "next", () -> { }));
            assertEquals(2L, failure.committed().revision(),
                    "the activation actually committed before old cleanup failed");
            assertEquals("abi", failure.committed().abi());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertEquals(1, failedCleanup.get(), "old cleanup invoked exactly once");
            assertEquals(2L, slot.current().revision());
            assertThrows(IllegalStateException.class,
                    () -> slot.activate(1, "abi", "retry", () -> { }),
                    "caller must not retry the already committed old revision");
            try (var lease = slot.acquire()) {
                assertEquals("next", lease.code());
            }
        }
        assertEquals(1, failedCleanup.get());
    }

    @Test void reclaimErrorAfterUpgradeStillReportsCommittedRevision() {
        AtomicInteger failedCleanup = new AtomicInteger();
        try (var slot = new SupervisorCodeSlot<>("abi", "old", () -> {
            failedCleanup.incrementAndGet();
            throw new AssertionError("reclamation error");
        }, 2)) {
            var failure = assertThrows(
                    SupervisorCodeSlot.PostCommitReclamationException.class,
                    () -> slot.activate(1, "abi", "next", () -> { }));
            assertEquals(2L, failure.committed().revision(),
                    "Error from post-commit reclaim must not hide committed activation");
            assertEquals("abi", failure.committed().abi());
            assertInstanceOf(AssertionError.class, failure.getCause());
            assertEquals(1, failedCleanup.get());
            assertEquals(2L, slot.current().revision());
            assertThrows(IllegalStateException.class,
                    () -> slot.activate(1, "abi", "retry", () -> { }),
                    "old revision cannot be retried after post-commit Error");
            try (var lease = slot.acquire()) {
                assertEquals("next", lease.code());
            }
        }
        assertEquals(1, failedCleanup.get());
    }

    @Test void concurrentLeasesAndPolicyUpgradesRetireEachGenerationOnce() throws Exception {
        // Bounded deterministic race: no sleep-based synchronization and no
        // executor may outlive its runtime policy slot.
        for (int round = 0; round < 20; round++) {
            AtomicInteger retired = new AtomicInteger();
            AtomicInteger upgrades = new AtomicInteger();
            var slot = new SupervisorCodeSlot<>("abi", 1, retired::incrementAndGet, 4);
            var go = new java.util.concurrent.CountDownLatch(1);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(5);
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            try {
                for (int reader = 0; reader < 4; reader++) {
                    tasks.add(pool.submit(() -> {
                        go.await();
                        for (int i = 0; i < 100; i++) {
                            try (var lease = slot.acquire()) {
                                assertEquals(lease.descriptor().revision(), lease.code().longValue(),
                                        "a pinned code lease cannot change revision during activation");
                            }
                        }
                        return null;
                    }));
                }
                tasks.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < 100; i++) {
                        long expected = slot.current().revision();
                        try {
                            slot.activate(expected, "abi", Math.toIntExact(expected + 1),
                                    retired::incrementAndGet);
                            upgrades.incrementAndGet();
                        } catch (IllegalStateException bounded) {
                            if (!bounded.getMessage().contains("pinned supervisor code revision limit"))
                                throw bounded;
                        }
                    }
                    return null;
                }));
                go.countDown();
                for (var task : tasks) task.get(8, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
                assertEquals(true, pool.awaitTermination(8, java.util.concurrent.TimeUnit.SECONDS),
                        "no worker may retain the supervisor policy after test exit");
                slot.close();
            }
            assertEquals(0, slot.liveRevisions(), "every retired generation must be reclaimed");
            assertEquals(upgrades.get() + 1, retired.get(),
                    "a successfully installed policy must be reclaimed exactly once");
            assertEquals(true, upgrades.get() > 0, "upgrader must make forward progress");
        }
    }

    @Test void closeFencesNewAcquisitionsButWaitsForPinnedCleanup() {
        AtomicInteger freed = new AtomicInteger();
        var slot = new SupervisorCodeSlot<>("v1", "running", freed::incrementAndGet, 2);
        var pinned = slot.acquire();
        slot.close();
        assertEquals(0, freed.get());
        assertThrows(IllegalStateException.class, slot::acquire);
        pinned.close();
        assertEquals(1, freed.get());
        slot.close();
        assertEquals(1, freed.get());
    }
}
