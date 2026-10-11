package dev.oreslang.nodes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Mechanical regression checks for lossless suppressed cleanup failure. */
final class FinallyCleanupFailureTest {
    @Test
    void cleanupFailureRetainsEarlierExceptionalExitExactlyOnce() {
        IllegalStateException pending = new IllegalStateException("original");
        IllegalArgumentException cleanup = new IllegalArgumentException("cleanup");

        assertSame(cleanup,
                OresEvalRootNode.preserveSuppressedCleanupFailure(cleanup, pending));
        assertArrayEquals(new Throwable[] { pending }, cleanup.getSuppressed());

        OresEvalRootNode.preserveSuppressedCleanupFailure(cleanup, pending);
        assertArrayEquals(new Throwable[] { pending }, cleanup.getSuppressed(),
                "re-entered cleanup must not duplicate an earlier failure");
    }

    @Test
    void repeatedSameThrowableDoesNotSelfSuppress() {
        IllegalStateException identical = new IllegalStateException("identical");
        assertSame(identical,
                OresEvalRootNode.preserveSuppressedCleanupFailure(
                        identical, identical));
        assertEquals(0, identical.getSuppressed().length);
    }

    @Test
    void multipleDistinctFailuresKeepIdentityAndOrder() {
        RuntimeException original = new RuntimeException("original");
        RuntimeException firstCleanup = new RuntimeException("first cleanup");
        RuntimeException secondCleanup = new RuntimeException("second cleanup");

        OresEvalRootNode.preserveSuppressedCleanupFailure(firstCleanup, original);
        OresEvalRootNode.preserveSuppressedCleanupFailure(secondCleanup, firstCleanup);

        assertArrayEquals(new Throwable[] { firstCleanup },
                secondCleanup.getSuppressed());
        assertArrayEquals(new Throwable[] { original },
                firstCleanup.getSuppressed());
    }
}
