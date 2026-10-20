package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

final class TrapFailurePolicyTest {
    private static final class GuestPanic extends RuntimeException { }

    // Production's equivalent is a privately constructed interpreter fault.
    private static final class TrustedCastFailure extends RuntimeException {
        private TrustedCastFailure(String message) { super(message); }
    }

    @Test void guestThrowAndTrustedInterpreterFaultAreTrappable() {
        assertTrue(TrapFailurePolicy.isTrappable(
                new GuestControlSignal.Throw("ordinary guest throw"), GuestPanic.class));
        assertTrue(TrapFailurePolicy.isTrappable(
                new CompletionException(new TrustedCastFailure("guest cast failure")),
                GuestPanic.class, TrustedCastFailure.class));
        assertFalse(TrapFailurePolicy.isTrappable(
                new TrustedCastFailure("missing trusted class"), GuestPanic.class));
        assertTrue(TrapFailurePolicy.isGuestCatchable(
                new CompletionException(new TrustedCastFailure("guest cast failure")),
                GuestPanic.class, TrustedCastFailure.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(
                new TrustedCastFailure("missing trusted class"), GuestPanic.class));
    }

    @Test void forgedTraceNeverAuthorizesHostErrors() {
        for (RuntimeException foreign : new RuntimeException[]{
                new IllegalArgumentException("foreign"),
                new IllegalStateException("foreign"),
                new ArithmeticException("foreign"),
                new ClassCastException("foreign")
        }) {
            foreign.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("dev.oreslang.nodes.OresEvalRootNode",
                            "eval", "OresEvalRootNode.java", 8080)
            });
            assertFalse(TrapFailurePolicy.isTrappable(foreign, GuestPanic.class),
                    foreign::toString);
            assertFalse(TrapFailurePolicy.isGuestCatchable(foreign, GuestPanic.class),
                    foreign::toString);
            assertFalse(TrapFailurePolicy.isTrappable(
                    foreign, GuestPanic.class, TrustedCastFailure.class));
        }
    }

    @Test void unclassifiedHostRuntimeFailuresFailClosed() {
        assertFalse(TrapFailurePolicy.isTrappable(
                new RuntimeException("unclassified host failure"), GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(
                new RuntimeException("unclassified host failure"), GuestPanic.class));

        IllegalArgumentException foreign = new IllegalArgumentException("foreign failure");
        foreign.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("vendor.client.HttpClient", "request", "HttpClient.java", 42)
        });
        assertFalse(TrapFailurePolicy.isTrappable(foreign, GuestPanic.class));
    }

    @Test void directTerminalFailuresCannotBecomeNone() {
        for (RuntimeException terminal : new RuntimeException[]{
                new SecurityException("denied"),
                new CancellationException("cancelled"),
                new RejectedExecutionException("scheduler closed"),
                new GuestPanic(),
                new GuestControlSignal.Raise("escape"),
                new GuestControlSignal.Panic("invariant")
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

    @Test void explicitGuestCatchCanRecoverDenialButNotRuntimeTimeout() {
        assertTrue(TrapFailurePolicy.isGuestCatchable(
                new SecurityException("denied"), GuestPanic.class));
        assertTrue(TrapFailurePolicy.isGuestCatchable(
                new CompletionException(new SecurityException("wrapped denial")),
                GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(
                new CompletionException(new TimeoutException("runtime deadline")),
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
                new TimeoutException("runtime deadline"),
                new AssertionError("host failure"),
                new GuestPanic(),
                new GuestControlSignal.Raise("escape"),
                new GuestControlSignal.Panic("invariant")
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

    @Test void suppressedHostExceptionCannotBeLaunderedByRealGuestThrow() {
        RuntimeException failure = new CompletionException(
                new GuestControlSignal.Throw("authenticated guest throw"));
        var forgedHost = new IllegalStateException("host network failure");
        forgedHost.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(
                        "dev.oreslang.nodes.OresEvalRootNode",
                        "eval", "OresEvalRootNode.java", 1)
        });
        failure.addSuppressed(forgedHost);
        assertFalse(TrapFailurePolicy.isTrappable(failure, GuestPanic.class),
                "an authentic primary guest Throw must not hide a forged HOST cleanup failure");
        assertFalse(TrapFailurePolicy.isGuestCatchable(failure, GuestPanic.class));
    }

    @Test void suppressedControlSignalsCannotEscapePrimaryGuestClassification() {
        RuntimeException failure = new CompletionException(
                new GuestControlSignal.Throw("ordinary guest error"));
        failure.addSuppressed(new CompletionException(
                new GuestControlSignal.Raise("CONTROL must reach supervisor")));
        assertFalse(TrapFailurePolicy.isTrappable(failure, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(failure, GuestPanic.class));
    }

    @Test void exactTrustedFaultClassIsRequiredThroughTransparentWrappers() {
        var authentic = new CompletionException(new TrustedCastFailure("cast"));
        assertTrue(TrapFailurePolicy.isTrappable(
                authentic, GuestPanic.class, TrustedCastFailure.class));

        var unrelated = new CompletionException(new IllegalStateException("host error"));
        assertFalse(TrapFailurePolicy.isTrappable(
                unrelated, GuestPanic.class, TrustedCastFailure.class));
        // Even the presence of a valid trusted cast fault elsewhere in the
        // suppressed tree cannot authorize a distinct untrusted HOST node.
        unrelated.addSuppressed(new TrustedCastFailure("trusted diagnostic"));
        assertFalse(TrapFailurePolicy.isTrappable(
                unrelated, GuestPanic.class, TrustedCastFailure.class));
    }
}
