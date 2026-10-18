package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

final class TrapFailurePolicyTest {
    private static final class GuestPanic extends RuntimeException { }

    @Test void ordinaryGuestFailuresRemainTrappable() {
        assertTrue(TrapFailurePolicy.isTrappable(
                new IllegalStateException("Assert.equal: values are not equal"), GuestPanic.class));
        assertTrue(TrapFailurePolicy.isTrappable(
                new CompletionException(new IllegalArgumentException("ordinary guest error")),
                GuestPanic.class));
    }

    @Test void directTerminalFailuresCannotBecomeNone() {
        for (RuntimeException terminal : new RuntimeException[]{
                new SecurityException("denied"),
                new CancellationException("cancelled"),
                new RejectedExecutionException("scheduler closed"),
                new GuestPanic()
        }) {
            assertFalse(TrapFailurePolicy.isTrappable(terminal, GuestPanic.class),
                    terminal::toString);
        }
    }

    @Test void nestedSchedulerControlFailuresCannotBecomeNone() {
        for (Throwable terminal : new Throwable[]{
                new SecurityException("denied"),
                new CancellationException("cancelled"),
                new InterruptedException("interrupted"),
                new TimeoutException("deadline"),
                new RejectedExecutionException("refused"),
                new AssertionError("runtime invariant violated"),
                new GuestPanic()
        }) {
            RuntimeException wrapped = new CompletionException(
                    new IllegalStateException("future failed", terminal));
            assertFalse(TrapFailurePolicy.isTrappable(wrapped, GuestPanic.class),
                    terminal::toString);
        }
    }

    @Test void cleanupSuppressedControlFailureIsNotSwallowed() {
        IllegalStateException failure = new IllegalStateException("ordinary failure");
        failure.addSuppressed(new CompletionException(
                new SecurityException("permission denied during cleanup")));
        assertFalse(TrapFailurePolicy.isTrappable(failure, GuestPanic.class));
    }

    @Test void cyclesAndExcessiveDepthFailClosed() {
        IllegalStateException a = new IllegalStateException("a");
        IllegalStateException b = new IllegalStateException("b");
        a.initCause(b);
        b.initCause(a);
        assertFalse(TrapFailurePolicy.isTrappable(a, GuestPanic.class));

        RuntimeException chain = new IllegalStateException("root");
        for (int index = 0; index < 65; index++) {
            chain = new IllegalStateException("wrapper " + index, chain);
        }
        assertFalse(TrapFailurePolicy.isTrappable(chain, GuestPanic.class));
    }

    @Test void explicitGuestCatchCanRecoverDenialAndTimeout() {
        assertTrue(TrapFailurePolicy.isGuestCatchable(
                new SecurityException("denied"), GuestPanic.class));
        assertTrue(TrapFailurePolicy.isGuestCatchable(
                new CompletionException(new SecurityException("wrapped denial")),
                GuestPanic.class));
        assertTrue(TrapFailurePolicy.isGuestCatchable(
                new CompletionException(new TimeoutException("request timed out")),
                GuestPanic.class));
        assertFalse(TrapFailurePolicy.isTrappable(
                new CompletionException(new SecurityException("wrapped denial")),
                GuestPanic.class));
    }

    @Test void explicitGuestCatchCannotConsumeSchedulerControl() {
        for (Throwable control : new Throwable[]{
                new CancellationException("cancelled"),
                new RejectedExecutionException("scheduler closed"),
                new InterruptedException("interrupted"),
                new AssertionError("host failure"),
                new GuestPanic()
        }) {
            assertFalse(TrapFailurePolicy.isGuestCatchable(
                    new CompletionException(control), GuestPanic.class),
                    control::toString);
        }
    }

    @Test void excessivelyWideSuppressedGraphsFailClosed() {
        RuntimeException failure = new IllegalStateException("root");
        for (int index = 0; index < 65; index++) {
            failure.addSuppressed(new IllegalStateException("cleanup " + index));
        }
        assertFalse(TrapFailurePolicy.isTrappable(failure, GuestPanic.class));
    }
}
