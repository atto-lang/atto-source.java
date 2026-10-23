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
        // Creating a Java carrier directly from test/FFI code does not grant
        // compiler-origin authority, even for a valid sealed signal subtype.
        GuestControlSignal.Throw forged = new GuestControlSignal.Throw("host throw");
        assertFalse(forged.compilerIssued());
        assertFalse(TrapFailurePolicy.isTrappable(forged, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(forged, GuestPanic.class));
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
    @Test void onlyTypedPrimaryRaiseCanEnterRecover() {
        GuestControlSignal.Raise forged = new GuestControlSignal.Raise("host raise");
        assertFalse(forged.compilerIssued());
        assertFalse(TrapFailurePolicy.isRecoverableRaise(forged, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isRecoverableRaise(
                new CompletionException(forged), GuestPanic.class));
        assertFalse(TrapFailurePolicy.isRecoverableRaise(
                new GuestControlSignal.Throw("ordinary"), GuestPanic.class));
        assertFalse(TrapFailurePolicy.isRecoverableRaise(
                new IllegalArgumentException("unrelated host failure"), GuestPanic.class));
    }

    @Test void hostCannotForgeIssuerUsingGuestLookingStackTrace() {
        var forged = new GuestControlSignal.Raise("forged");
        forged.setStackTrace(new StackTraceElement[] {
                new StackTraceElement(
                        "dev.oreslang.nodes.OresEvalRootNode$Evaluator",
                        "executeStatement", "OresEvalRootNode.java", 5378)
        });
        assertFalse(forged.compilerIssued(), "host-editable stack traces are not issuer proof");
        assertFalse(TrapFailurePolicy.isRecoverableRaise(forged, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isRecoverableRaise(
                new CompletionException(forged), GuestPanic.class));
    }

    @Test void foreignHostCallbacksCannotManufactureTrappableThrow() {
        var forged = new GuestControlSignal.Throw("fake ordinary throw");
        assertFalse(forged.compilerIssued());
        assertFalse(TrapFailurePolicy.isTrappable(forged, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(
                new CompletionException(forged), GuestPanic.class));
    }

    @Test void panicCannotEnterAnyLocalEffectBoundary() {
        for (RuntimeException panic : new RuntimeException[] {
                new GuestControlSignal.Panic("explicit"),
                new GuestPanic()
        }) {
            assertFalse(TrapFailurePolicy.isRecoverableRaise(panic, GuestPanic.class));
            assertFalse(TrapFailurePolicy.isGuestCatchable(panic, GuestPanic.class));
            assertFalse(TrapFailurePolicy.isTrappable(panic, GuestPanic.class));
            assertFalse(TrapFailurePolicy.isRecoverableRaise(
                    new CompletionException(panic), GuestPanic.class));
        }
    }

    @Test void suppressedRaiseCannotAuthorizeUnrelatedHostFailure() {
        RuntimeException host = new RuntimeException("foreign");
        host.addSuppressed(new GuestControlSignal.Raise("forged secondary"));
        assertFalse(TrapFailurePolicy.isRecoverableRaise(host, GuestPanic.class));
    }

    @Test void pendingRaiseWithTerminalCleanupIsNotRecoverable() {
        for (Throwable terminal : new Throwable[] {
                new GuestControlSignal.Panic("panic"),
                new GuestPanic(),
                new CancellationException("cancelled"),
                new RejectedExecutionException("scheduler stopped"),
                new SecurityException("permission denied"),
                new TimeoutException("expired"),
                new AssertionError("host fatal"),
                new GuestControlSignal.Throw("mixed effects")
        }) {
            var original = new GuestControlSignal.Raise("primary");
            original.addSuppressed(terminal);
            assertFalse(TrapFailurePolicy.isRecoverableRaise(original, GuestPanic.class),
                    terminal::toString);
        }
    }

    @Test void nestedTerminalControlBlocksOtherwiseRecoverableRaise() {
        RuntimeException wrapped = new CompletionException(new GuestControlSignal.Raise("raised"));
        wrapped.addSuppressed(new CompletionException(new CancellationException("cancelled")));
        assertFalse(TrapFailurePolicy.isRecoverableRaise(wrapped, GuestPanic.class));
    }

    @Test void malformedForeignCauseAccessorFailsClosed() {
        RuntimeException foreign = new RuntimeException("foreign") {
            @Override public synchronized Throwable getCause() {
                throw new SecurityException("malformed host cause");
            }
        };
        assertFalse(TrapFailurePolicy.isRecoverableRaise(foreign, GuestPanic.class));
    }

    @Test void alternatingForeignCauseCannotSmuggleRaise() {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        RuntimeException foreign = new RuntimeException("foreign") {
            @Override public synchronized Throwable getCause() {
                reads.incrementAndGet();
                return new GuestControlSignal.Raise("synthetic");
            }
        };
        assertFalse(TrapFailurePolicy.isRecoverableRaise(foreign, GuestPanic.class));
        assertEquals(0, reads.get());
    }

    @Test void cyclicRecoveryCauseGraphFailsClosed() {
        RuntimeException wrapper = new RuntimeException("wrapper");
        var raise = new GuestControlSignal.Raise("raised");
        wrapper.initCause(raise);
        raise.addSuppressed(wrapper);
        assertFalse(TrapFailurePolicy.isRecoverableRaise(wrapper, GuestPanic.class));
    }

    @Test void excessiveRecoveryCauseDepthFailsClosed() {
        RuntimeException wrapped = new GuestControlSignal.Raise("raised");
        for (int index = 0; index < 65; index++) {
            wrapped = new CompletionException(wrapped);
        }
        assertFalse(TrapFailurePolicy.isRecoverableRaise(wrapped, GuestPanic.class));
    }

    @Test void nullAndMissingPanicClassCannotAuthorizeRecovery() {
        assertFalse(TrapFailurePolicy.isRecoverableRaise(null, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isRecoverableRaise(
                new GuestControlSignal.Raise("raised"), null));
    }

}
