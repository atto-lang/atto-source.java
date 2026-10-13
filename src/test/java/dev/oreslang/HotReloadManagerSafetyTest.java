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
}
