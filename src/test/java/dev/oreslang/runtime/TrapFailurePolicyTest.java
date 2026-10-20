package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

final class TrapFailurePolicyTest {
    private static final class GuestPanic extends RuntimeException { }
    private static final class TrustedCastError extends RuntimeException { }
    private static class SameShapeHostCast extends RuntimeException { }
    private static final class ForgedSubtype extends SameShapeHostCast { }

    @Test void onlyExplicitGuestFailuresHaveAuthenticatedTrapOrigin() {
        // A JDK exception's mutable stack trace is NOT a runtime-origin proof.
        assertFalse(TrapFailurePolicy.isTrappable(
                new IllegalStateException("Assert.equal: values are not equal"), GuestPanic.class));
        assertFalse(TrapFailurePolicy.isTrappable(
                new CompletionException(new IllegalArgumentException("ordinary guest error")),
                GuestPanic.class));
        assertTrue(TrapFailurePolicy.isTrappable(
                new GuestControlSignal.Throw("ordinary guest throw"), GuestPanic.class));
        assertTrue(TrapFailurePolicy.isTrappable(
                new CompletionException(new GuestControlSignal.Throw("wrapped guest throw")),
                GuestPanic.class));
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
    @Test void hostExceptionCannotForgeRuntimeProvenanceByReplacingStackTrace() {
        RuntimeException foreign = new IllegalStateException("host network failure");
        foreign.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(
                        "dev.oreslang.runtime.TrapFailurePolicy",
                        "permitted", "TrapFailurePolicy.java", 1)
        });
        assertFalse(TrapFailurePolicy.isTrappable(foreign, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(foreign, GuestPanic.class));
        RuntimeException wrapped = new CompletionException(
                new GuestControlSignal.Throw("guest error"));
        wrapped.addSuppressed(foreign);
        assertFalse(TrapFailurePolicy.isTrappable(wrapped, GuestPanic.class),
                "a guest cause must not hide a forged host error in cleanup");
    }

    @Test void offCarrierCancellationOrPanicCannotBeDowngradedThroughSuppression() {
        var failure = new CompletionException(
                new GuestControlSignal.Throw("normal guest failure"));
        failure.addSuppressed(new CompletionException(
                new GuestControlSignal.Raise("nontrappable CONTROL")));
        assertFalse(TrapFailurePolicy.isTrappable(failure, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(failure, GuestPanic.class));
    }

    @Test void maliciousHostCannotForgeGuestOriginByRewritingStackTrace() {
        RuntimeException forged = new IllegalStateException("looks like an Oreslang error");
        forged.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("dev.oreslang.nodes.OresEvalRootNode",
                        "evaluate", "OresEvalRootNode.java", 101),
                new StackTraceElement("dev.oreslang.runtime.GuestControlSignal",
                        "throwing", "GuestControlSignal.java", 12)
        });
        assertFalse(TrapFailurePolicy.isTrappable(
                forged, GuestPanic.class, TrustedCastError.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(
                forged, GuestPanic.class, TrustedCastError.class));
        assertFalse(TrapFailurePolicy.isTrappable(
                new CompletionException(forged), GuestPanic.class, TrustedCastError.class));
    }

    @Test void exactlyTypedInterpreterErrorSurvivesTransparentWrappersOnly() {
        var cast = new TrustedCastError();
        assertTrue(TrapFailurePolicy.isTrappable(
                cast, GuestPanic.class, TrustedCastError.class));
        assertTrue(TrapFailurePolicy.isTrappable(
                new CompletionException(cast), GuestPanic.class, TrustedCastError.class));
        assertTrue(TrapFailurePolicy.isGuestCatchable(
                cast, GuestPanic.class, TrustedCastError.class));
        assertFalse(TrapFailurePolicy.isTrappable(
                cast, GuestPanic.class),
                "two-argument boundary grants no implicit cast-error authority");
        assertFalse(TrapFailurePolicy.isTrappable(
                new SameShapeHostCast(), GuestPanic.class, TrustedCastError.class));
        assertFalse(TrapFailurePolicy.isTrappable(
                new ForgedSubtype(), GuestPanic.class, SameShapeHostCast.class),
                "subclasses cannot inherit interpreter-only provenance");
        var hostWrapper = new RuntimeException("untrusted host wrapper", cast);
        assertFalse(TrapFailurePolicy.isTrappable(
                hostWrapper, GuestPanic.class, TrustedCastError.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(
                hostWrapper, GuestPanic.class, TrustedCastError.class));
    }

    @Test void opaqueHostCleanupCannotLaunderSuppressedGuestThrow() {
        RuntimeException failure = new RuntimeException("host failure");
        failure.addSuppressed(new GuestControlSignal.Throw("not primary"));
        assertFalse(TrapFailurePolicy.isTrappable(
                failure, GuestPanic.class, TrustedCastError.class));
        var wrapped = new CompletionException(new GuestControlSignal.Throw("source"));
        wrapped.addSuppressed(new IllegalStateException("uncertified host cleanup"));
        assertFalse(TrapFailurePolicy.isTrappable(
                wrapped, GuestPanic.class, TrustedCastError.class));
    }

}
