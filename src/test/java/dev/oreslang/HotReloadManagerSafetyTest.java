package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.HotReloadManager;
import dev.oreslang.runtime.IsolatePolicy;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for manager-level source generation policy/lifetime safety. */
final class HotReloadManagerSafetyTest {
    private static final ExecutionProfile PROFILE = ExecutionProfile.serverJit();
    private static final String GOOD = "pub routine main(): void { return; }";
    private static final String BAD = """
            pub routine main(): void {
              val numbers = arr[1];
              val invalid = numbers[99];
              return;
            }
            """;

    @Test
    void rejectsGuestCapabilityEscalationEvenWhenSupervisorMayHotLoad() {
        IsolatePolicy supervisor = IsolatePolicy.developer();
        IsolatePolicy guest = supervisor.withCapabilities(IsolatePolicy.Capability.NATIVE);
        SecurityException denied = assertThrows(SecurityException.class,
                () -> new HotReloadManager(supervisor, guest, PROFILE));
        assertTrue(denied.getMessage().contains("no more permissive"));
    }

    @Test
    void rejectsGuestHeapMailboxAndWallTimeEscalation() {
        IsolatePolicy supervisor = IsolatePolicy.developer();
        IsolatePolicy biggerHeap = new IsolatePolicy(
                supervisor.capabilities(),
                supervisor.maxHeapBytes() + 1L,
                supervisor.maxMailboxMessages(),
                supervisor.maxWallTime(),
                false);
        IsolatePolicy biggerMailbox = new IsolatePolicy(
                supervisor.capabilities(),
                supervisor.maxHeapBytes(),
                supervisor.maxMailboxMessages() + 1,
                supervisor.maxWallTime(),
                false);
        IsolatePolicy longerRuntime = new IsolatePolicy(
                supervisor.capabilities(),
                supervisor.maxHeapBytes(),
                supervisor.maxMailboxMessages(),
                supervisor.maxWallTime().plusSeconds(1),
                false);

        assertThrows(SecurityException.class, () -> new HotReloadManager(supervisor, biggerHeap, PROFILE));
        assertThrows(SecurityException.class, () -> new HotReloadManager(supervisor, biggerMailbox, PROFILE));
        assertThrows(SecurityException.class, () -> new HotReloadManager(supervisor, longerRuntime, PROFILE));
    }

    @Test
    void adversarialSupervisorCannotRemoveAdversarialModeInChild() {
        IsolatePolicy parent = IsolatePolicy.strictFaas().withCapabilities(IsolatePolicy.Capability.HOT_CODE_LOAD);
        IsolatePolicy weakerGuest = new IsolatePolicy(
                Set.of(IsolatePolicy.Capability.STDOUT),
                32L * 1024 * 1024,
                16,
                Duration.ofSeconds(1),
                false);
        assertThrows(SecurityException.class,
                () -> new HotReloadManager(parent, weakerGuest, PROFILE));
    }

    @Test
    void guestMayBeStrictSubsetOfTrustedSupervisorsAuthority() {
        assertDoesNotThrow(() -> {
            try (var manager = HotReloadManager.forUntrustedActors(IsolatePolicy.developer(), PROFILE)) {
                assertEquals(0, manager.liveGenerations());
            }
        });
    }

