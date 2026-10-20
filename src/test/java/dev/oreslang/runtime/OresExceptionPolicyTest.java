package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

final class OresExceptionPolicyTest {

    @Test
    void ordinaryDeferFailureCannotReplaceAPanic() {
        OresPanic panic = new OresPanic("broken invariant");
        IllegalStateException cleanup = new IllegalStateException("cleanup");
        Throwable finalFailure = OresExceptionPolicy.combine(panic, cleanup);
        assertSame(panic, finalFailure);
        assertArrayEquals(new Throwable[]{cleanup}, panic.getSuppressed());
        assertThrows(OresPanic.class, () -> OresExceptionPolicy.rethrow(finalFailure));
    }

    @Test
    void panicOverridesOrdinaryAndRetainsItsCause() {
        IllegalStateException ordinary = new IllegalStateException("source throw");
        OresPanic panic = new OresPanic("panic during defer");
        Throwable finalFailure = OresExceptionPolicy.combine(ordinary, panic);
        assertSame(panic, finalFailure);
        assertArrayEquals(new Throwable[]{ordinary}, panic.getSuppressed());
    }

    @Test
    void cancellationCannotBeSwallowedByCleanupPanic() {
        CancellationException cancelled = new CancellationException("stop");
        OresPanic cleanup = new OresPanic("cleanup panic");
        Throwable finalFailure = OresExceptionPolicy.combine(cancelled, cleanup);
        assertSame(cancelled, finalFailure);
        assertArrayEquals(new Throwable[]{cleanup}, cancelled.getSuppressed());
    }

    @Test
    void raiseDominatesOrdinaryButNotPanic() {
        IllegalArgumentException ordinary = new IllegalArgumentException("ordinary");
        OresRaised raised = new OresRaised("raise");
        assertSame(raised, OresExceptionPolicy.combine(ordinary, raised));
        OresPanic panic = new OresPanic("panic");
        assertSame(panic, OresExceptionPolicy.combine(raised, panic));
        assertEquals(1, panic.getSuppressed().length);
    }

    @Test
    void processExitDominatesPanicRaisedDuringCleanup() {
        ProcessExitSignal exit = new ProcessExitSignal(23);
        OresPanic cleanup = new OresPanic("cleanup broke an invariant");
        Throwable terminal = OresExceptionPolicy.combine(exit, cleanup);
        assertSame(exit, terminal, "explicit process exit must never become a guest panic");
        assertArrayEquals(new Throwable[]{cleanup}, exit.getSuppressed());
        ProcessExitSignal actual = assertThrows(ProcessExitSignal.class,
                () -> OresExceptionPolicy.rethrow(terminal));
        assertEquals(23, actual.exitStatus());
    }

    @Test
    void hostErrorsStayAboveGuestPanicAndStructuredCancellation() {
        OresPanic pending = new OresPanic("panic before finally");
        AssertionError hostFailure = new AssertionError("host invariant");
        Throwable dominant = OresExceptionPolicy.combine(pending, hostFailure);
        assertSame(hostFailure, dominant,
                "an uncaught Java Error cannot be downgraded into a guest panic");
        assertArrayEquals(new Throwable[]{pending}, hostFailure.getSuppressed());

        CancellationException cancelled = new CancellationException("cancelled");
        AssertionError duringCleanup = new AssertionError("cleanup host failure");
        assertSame(duringCleanup, OresExceptionPolicy.combine(cancelled, duringCleanup));
        assertArrayEquals(new Throwable[]{cancelled}, duringCleanup.getSuppressed());

        // A scheduler cancellation signal remains cancellation, not HostFatal,
        // even though its JVM representation extends Error.
        assertSame(cancelled,
                OresExceptionPolicy.combine(cancelled, new OresPanic("later panic")));
    }

    @Test
    void everyCleanupFailureCombinationFollowsTheCrossBackendPriorityLattice() {
        java.util.List<java.util.function.Supplier<? extends Throwable>> factories =
                java.util.List.of(
                        () -> new IllegalStateException("throw"),
                        () -> new OresRaised("raise"),
                        () -> new OresPanic("panic"),
                        () -> new CancellationException("cancelled"),
                        () -> new ProcessExitSignal(9),
                        () -> new AssertionError("host failure"));
        int[] priority = {1, 2, 3, 4, 5, 5};
        for (int pendingKind = 0; pendingKind < factories.size(); pendingKind++) {
            for (int cleanupKind = 0; cleanupKind < factories.size(); cleanupKind++) {
                Throwable pending = factories.get(pendingKind).get();
                Throwable cleanup = factories.get(cleanupKind).get();
                Throwable result = OresExceptionPolicy.combine(pending, cleanup);
                boolean cleanupWins = priority[cleanupKind] > priority[pendingKind];
                assertSame(cleanupWins ? cleanup : pending, result,
                        "precedence mismatch: " + pendingKind + " -> " + cleanupKind);
                assertArrayEquals(new Throwable[]{cleanupWins ? pending : cleanup},
                        result.getSuppressed(),
                        "suppressed ordering mismatch: " + pendingKind + " -> " + cleanupKind);
            }
        }
    }

    @Test
    void selfSuppressionIsNotAttempted() {
        OresPanic same = new OresPanic("same");
        assertSame(same, OresExceptionPolicy.combine(same, same));
        assertEquals(0, same.getSuppressed().length);
    }
    @Test
    void checkedHostThrowableCannotBecomeGuestCatchableIllegalState() {
        Exception checked = new Exception("unexpected FFI checked failure");
        AssertionError hostFatal = assertThrows(AssertionError.class,
                () -> OresExceptionPolicy.rethrow(checked));
        assertSame(checked, hostFatal.getCause());
        assertTrue(hostFatal.getMessage().contains("host failure"));
        // A guest catch(Exception) or trap of RuntimeException cannot consume
        // the host-error carrier. An ordinary guest throw remains unchanged.
        IllegalStateException ordinary = new IllegalStateException("source");
        assertSame(ordinary, assertThrows(IllegalStateException.class,
                () -> OresExceptionPolicy.rethrow(ordinary)));
        assertDoesNotThrow(() -> OresExceptionPolicy.rethrow(null));
    }

    @Test
    void checkedHostCauseDoesNotErasePriorityOfExistingCancellation() {
        CancellationException cancelled = new CancellationException("cancel");
        AssertionError fromBoundary = assertThrows(AssertionError.class,
                () -> OresExceptionPolicy.rethrow(new Exception("checked host")));
        Throwable dominant = OresExceptionPolicy.combine(cancelled, fromBoundary);
        assertSame(fromBoundary, dominant);
        assertArrayEquals(new Throwable[]{cancelled}, fromBoundary.getSuppressed());
    }

}
