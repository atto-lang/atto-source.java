package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.HotReloadManager;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transactional generic hot-loader regression: tests actual guest startup
 * and live routing, NOT actor state/mailbox migration or physical isolation.
 */
final class HotReloadTransactionalPublicationTest {
    private static final String GOOD = "pub routine main(): void { return; }";
    private static final String BAD = """
            pub routine main(): void {
              val values = arr[1];
              val boom = values[99];
              return;
            }
            """;

    @Test
    void failedReplacementNeverEvictsRunningGeneration() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var previous = manager.loadAndStart("worker.ores", GOOD);
            assertTrue(previous.started());
            assertSame(previous, manager.active("worker.ores"));
            assertThrows(RuntimeException.class, () -> manager.reloadAndStart("worker.ores", BAD));
            assertSame(previous, manager.active("worker.ores"));
            assertSame(previous, manager.active());
            assertFalse(previous.closed());
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void successfulReplacementPublishesOnlyStartedCandidateAndPinsPriorVersion() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var previous = manager.loadAndStart("worker.ores", GOOD);
            var current = manager.reloadAndStart("worker.ores", GOOD + "\nconst int newVersion = 2;");
            assertTrue(current.started());
            assertSame(current, manager.active("worker.ores"));
            assertSame(current, manager.active());
            assertFalse(previous.closed());
            assertEquals(2, manager.liveGenerations());
            manager.retire(previous.id());
            assertTrue(previous.closed());
            assertSame(current, manager.active("worker.ores"));
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void failureOnOtherUnitPreservesLastServingSelection() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var original = manager.loadAndStart("stable.ores", GOOD);
            assertThrows(RuntimeException.class, () -> manager.loadAndStart("broken.ores", BAD));
            assertSame(original, manager.active());
            assertSame(original, manager.active("stable.ores"));
            assertNull(manager.active("broken.ores"));
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void legacyLoadStillStagesWithoutRunningItsEntrypoint() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var staged = manager.load("legacy.ores", GOOD);
            assertFalse(staged.started());
            // Generic load() still publishes for backward compatibility;
            // this pointer is NOT evidence of readiness to accept traffic.
            assertSame(staged, manager.active("legacy.ores"));
        }
    }
}