    @Test
    void closeSealsManagerAndCannotRecreateGuestContexts() {
        var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE);
        var generation = manager.load("first.ores", GOOD);
        manager.close();
        manager.close();
        assertTrue(generation.closed());
        assertEquals(0, manager.liveGenerations());
        assertThrows(IllegalStateException.class,
                () -> manager.load("forbidden.ores", GOOD));
        assertEquals(0, manager.liveGenerations());
    }

    @Test
    void startFailureViaConvenienceApiRetiresFailedGeneration() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            assertThrows(RuntimeException.class, () -> manager.loadAndStart("bad.ores", BAD));
            assertNull(manager.active("bad.ores"));
            assertEquals(0, manager.liveGenerations());
        }
    }

    @Test
    void directGenerationCloseEvictsDeadActivePointersAndRegistrations() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var generation = manager.loadAndStart("service.ores", GOOD);
            generation.close();
            generation.close();
            assertTrue(generation.closed());
            assertNull(manager.active("service.ores"));
            assertNull(manager.active());
            assertEquals(0, manager.liveGenerations());
            assertTrue(manager.activeGenerations().isEmpty());
        }
    }

    @Test
    void directFailedStartAlsoEvictsClosedGeneration() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var generation = manager.load("service.ores", BAD);
            assertThrows(RuntimeException.class, generation::start);
            assertTrue(generation.closed());
            assertNull(manager.active("service.ores"));
            assertEquals(0, manager.liveGenerations());
        }
    }

    @Test
    void failedLaterVersionReleasesQuotaWithoutClosingPreviouslyStartedContext() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var healthy = manager.loadAndStart("service.ores", GOOD);
            assertThrows(RuntimeException.class, () -> manager.loadAndStart("service.ores", BAD));
            assertFalse(healthy.closed());
            assertEquals(1, manager.liveGenerations());
            // Atomic activation/rollback is tracked separately in issue #475.
            // Never treat the manager's current active pointer as a serving route.
        }
    }

    @Test
    void failedRetireAfterSealRetainsGenerationForExactRetry()
            throws Exception {
        var manager =
                new HotReloadManager(
                        IsolatePolicy.developer(), PROFILE);
        try {
            var generation = manager.load("retry-retire.ores", GOOD);
            var faultField =
                    generation.getClass().getDeclaredField(
                            "closeAfterSealFaultForTesting");
            faultField.setAccessible(true);
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            faultField.set(generation, (Runnable) () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new OutOfMemoryError(
                            "injected generation close after admission seal");
                }
            });

            var failure =
                    assertThrows(
                            OutOfMemoryError.class,
                            () -> manager.retire(generation.id()));
            assertTrue(failure.getMessage().contains(
                    "generation close after admission seal"));
            assertTrue(generation.closed(),
                    "failed retirement must keep execution/pin admission sealed");
            assertNull(manager.active("retry-retire.ores"),
                    "routing must unpublish before physical retirement");
            assertEquals(1, manager.liveGenerations(),
                    "failed physical close must retain exact generation owner");
            assertThrows(
                    IllegalStateException.class,
                    generation::pinActorRoute,
                    "sealed failed-retirement generation cannot mint new pins");

            manager.retire(generation.id());

            assertEquals(2, attempts.get());
            assertEquals(0, manager.liveGenerations());
            assertTrue(generation.closed());
        } finally {
            manager.close();
        }
    }

    @Test
    void managerCloseRetriesGenerationAfterContextClosedButBeforeEviction()
            throws Exception {
        var manager =
                new HotReloadManager(
                        IsolatePolicy.developer(), PROFILE);
        try {
            var retained = manager.load("retry-manager-close.ores", GOOD);
            var sibling = manager.load("retry-manager-sibling.ores", GOOD);

            var faultField =
                    retained.getClass().getDeclaredField(
                            "closeAfterContextFaultForTesting");
            var contextClosedField =
                    retained.getClass().getDeclaredField(
                            "contextCloseComplete");
            faultField.setAccessible(true);
            contextClosedField.setAccessible(true);
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            faultField.set(retained, (Runnable) () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new OutOfMemoryError(
                            "injected after generation Context close");
                }
            });

            var failure =
                    assertThrows(OutOfMemoryError.class, manager::close);
            assertTrue(failure.getMessage().contains(
                    "after generation Context close"));
            assertTrue(retained.closed());
            assertTrue(contextClosedField.getBoolean(retained),
                    "physical Context close receipt must survive later failure");
            assertEquals(1, manager.liveGenerations(),
                    "only failed deregistration owner should remain");
            assertTrue(sibling.closed(),
                    "manager close must continue closing independent generations");
            assertThrows(
                    IllegalStateException.class,
                    () -> manager.load("forbidden-after-seal.ores", GOOD),
                    "failed manager close remains terminal for new admission");

            manager.close();

            assertEquals(2, attempts.get());
            assertEquals(0, manager.liveGenerations());
            assertTrue(manager.activeGenerations().isEmpty());
        } finally {
            manager.close();
        }
    }


    @Test
    void directCloseFailureUnpublishesRouteButRetainsCleanupRegistry()
            throws Exception {
        var manager =
                new HotReloadManager(
                        IsolatePolicy.developer(), PROFILE);
        try {
            var generation = manager.load("direct-close-retry.ores", GOOD);
            var faultField =
                    generation.getClass().getDeclaredField(
                            "closeAfterSealFaultForTesting");
            faultField.setAccessible(true);
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            faultField.set(generation, (Runnable) () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new OutOfMemoryError(
                            "injected direct close after seal");
                }
            });

            assertThrows(OutOfMemoryError.class, generation::close);

            assertTrue(generation.closed());
            assertNull(manager.active("direct-close-retry.ores"),
                    "sealed generation must be removed from active routing");
            assertNull(manager.active());
            assertEquals(1, manager.liveGenerations(),
                    "failed direct close must retain cleanup registration");

            generation.close();

            assertEquals(2, attempts.get());
            assertEquals(0, manager.liveGenerations());
        } finally {
            manager.close();
        }
    }

}
