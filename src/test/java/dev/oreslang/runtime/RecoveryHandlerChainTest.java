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

    @Test void doubleDecisionIsRejected() {
        var chain = RecoveryHandlerChain.<Integer>builder()
                .register((e, c) -> { c.next(); c.resolve(9); }).freeze();
        assertThrows(IllegalStateException.class,
                () -> chain.dispatch(RecoveryHandlerChain.FailureKind.RAISE, new Exception()));
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
