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

    @Test void directlyReentrantHandlerDoesNotRecursivelyInvokeItself() {
        @SuppressWarnings("unchecked")
        RecoveryHandlerChain<Integer>[] holder = (RecoveryHandlerChain<Integer>[])
                new RecoveryHandlerChain<?>[1];
        var visited = new java.util.concurrent.atomic.AtomicInteger();
        var leaked = new ArrayList<RecoveryHandlerChain.Continuation<Integer>>();
        holder[0] = RecoveryHandlerChain.<Integer>builder()
                .register((err, c) -> {
                    visited.incrementAndGet();
                    leaked.add(c);
                    holder[0].dispatch(RecoveryHandlerChain.FailureKind.RAISE,
                            new Exception("nested failure"));
                    c.resolve(7);
                }).freeze();
        Throwable original = new Exception("original");
        Error abort = assertThrows(Error.class,
                () -> holder[0].dispatch(RecoveryHandlerChain.FailureKind.RAISE, original));
        assertTrue(abort.getMessage().contains("reentrant"));
        assertEquals("nested failure", abort.getCause().getMessage());
        assertEquals(1, visited.get());
        assertThrows(IllegalStateException.class, () -> leaked.getFirst().resolve(5));
        // The guard must be removed on unwind, not retained on a pooled carrier.
        assertThrows(Error.class,
                () -> holder[0].dispatch(RecoveryHandlerChain.FailureKind.RAISE, original));
        assertEquals(2, visited.get());
    }

    @Test void indirectCycleAcrossRecoveryChainsIsAlsoDenied() {
        @SuppressWarnings("unchecked")
        RecoveryHandlerChain<Integer>[] holders = (RecoveryHandlerChain<Integer>[])
                new RecoveryHandlerChain<?>[2];
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        holders[0] = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> {
                    calls.incrementAndGet();
                    holders[1].dispatch(RecoveryHandlerChain.FailureKind.THROW,
                            new Exception("B pending"));
                    c.resolve(1);
                }).freeze();
        holders[1] = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> {
                    calls.incrementAndGet();
                    holders[0].dispatch(RecoveryHandlerChain.FailureKind.THROW,
                            new Exception("A reentered"));
                    c.resolve(2);
                }).freeze();
        Error abort = assertThrows(Error.class,
                () -> holders[0].dispatch(RecoveryHandlerChain.FailureKind.THROW,
                        new Exception("outer")));
        assertTrue(abort.getMessage().contains("reentrant"));
        assertEquals("A reentered", abort.getCause().getMessage());
        assertEquals(2, calls.get());
    }

    @Test void suppressedReentryAbortCannotBeConvertedToSuccess() {
        @SuppressWarnings("unchecked")
        RecoveryHandlerChain<Integer>[] holder = (RecoveryHandlerChain<Integer>[])
                new RecoveryHandlerChain<?>[1];
        holder[0] = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> {
                    try {
                        holder[0].dispatch(RecoveryHandlerChain.FailureKind.THROW,
                                new Exception("inner"));
                    } catch (Error ignored) {
                        // A foreign host callback may catch Throwable/Error,
                        // but it cannot reset the dispatch scope's poison bit.
                    }
                    c.resolve(8);
                }).freeze();
        Error abort = assertThrows(Error.class,
                () -> holder[0].dispatch(RecoveryHandlerChain.FailureKind.THROW,
                        new Exception("outer")));
        assertEquals("inner", abort.getCause().getMessage());
    }

    @Test void distinctNestedRecoveryChainsArePermitted() {
        var trace = new ArrayList<String>();
        var inner = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> { trace.add("inner"); c.resolve(3); }).freeze();
        var outer = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> {
                    trace.add("outer");
                    assertEquals(new RecoveryHandlerChain.Recovered<>(3),
                            inner.dispatch(RecoveryHandlerChain.FailureKind.RAISE,
                                    new Exception("inner pending")));
                    c.resolve(5);
                }).freeze();
        assertEquals(new RecoveryHandlerChain.Recovered<>(5),
                outer.dispatch(RecoveryHandlerChain.FailureKind.RAISE,
                        new Exception("outer pending")));
        assertEquals(List.of("outer", "inner"), trace);
    }

    @Test void tooManyDistinctNestedChainsFailClosed() {
        @SuppressWarnings("unchecked")
        RecoveryHandlerChain<Integer>[] chains = (RecoveryHandlerChain<Integer>[])
                new RecoveryHandlerChain<?>[65];
        for (int i = chains.length - 1; i >= 0; i--) {
            final int index = i;
            chains[index] = RecoveryHandlerChain.<Integer>builder()
                    .register((e, c) -> {
                        if (index + 1 < chains.length) {
                            chains[index + 1].dispatch(RecoveryHandlerChain.FailureKind.RAISE,
                                    new Exception("depth " + (index + 1)));
                        }
                        c.resolve(index);
                    }).freeze();
        }
        Error abort = assertThrows(Error.class,
                () -> chains[0].dispatch(RecoveryHandlerChain.FailureKind.RAISE,
                        new Exception("root")));
        assertTrue(abort.getMessage().contains("nested"));
        assertEquals("depth 64", abort.getCause().getMessage());
    }

    @Test void unauthenticatedDispatcherStaysPackagePrivate() {
        assertFalse(java.lang.reflect.Modifier.isPublic(RecoveryHandlerChain.class.getModifiers()),
                "host prototype must not become a public guest or Java API before trusted admission");
    }

    @Test void alternatingForeignCauseCannotHideCancellationOnFirstRead() {
        var causeReads = new java.util.concurrent.atomic.AtomicInteger();
        Throwable hiddenCancellation = new java.util.concurrent.CancellationException("hidden");
        Exception foreign = new Exception("stateful cause") {
            @Override public synchronized Throwable getCause() {
                return causeReads.incrementAndGet() % 2 == 1 ? null : hiddenCancellation;
            }
        };
        var invoked = new java.util.concurrent.atomic.AtomicInteger();
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> { invoked.incrementAndGet(); c.resolve(1); }).freeze();
        var result = assertInstanceOf(RecoveryHandlerChain.Unhandled.class,
                chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, foreign));
        assertSame(foreign, result.error());
        assertEquals(0, invoked.get());
        assertEquals(0, causeReads.get(), "untrusted virtual accessor must not run");
    }

    @Test void nestedForeignCauseCannotConcealSecurityDenial() {
        var causeReads = new java.util.concurrent.atomic.AtomicInteger();
        Exception wrappedForeign = new Exception("foreign child") {
            @Override public synchronized Throwable getCause() {
                causeReads.incrementAndGet();
                return new SecurityException("revoked");
            }
        };
        Throwable pending = new RuntimeException("ordinary wrapper", wrappedForeign);
        var invoked = new java.util.concurrent.atomic.AtomicInteger();
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> { invoked.incrementAndGet(); c.resolve(3); }).freeze();
        for (var kind : new RecoveryHandlerChain.FailureKind[] {
                RecoveryHandlerChain.FailureKind.THROW,
                RecoveryHandlerChain.FailureKind.RAISE }) {
            var outcome = assertInstanceOf(RecoveryHandlerChain.Unhandled.class,
                    chain.dispatch(kind, pending));
            assertSame(pending, outcome.error());
            assertEquals(kind, outcome.kind());
        }
        assertEquals(0, invoked.get());
        assertEquals(0, causeReads.get(), "untrusted nested virtual accessor must not run");
    }

    @Test void regularThrowableCauseTraversalStillAdmitsOrdinaryErrors() {
        Throwable pending = new RuntimeException("outer", new IllegalArgumentException("guest ordinary"));
        var chain = RecoveryHandlerChain.<Integer>builder()
                .onRaise((e, c) -> c.resolve(17)).freeze();
        assertEquals(new RecoveryHandlerChain.Recovered<>(17),
                chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, pending));
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
