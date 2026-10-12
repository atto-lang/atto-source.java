package dev.oreslang.nodes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Direct Java-level assertions for suppressed exceptions that the public
 * Graal PolyglotException projection does not guarantee to expose. */
final class ArmResultCleanupFailureTest {
    @Test
    void earlierExecutionErrorRetainsEachDistinctCleanupError() {
        IllegalStateException original = new IllegalStateException("original failure");
        IllegalArgumentException first = new IllegalArgumentException("first deferred error");
        IllegalArgumentException second = new IllegalArgumentException("second deferred error");
        OresEvalRootNode.preserveCleanupFailure(original, first);
        OresEvalRootNode.preserveCleanupFailure(original, second);
        assertArrayEquals(new Throwable[]{first, second}, original.getSuppressed());
        assertEquals("original failure", original.getMessage());
    }

    @Test
    void noSelfSuppressionOrDuplicateCleanupCauses() {
        IllegalStateException original = new IllegalStateException("original");
        IllegalStateException cleanup = new IllegalStateException("cleanup");
        OresEvalRootNode.preserveCleanupFailure(original, original);
        OresEvalRootNode.preserveCleanupFailure(original, cleanup);
        OresEvalRootNode.preserveCleanupFailure(original, cleanup);
        OresEvalRootNode.preserveCleanupFailure(original, null);
        assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
    }
}
