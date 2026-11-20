package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HOST-ONLY recovery/provenance tests, not source actor hook or SOE certification.
 * Real carrier StackOverflowError must not be induced in a shared JVM test.
 */
final class ActorFailureBoundaryIsolationTest {
    private static final CodeImageIdentity IMAGE =
            CodeImageIdentity.issue("actor-failure-boundary-test", 1);

    private static ExecutionContext actor(long execution, long owner, long generation) {
        return ExecutionContext.actor(19, execution, 7, owner, generation, 0, 0);
    }

    private static GuestFailure issue(RecoveryHandlerChain.FailureKind kind,
            Throwable error, ExecutionContext context) {
        return GuestFailure.issueFromCompiler(kind, error, context, IMAGE, 1);
    }

    @Test
    void actorRaiseIsRecoveredOnlyByItsOwnLexicalRecoveryBoundary() {
        ExecutionContext first = actor(11, 101, 1);
        ExecutionContext second = actor(12, 102, 1);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        var firstRecovery = RecoveryHandlerChain.<Integer>builder(first, IMAGE)
                .onThrow((e, cursor) -> fail("catch/on_throw must not consume raise"))
                .onRaise((e, cursor) -> {
                    firstCalls.incrementAndGet();
                    cursor.resolve(41);
                }).freeze();
        var secondRecovery = RecoveryHandlerChain.<Integer>builder(second, IMAGE)
                .onRaise((e, cursor) -> {
                    secondCalls.incrementAndGet();
                    cursor.resolve(42);
                }).freeze();

        GuestFailure secondRaise = issue(
                RecoveryHandlerChain.FailureKind.RAISE, new Exception("second actor"), second);
        var foreign = assertInstanceOf(RecoveryHandlerChain.Unhandled.class,
                firstRecovery.dispatch(secondRaise));
        assertSame(secondRaise, foreign.pendingFailure());
        assertEquals(0, firstCalls.get());
        assertEquals(new RecoveryHandlerChain.Recovered<>(42),
                secondRecovery.dispatch(secondRaise));
        assertEquals(1, secondCalls.get());

        assertEquals(new RecoveryHandlerChain.Recovered<>(41),
                firstRecovery.dispatch(issue(
                        RecoveryHandlerChain.FailureKind.RAISE,
                        new Exception("first actor"), first)));
        assertEquals(1, firstCalls.get());
        assertEquals(1, secondCalls.get());
    }

    @Test
    void throwHandlerAndRaiseRecoveryNeverCrossChannels() {
        ExecutionContext context = actor(13, 103, 2);
        AtomicInteger throwsSeen = new AtomicInteger();
        AtomicInteger raisesSeen = new AtomicInteger();
        var chain = RecoveryHandlerChain.<Integer>builder(context, IMAGE)
                .onThrow((e, cursor) -> {
                    throwsSeen.incrementAndGet();
                    cursor.resolve(10);
                })
                .onRaise((e, cursor) -> {
                    raisesSeen.incrementAndGet();
                    cursor.resolve(20);
                })
                .freeze();
        assertEquals(new RecoveryHandlerChain.Recovered<>(10),
                chain.dispatch(issue(RecoveryHandlerChain.FailureKind.THROW,
                        new Exception("ordinary throw"), context)));
        assertEquals(new RecoveryHandlerChain.Recovered<>(20),
                chain.dispatch(issue(RecoveryHandlerChain.FailureKind.RAISE,
                        new Exception("raised"), context)));
        assertEquals(1, throwsSeen.get());
        assertEquals(1, raisesSeen.get());
    }

