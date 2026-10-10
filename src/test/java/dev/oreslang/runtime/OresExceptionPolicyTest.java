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
    void selfSuppressionIsNotAttempted() {
        OresPanic same = new OresPanic("same");
        assertSame(same, OresExceptionPolicy.combine(same, same));
        assertEquals(0, same.getSuppressed().length);
    }
}
