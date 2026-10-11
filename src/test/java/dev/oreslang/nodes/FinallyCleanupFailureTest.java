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
    @Test
    void cancellationWithFailingNestedFinallyCannotEnterAnEnclosingGuestCatch() {
        var cancellation = new dev.oreslang.runtime.OresScheduler.TaskCancelledException();
        var cleanupFailure = new IllegalStateException("inner finally failed");
        OresEvalRootNode.preserveSuppressedCleanupFailure(cleanupFailure, cancellation);

        assertFalse(OresEvalRootNode.shouldEnterSourceCatch(
                cancellation, true, false),
                "the cancellation signal itself must never be caught");
        assertFalse(OresEvalRootNode.shouldEnterSourceCatch(
                cleanupFailure, true, false),
                "inner cleanup failure must not turn cancellation into a catchable exit");
        assertArrayEquals(new Throwable[] { cancellation }, cleanupFailure.getSuppressed(),
                "the original cancellation must remain diagnosable");
    }

    @Test
    void cleanupMayCatchItsOwnOrdinaryErrorsWithoutCatchingCancellation() {
        var cancellation = new dev.oreslang.runtime.OresScheduler.TaskCancelledException();
        var localFailure = new IllegalArgumentException("cleanup-local error");

        assertTrue(OresEvalRootNode.shouldEnterSourceCatch(
                localFailure, true, true),
                "an explicitly admitted cleanup block may recover its own local failure");
        assertFalse(OresEvalRootNode.shouldEnterSourceCatch(
                cancellation, true, true),
                "even cleanup-local catch cannot swallow the scheduler cancellation");
    }

    @Test
    void normalGuestExceptionsRemainCatchableBeforeCancellation() {
        var failure = new IllegalStateException("ordinary guest error");
        assertTrue(OresEvalRootNode.shouldEnterSourceCatch(failure, false, false));
        assertTrue(OresEvalRootNode.shouldEnterSourceCatch(failure, false, true));
    }

}
