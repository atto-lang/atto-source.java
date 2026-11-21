package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GenericHotReloadTransactionTest {
    private static final String BAD_MAIN =
            "pub routine main(): void { val int fail = 1 / 0; return; }";

    @Test
    void failedReplacementKeepsTheOldGeneration() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var original = loader.loadAndStart("legacy.ores", "pub val int answer = 1;");
            assertThrows(RuntimeException.class,
                    () -> loader.loadAndStart("legacy.ores", BAD_MAIN));
            assertSame(original, loader.active("legacy.ores"));
            assertSame(original, loader.active());
            assertFalse(original.closed());
            assertEquals(1, loader.liveGenerations());
        }
    }

    @Test
    void successfulReplacementRetainsOldGenerationUntilRetired() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var original = loader.loadAndStart("legacy.ores", "pub val int answer = 1;");
            var replacement = loader.loadAndStart("legacy.ores", "pub val int answer = 2;");
            assertSame(replacement, loader.active("legacy.ores"));
            assertTrue(replacement.started());
            assertFalse(original.closed());
            assertEquals(2, loader.liveGenerations());
            loader.retire(original.id());
            assertTrue(original.closed());
            assertSame(replacement, loader.active("legacy.ores"));
        }
    }

    @Test
    void failureOfAnotherUnitDoesNotChangeGlobalSelection() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var previous = loader.loadAndStart("stable.ores", "pub val int answer = 1;");
            assertThrows(RuntimeException.class,
                    () -> loader.loadAndStart("broken.ores", BAD_MAIN));
            assertSame(previous, loader.active());
            assertNull(loader.active("broken.ores"));
            assertEquals(1, loader.liveGenerations());
        }
    }
}
