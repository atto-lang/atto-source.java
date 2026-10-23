package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RecoveryHandlerChainTest {

    @Test void registrationIsLifoAndExplicitBindingIsPreserved() {
        String context = "bound";
        StringBuilder trace = new StringBuilder();
        RecoveryHandlerChain<Integer> chain = RecoveryHandlerChain.<Integer>builder()
                .onRaise((error, c) -> {
                    trace.append("A");
                    c.resolve(42);
                })
                .onRaise((error, c) -> {
                    trace.append(context).append("B");
                    c.next();
                })
                .freeze();
        var result = chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE,
                new IllegalStateException("raised"));
        assertEquals(new RecoveryHandlerChain.Recovered<>(42), result);
        assertEquals("boundBA", trace.toString());
    }

    @Test void localHandlerShortCircuitsAndNeverCallsEarlierHandlers() {
        List<Integer> visited = new ArrayList<>();
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> { visited.add(1); c.resolve(42); })
                .register((e, c) -> { visited.add(2); c.resolve(7); })
                .freeze();
        assertEquals(new RecoveryHandlerChain.Recovered<>(7),
                chain.dispatch(RecoveryHandlerChain.FailureKind.THROW, new Exception()));
        assertEquals(List.of(2), visited);
    }

    @Test void exhaustedChainPreservesOriginalErrorIdentityAndKind() {
        Throwable original = new IllegalArgumentException("test");
        var chain = RecoveryHandlerChain.<Integer>builder()
                .onRaise((e, c) -> c.next())
                .onRaise((e, c) -> c.next())
                .freeze();
        var result = chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, original);
        var unhandled = assertInstanceOf(RecoveryHandlerChain.Unhandled.class, result);
        assertSame(original, unhandled.error());
        assertEquals(RecoveryHandlerChain.FailureKind.RAISE, unhandled.kind());
    }

    @Test void panicCancellationAndFatalNeverInvokeGuestRecovery() {
        List<String> trace = new ArrayList<>();
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> { trace.add("invoked"); c.resolve(0); })
                .freeze();
        Throwable error = new AssertionError("fatal");
        for (var kind : List.of(RecoveryHandlerChain.FailureKind.PANIC,
                RecoveryHandlerChain.FailureKind.CANCELLATION,
                RecoveryHandlerChain.FailureKind.HOST_FATAL)) {
            var result = assertInstanceOf(RecoveryHandlerChain.Unhandled.class,
                    chain.dispatch(kind, error));
            assertSame(error, result.error());
            assertEquals(kind, result.kind());
        }
        assertEquals(List.of(), trace);
    }

    @Test void throwAndRaiseFiltersAreIndependent() {
        StringBuilder trace = new StringBuilder();
        var chain = RecoveryHandlerChain.<Integer>builder()
                .onThrow((e, c) -> { trace.append('T'); c.resolve(1); })
                .onRaise((e, c) -> { trace.append('R'); c.resolve(2); })
                .freeze();
        assertEquals(new RecoveryHandlerChain.Recovered<>(1),
                chain.dispatch(RecoveryHandlerChain.FailureKind.THROW, new Exception()));
        assertEquals(new RecoveryHandlerChain.Recovered<>(2),
                chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, new Exception()));
        assertEquals("TR", trace.toString());
    }

    @Test void incompleteHandlerFailsClosedAndCannotSilentlySwallow() {
        var chain = RecoveryHandlerChain.<Integer>builder().register((e, c) -> { }).freeze();
        Exception original = new Exception("pending");
        var result = assertInstanceOf(RecoveryHandlerChain.HandlerFailure.class,
                chain.dispatch(RecoveryHandlerChain.FailureKind.THROW, original));
        assertSame(original, result.pendingError());
        assertEquals(RecoveryHandlerChain.FailureKind.THROW, result.pendingKind());
        assertInstanceOf(IllegalStateException.class, result.callbackError());
    }

    @Test void continuationIsOneShotAndRevokedAfterHandlerReturns() {
        List<RecoveryHandlerChain.Continuation<Integer>> leaked = new ArrayList<>();
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> { leaked.add(c); c.resolve(7); })
                .register((e, c) -> { leaked.add(c); c.next(); })
                .freeze();
        assertEquals(new RecoveryHandlerChain.Recovered<>(7),
                chain.dispatch(RecoveryHandlerChain.FailureKind.THROW, new Exception()));
        for (var c : leaked) {
            assertThrows(IllegalStateException.class, c::next);
            assertThrows(IllegalStateException.class, () -> c.resolve(100));
        }
        assertNotSame(leaked.get(0), leaked.get(1));
    }

    @Test void doubleDecisionIsRejectedWithoutLosingOriginalFailure() {
        Throwable original = new Exception("original raise");
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> { c.next(); c.resolve(9); }).freeze();
        var result = assertInstanceOf(RecoveryHandlerChain.HandlerFailure.class,
                chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, original));
        assertSame(original, result.pendingError());
        assertInstanceOf(IllegalStateException.class, result.callbackError());
    }

    @Test void failureDuringHandlerRevokesContinuation() {
        List<RecoveryHandlerChain.Continuation<Integer>> leaked = new ArrayList<>();
        RuntimeException boom = new IllegalArgumentException("handler failed");
        Throwable original = new Exception("original raise");
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> { leaked.add(c); throw boom; }).freeze();
        var result = assertInstanceOf(RecoveryHandlerChain.HandlerFailure.class,
                chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, original));
        assertSame(boom, result.callbackError());
        assertSame(original, result.pendingError());
        assertEquals(RecoveryHandlerChain.FailureKind.RAISE, result.pendingKind());
        assertThrows(IllegalStateException.class, () -> leaked.getFirst().next());
    }


    @Test void wrappedAndSuppressedTerminalControlIsNeverRecoverable() {
        var visited = new ArrayList<String>();
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> { visited.add("called"); c.resolve(9); }).freeze();
        Throwable[] prohibited = {
                new java.util.concurrent.CompletionException(
                        new java.util.concurrent.CancellationException("cancel")),
                new RuntimeException("wrapper",
                        new java.util.concurrent.TimeoutException("deadline")),
                new RuntimeException("denied", new SecurityException("no authority")),
                new java.util.concurrent.CompletionException(new AssertionError("host fatal")),
                new java.util.concurrent.RejectedExecutionException("stopped")
        };
        for (Throwable signal : prohibited) {
            var result = assertInstanceOf(RecoveryHandlerChain.Unhandled.class,
                    chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, signal));
            assertSame(signal, result.error());
        }
        RuntimeException ordinary = new RuntimeException("ordinary");
        ordinary.addSuppressed(new SecurityException("suppressed denial"));
        var result = assertInstanceOf(RecoveryHandlerChain.Unhandled.class,
                chain.dispatch(RecoveryHandlerChain.FailureKind.THROW, ordinary));
        assertSame(ordinary, result.error());
        assertTrue(visited.isEmpty());
    }

    @Test void cyclicAndExcessiveFailureGraphsFailClosed() {
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> c.resolve(7)).freeze();
        RuntimeException a = new RuntimeException("A");
        RuntimeException b = new RuntimeException("B");
        a.initCause(b);
        b.initCause(a);
        assertInstanceOf(RecoveryHandlerChain.Unhandled.class,
                chain.dispatch(RecoveryHandlerChain.FailureKind.THROW, a));

        RuntimeException wide = new RuntimeException("wide");
        for (int i = 0; i < 65; i++) {
            wide.addSuppressed(new RuntimeException("suppressed-" + i));
        }
        assertInstanceOf(RecoveryHandlerChain.Unhandled.class,
                chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, wide));
    }

    @Test void crossThreadContinuationUseIsRejected() throws Exception {
        var errors = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> {
                    Thread foreign = new Thread(() -> {
                        try {
                            c.resolve(12);
                        } catch (Throwable error) {
                            errors.set(error);
                        }
                    });
                    foreign.start();
                    try {
                        foreign.join();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", interrupted);
                    }
                    c.resolve(7);
                }).freeze();
        assertEquals(new RecoveryHandlerChain.Recovered<>(7),
                chain.dispatch(RecoveryHandlerChain.FailureKind.THROW, new Exception()));
        assertInstanceOf(IllegalStateException.class, errors.get());
    }

    @Test void noEligibleHandlerDoesNotInvokeRecovery() {
        var visited = new ArrayList<String>();
        var chain = RecoveryHandlerChain.<Integer>builder()
                .onThrow((e, c) -> { visited.add("throw"); c.resolve(1); })
                .freeze();
        Throwable original = new RuntimeException("not a throw");
        var result = assertInstanceOf(RecoveryHandlerChain.Unhandled.class,
                chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, original));
        assertSame(original, result.error());
        assertTrue(visited.isEmpty());
    }

    @Test void immutableSnapshotAndBoundedRegistration() {
        var builder = RecoveryHandlerChain.<Integer>builder();
        var frozen = builder.register((e, c) -> c.resolve(1)).freeze();
        builder.register((e, c) -> c.resolve(2));
        assertEquals(1, frozen.size());
        assertEquals(new RecoveryHandlerChain.Recovered<>(1),
                frozen.dispatch(RecoveryHandlerChain.FailureKind.THROW, new Exception()));
        for (int i = 2; i < RecoveryHandlerChain.MAX_HANDLERS; i++) {
            builder.register((e, c) -> c.next());
        }
        assertThrows(IllegalStateException.class,
                () -> builder.register((e, c) -> c.next()));
    }

    @Test void resolvedThenCallbackThrowsDoesNotConsumeFailure() {
        RuntimeException callbackError = new RuntimeException("callback failed after resolve");
        Throwable pendingError = new Exception("raised first");
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> { c.resolve(7); throw callbackError; }).freeze();
        var result = assertInstanceOf(RecoveryHandlerChain.HandlerFailure.class,
                chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, pendingError));
        assertSame(callbackError, result.callbackError());
        assertSame(pendingError, result.pendingError());
    }

    @Test void callbackCannotResolveOrDelegateAfterMakingPendingSignalTerminal() {
        for (boolean resolves : List.of(true, false)) {
            List<String> trace = new ArrayList<>();
            Throwable pending = new Exception("original raise");
            var chain = RecoveryHandlerChain.<Integer>builder()
                    .onRaise((err, c) -> { trace.add("older"); c.resolve(3); })
                    .onRaise((err, c) -> {
                        pending.addSuppressed(new SecurityException("revoked"));
                        if (resolves) c.resolve(7);
                        else c.next();
                    })
                    .freeze();
            var outcome = assertInstanceOf(RecoveryHandlerChain.Unhandled.class,
                    chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, pending));
            assertSame(pending, outcome.error());
            assertEquals(RecoveryHandlerChain.FailureKind.RAISE, outcome.kind());
            assertTrue(trace.isEmpty(), "must not reach older handler or return success");
        }
    }

    @Test void cancellationAndSecurityThrownByHandlerAreTerminalOutcomes() {
        RuntimeException[] terminal = {
                new java.util.concurrent.CancellationException("cancel"),
                new SecurityException("denied"),
                new java.util.concurrent.RejectedExecutionException("stopped"),
                new java.util.concurrent.CompletionException(
                        new java.util.concurrent.TimeoutException("deadline"))
        };
        for (RuntimeException callback : terminal) {
            Throwable pending = new Exception("original");
            var chain = RecoveryHandlerChain.<Integer>builder()
                    .register((e, c) -> { throw callback; }).freeze();
            var result = assertInstanceOf(RecoveryHandlerChain.TerminalCallbackFailure.class,
                    chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, pending));
            assertSame(pending, result.pendingError());
            assertSame(callback, result.terminalError());
            assertEquals(RecoveryHandlerChain.FailureKind.RAISE, result.pendingKind());
        }
    }

    @Test void ordinaryHandlerFailureRemainsDistinctFromTerminalSignals() {
        RuntimeException callback = new IllegalArgumentException("ordinary callback fault");
        Throwable pending = new Exception("pending raise");
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> { throw callback; }).freeze();
        var result = assertInstanceOf(RecoveryHandlerChain.HandlerFailure.class,
                chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, pending));
        assertSame(pending, result.pendingError());
        assertSame(callback, result.callbackError());
    }

    @Test void callbackCannotEscapeAHostFatalWithoutRevokingItsCursor() {
        Error fatal = new AssertionError("host fatal");
        Throwable pending = new Exception("pending");
        List<RecoveryHandlerChain.Continuation<Integer>> escaped = new ArrayList<>();
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> { escaped.add(c); throw fatal; }).freeze();
        assertSame(fatal, assertThrows(AssertionError.class,
                () -> chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, pending)));
        assertEquals(1, escaped.size());
        assertThrows(IllegalStateException.class, () -> escaped.getFirst().next());
    }

    @Test void callbackCausingHostAccessorFailureCannotSwallowOriginalError() {
        Throwable pending = new Exception("pending");
        RuntimeException callback = new RuntimeException("ordinary");
        callback.initCause(new Exception("cause") {
            @Override public synchronized Throwable getCause() {
                throw new IllegalStateException("host graph unavailable");
            }
        });
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> { throw callback; }).freeze();
        var result = assertInstanceOf(RecoveryHandlerChain.TerminalCallbackFailure.class,
                chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, pending));
        assertSame(pending, result.pendingError());
        assertSame(callback, result.terminalError());
    }

    @Test void inaccessibleCauseFailsClosed() {
        Throwable pending = new Exception("pending") {
            @Override public synchronized Throwable getCause() {
                throw new IllegalStateException("cause unavailable");
            }
        };
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> c.resolve(7)).freeze();
        for (var kind : new RecoveryHandlerChain.FailureKind[] {
                RecoveryHandlerChain.FailureKind.THROW,
                RecoveryHandlerChain.FailureKind.RAISE }) {
            var result = assertInstanceOf(RecoveryHandlerChain.Unhandled.class,
                    chain.dispatch(kind, pending));
            assertSame(pending, result.error());
            assertEquals(kind, result.kind());
        }
    }

    @Test void emptyChainAndNullRegistrationAreSafe() {
        var empty = RecoveryHandlerChain.<Integer>builder().freeze();
        assertEquals(0, empty.size());
        Throwable pendingError = new Exception("empty");
        var result = assertInstanceOf(RecoveryHandlerChain.Unhandled.class,
                empty.dispatch(RecoveryHandlerChain.FailureKind.THROW, pendingError));
        assertSame(pendingError, result.error());
        var builder = RecoveryHandlerChain.<Integer>builder();
        assertThrows(NullPointerException.class, () -> builder.register(null));
        assertThrows(NullPointerException.class, () -> builder.onRaise(null));
    }

    @Test void seededDifferentialReferenceModelCoversMasksAndDelegation() {
        java.util.Random random = new java.util.Random(0x0E5A2026L);
        for (int scenario = 0; scenario < 400; scenario++) {
            int depth = random.nextInt(64);
            int[] masks = new int[depth];
            boolean[] resolves = new boolean[depth];
            var trace = new ArrayList<Integer>();
            var builder = RecoveryHandlerChain.<Integer>builder();
            for (int i = 0; i < depth; i++) {
                masks[i] = 1 + random.nextInt(3);
                resolves[i] = random.nextBoolean();
                final int index = i;
                RecoveryHandlerChain.Handler<Integer> handler = (e, c) -> {
                    trace.add(index);
                    if (resolves[index]) c.resolve(index);
                    else c.next();
                };
                if (masks[i] == 1) builder.onThrow(handler);
                else if (masks[i] == 2) builder.onRaise(handler);
                else builder.register(handler);
            }
            var kind = random.nextBoolean()
                    ? RecoveryHandlerChain.FailureKind.THROW
                    : RecoveryHandlerChain.FailureKind.RAISE;
            int bit = kind == RecoveryHandlerChain.FailureKind.THROW ? 1 : 2;
            var expectedTrace = new ArrayList<Integer>();
            Integer expectedResult = null;
            for (int i = depth - 1; i >= 0; i--) {
                if ((masks[i] & bit) == 0) continue;
                expectedTrace.add(i);
                if (resolves[i]) { expectedResult = i; break; }
            }
            Throwable pending = new RuntimeException("scenario-" + scenario);
            var result = builder.freeze().dispatch(kind, pending);
            assertEquals(expectedTrace, trace, "scenario=" + scenario);
            if (expectedResult == null) {
                var unhandled = assertInstanceOf(
                        RecoveryHandlerChain.Unhandled.class, result);
                assertSame(pending, unhandled.error());
                assertEquals(kind, unhandled.kind());
            } else {
                assertEquals(new RecoveryHandlerChain.Recovered<>(expectedResult), result);
            }
        }
    }

    @Test void longDelegationChainIsIterative() {
        var builder = RecoveryHandlerChain.<Integer>builder();
        builder.register((e, c) -> c.resolve(123));
        for (int i = 1; i < RecoveryHandlerChain.MAX_HANDLERS; i++) {
            builder.register((e, c) -> c.next());
        }
        assertEquals(new RecoveryHandlerChain.Recovered<>(123),
                builder.freeze().dispatch(RecoveryHandlerChain.FailureKind.RAISE, new Exception()));
    }
}