    @Test
    void hostStackOverflowCannotBecomeRecoverableActorThrowOrRaise() {
        ExecutionContext context = actor(14, 104, 1);
        AtomicInteger invoked = new AtomicInteger();
        var chain = RecoveryHandlerChain.<Integer>builder(context, IMAGE)
                .onThrow((e, cursor) -> {
                    invoked.incrementAndGet();
                    cursor.resolve(1);
                })
                .onRaise((e, cursor) -> {
                    invoked.incrementAndGet();
                    cursor.resolve(2);
                }).freeze();
        // Constructing this Error does NOT overflow a JVM or guest stack.
        // It confirms that a host VM Error is denied at the recovery boundary.
        for (var kind : new RecoveryHandlerChain.FailureKind[] {
                RecoveryHandlerChain.FailureKind.THROW,
                RecoveryHandlerChain.FailureKind.RAISE }) {
            StackOverflowError hostFatal = new StackOverflowError("synthetic VM marker");
            GuestFailure signal = issue(kind, hostFatal, context);
            var unhandled = assertInstanceOf(RecoveryHandlerChain.Unhandled.class,
                    chain.dispatch(signal));
            assertSame(signal, unhandled.pendingFailure());
            assertSame(hostFatal, unhandled.error());
            assertEquals(kind, unhandled.kind());
        }
        assertEquals(0, invoked.get(), "neither recovery nor on_throw can swallow host fatal");
        assertThrows(IllegalArgumentException.class,
                () -> issue(RecoveryHandlerChain.FailureKind.PANIC,
                        new Exception("panic"), context),
                "PANIC requires supervisor CONTROL, not a local recovery envelope");
    }

    @Test
    void generationAndContinuationFenceStaleRecoveryCapabilities() {
        // The same actor slot and execution id are not sufficient after
        // restart, and a suspended continuation is not a portable credential.
        ExecutionContext owner = actor(21, 109, 1);
        ExecutionContext restarted = actor(21, 109, 2);
        ExecutionContext newContinuation =
                ExecutionContext.actor(19, 21, 7, 109, 1, 0, 9);
        AtomicInteger visited = new AtomicInteger();
        var ownerRecovery = RecoveryHandlerChain.<Integer>builder(owner, IMAGE)
                .onRaise((e, cursor) -> {
                    visited.incrementAndGet();
                    cursor.resolve(101);
                }).freeze();
        var restartedRecovery = RecoveryHandlerChain.<Integer>builder(restarted, IMAGE)
                .onRaise((e, cursor) -> {
                    visited.incrementAndGet();
                    cursor.resolve(202);
                }).freeze();
        var continuationRecovery =
                RecoveryHandlerChain.<Integer>builder(newContinuation, IMAGE)
                .onRaise((e, cursor) -> {
                    visited.incrementAndGet();
                    cursor.resolve(303);
                }).freeze();

        GuestFailure pending = issue(
                RecoveryHandlerChain.FailureKind.RAISE, new Exception("pending"), owner);
        assertSame(pending, assertInstanceOf(RecoveryHandlerChain.Unhandled.class,
                restartedRecovery.dispatch(pending)).pendingFailure());
        assertSame(pending, assertInstanceOf(RecoveryHandlerChain.Unhandled.class,
                continuationRecovery.dispatch(pending)).pendingFailure());
        assertEquals(0, visited.get(), "neither foreign generation nor continuation can recover");
        assertEquals(new RecoveryHandlerChain.Recovered<>(101),
                ownerRecovery.dispatch(pending));
        assertEquals(1, visited.get());
        assertThrows(IllegalStateException.class, () -> ownerRecovery.dispatch(pending),
                "resolved failure must not be replayed through its former owner");
    }

    @Test
    void fatalExceptionFromRaiseCallbackCannotBeReclassifiedAsRecovery() {
        ExecutionContext owner = actor(22, 110, 1);
        AtomicInteger callbacks = new AtomicInteger();
        var recovery = RecoveryHandlerChain.<Integer>builder(owner, IMAGE)
                .onRaise((e, cursor) -> {
                    callbacks.incrementAndGet();
                    // Deliberately construct/throw this marker: this does NOT
                    // overflow a real JVM carrier stack.
                    throw new StackOverflowError("synthetic callback fatal");
                }).freeze();
        GuestFailure pending = issue(
                RecoveryHandlerChain.FailureKind.RAISE, new Exception("pending"), owner);
        assertThrows(StackOverflowError.class, () -> recovery.dispatch(pending));
        assertEquals(1, callbacks.get());
        assertThrows(IllegalStateException.class, () -> recovery.dispatch(pending),
                "fatal callback must revoke the original recovery capability");
    }
}
