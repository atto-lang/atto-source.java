package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Deterministic shutdown barriers for mailbox-backed SourceTasks.
 * An actor STOP closes user and ordinary continuation admission without
 * throwing away the existing scheduler's lexical cancellation work.
 */
@Timeout(20)
final class ActorStopCleanupAdmissionTest {
    @Test
    void stoppedActorRunsSuspendedCleanupThroughAsyncStageBeforeReclaim()
            throws Exception {
        var mutex = OresMutex.shared(new int[] { 5 });
        var never = new OresFuture<Integer>();
        var asyncClose = new OresFuture<Integer>();
        var independentCloseReceipt = new OresFuture<Integer>();
        var waiting = new CountDownLatch(1);
        var cleanupEntered = new CountDownLatch(1);
        var cleanupExited = new CountDownLatch(1);
        var cleanupFinished = new AtomicInteger();
        var ordinaryExecuted = new AtomicInteger();
        var genericContinuationExecuted = new AtomicInteger();
        var genericProducer = new OresFuture<Integer>();
        var unexpectedFailure = new AtomicReference<Throwable>();

        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                if (!"start".equals(message)) {
                    ordinaryExecuted.incrementAndGet();
                    return;
                }
                ActorRuntime.ContinuationTarget target =
                        context.runtime().captureCurrentContinuationTarget();
                context.runtime().enqueueOnCompletion(genericProducer, target,
                        (value, failure) -> genericContinuationExecuted.incrementAndGet());
                context.runtime().startActorTask(
                        new OresScheduler.CleanupAwareTask<Void>() {
                            int state;
                            OresMutex.Guard<int[]> guard;

                            @Override public OresScheduler.Step<Void> resume(
                                    OresScheduler.Resume resume) {
                                if (state++ == 0) {
                                    guard = mutex.lock();
                                    waiting.countDown();
                                    return OresScheduler.await(never);
                                }
                                if (state == 2) {
                                    try {
                                        assertTrue(OresScheduler.cancellationCleanupTurn());
                                        // Real SourceTask defer lowering invokes these
                                        // checks even after the actor is logically stopped.
                                        context.runtime().schedulerSafepoint();
                                        assertInstanceOf(CancellationException.class, resume.failure());
                                        // A cleanup continuation cannot start new ordinary
                                        // tasks through the sealed actor scheduler.
                                        assertThrows(RejectedExecutionException.class,
                                                () -> context.runtime().startActorTask(
                                                        ignored -> OresScheduler.done(null)));
                                    } catch (Throwable failure) {
                                        unexpectedFailure.set(failure);
                                    }
                                    // An async iterator/resource close may
                                    // mint its own bounded runtime-owned Future
                                    // during cleanup; it cannot become orphaned
                                    // just because STOP has already happened.
                                    assertSame(independentCloseReceipt,
                                            context.runtime().ownCurrentActorFuture(
                                                    independentCloseReceipt));
                                    cleanupEntered.countDown();
                                    return OresScheduler.await(asyncClose);
                                }
                                try {
                                    assertTrue(OresScheduler.cancellationCleanupTurn());
                                    assertEquals(Integer.valueOf(8), resume.value());
                                } catch (Throwable failure) {
                                    unexpectedFailure.set(failure);
                                } finally {
                                    guard.release();
                                    cleanupFinished.incrementAndGet();
                                    cleanupExited.countDown();
                                }
                                return OresScheduler.done(null);
                            }
                        });
            });

            actor.send("start");
            assertTrue(waiting.await(3, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS),
                    "SourceTask cleanup must run despite STOP sealing ordinary mail");
            assertFalse(actor.done().isDone(), "actor DONE is a physical cleanup barrier");
            assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS));
            assertTrue(mutex.tryLock().isEmpty(), "mutex is still physically held");
            assertFalse(mutex.isPoisoned(), "explicit lexical cleanup owns this guard");
            assertThrows(ActorRuntime.ActorTerminatedException.class,
                    () -> actor.send("ordinary"));
            assertEquals(0, genericContinuationExecuted.get(),
                    "ordinary Future callbacks cannot reenter a stopped actor");
            assertFalse(never.isCancelled(), "external awaited producer remains borrowed");

            asyncClose.completeFromRuntime(8);
            assertTrue(cleanupExited.await(3, TimeUnit.SECONDS));
            assertFalse(actor.done().isDone(),
                    "actor DONE must also join cleanup-owned async-close receipts");
            independentCloseReceipt.completeFromRuntime(1);
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(1, cleanupFinished.get(), "defer executes exactly once");
            assertNull(unexpectedFailure.get(), () -> String.valueOf(unexpectedFailure.get()));
            assertEquals(0, ordinaryExecuted.get());
            assertEquals(0, genericContinuationExecuted.get());
            assertFalse(mutex.isPoisoned());
            var acquired = mutex.tryLock().orElseThrow();
            acquired.release();
            ExecutionException cancelled = assertThrows(ExecutionException.class,
                    () -> actor.done().get(3, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                    cancelled.getCause());
        }
    }

    @Test
    void repeatedStopAndLateProducerCannotReplayMultipleAsyncCleanupStages()
            throws Exception {
        // A single cancellation must enter the existing SourceTask frame
        // exactly once, then keep cleanup authority through two real awaits.
        // Neither a second STOP nor a late upstream completion may resurrect
        // the ordinary actor mailbox or replay a previously retired turn.
        OresFuture<Integer> upstream = new OresFuture<>();
        OresFuture<Integer> closeFirst = new OresFuture<>();
        OresFuture<Integer> closeSecond = new OresFuture<>();
        CountDownLatch awaitingUpstream = new CountDownLatch(1);
        CountDownLatch firstCloseEntered = new CountDownLatch(1);
        CountDownLatch secondCloseEntered = new CountDownLatch(1);
        AtomicInteger initialTurns = new AtomicInteger();
        AtomicInteger completedCleanup = new AtomicInteger();
        AtomicInteger unexpectedMessages = new AtomicInteger();
        AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                if (!"start".equals(message)) {
                    unexpectedMessages.incrementAndGet();
                    return;
                }
                context.runtime().startActorTask(
                        new OresScheduler.CleanupAwareTask<Void>() {
                            int stage;
                            @Override public OresScheduler.Step<Void> resume(
                                    OresScheduler.Resume resume) {
                                switch (stage++) {
                                    case 0:
                                        initialTurns.incrementAndGet();
                                        awaitingUpstream.countDown();
                                        return OresScheduler.await(upstream);
                                    case 1:
                                        try {
                                            assertTrue(OresScheduler.cancellationCleanupTurn());
                                            assertInstanceOf(CancellationException.class,
                                                    resume.failure());
                                            context.runtime().schedulerSafepoint();
                                        } catch (Throwable failure) {
                                            unexpectedFailure.compareAndSet(null, failure);
                                        }
                                        firstCloseEntered.countDown();
                                        return OresScheduler.await(closeFirst);
                                    case 2:
                                        try {
                                            assertTrue(OresScheduler.cancellationCleanupTurn(),
                                                    "authority must persist after first cleanup await");
                                            assertEquals(Integer.valueOf(11), resume.value());
                                            assertNull(resume.failure());
                                            context.runtime().schedulerSafepoint();
                                        } catch (Throwable failure) {
                                            unexpectedFailure.compareAndSet(null, failure);
                                        }
                                        secondCloseEntered.countDown();
                                        return OresScheduler.await(closeSecond);
                                    case 3:
                                        try {
                                            assertTrue(OresScheduler.cancellationCleanupTurn(),
                                                    "authority must persist after second cleanup await");
                                            assertEquals(Integer.valueOf(22), resume.value());
                                            assertNull(resume.failure());
                                        } catch (Throwable failure) {
                                            unexpectedFailure.compareAndSet(null, failure);
                                        }
                                        completedCleanup.incrementAndGet();
                                        return OresScheduler.done(null);
                                    default:
                                        unexpectedFailure.compareAndSet(null,
                                                new AssertionError("SourceTask replayed"));
                                        return OresScheduler.done(null);
                                }
                            }
                        });
            });
            actor.send("start");
            assertTrue(awaitingUpstream.await(3, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            assertTrue(firstCloseEntered.await(3, TimeUnit.SECONDS));
            assertFalse(actor.done().isDone());

            // A repeat request is harmless even while asynchronous cleanup
            // is suspended. Do not rely on cancel()'s boolean for idempotence.
            actor.cancel();
            assertTrue(upstream.completeFromRuntime(99));
            assertEquals(0, upstream.pendingRuntimeWaiterCount(),
                    "original await subscriber must be detached after STOP");
            assertFalse(actor.done().isDone());

            closeFirst.completeFromRuntime(11);
            assertTrue(secondCloseEntered.await(3, TimeUnit.SECONDS));
            actor.cancel();
            assertFalse(actor.done().isDone(),
                    "second asynchronous close remains a physical barrier");

            closeSecond.completeFromRuntime(22);
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(1, initialTurns.get());
            assertEquals(1, completedCleanup.get());
            assertEquals(0, unexpectedMessages.get());
            assertNull(unexpectedFailure.get(),
                    () -> String.valueOf(unexpectedFailure.get()));
            assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                    assertThrows(ExecutionException.class,
                            () -> actor.done().get(3, TimeUnit.SECONDS)).getCause());
        }
    }

    @Test
    void actorCrashRetainsOriginalFailureUntilSuspendedCleanupReleasesGuard()
            throws Exception {
        var mutex = OresMutex.shared(new int[] { 0 });
        var awaited = new OresFuture<Integer>();
        var closeReceipt = new OresFuture<Integer>();
        var waiting = new CountDownLatch(1);
        var cleanupEntered = new CountDownLatch(1);
        var released = new AtomicInteger();
        var crash = new IllegalStateException("original actor failure");

        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                if ("crash".equals(message)) throw crash;
                context.runtime().startActorTask(
                        new OresScheduler.CleanupAwareTask<Void>() {
                            int pc;
                            OresMutex.Guard<int[]> guard;
                            @Override public OresScheduler.Step<Void> resume(
                                    OresScheduler.Resume resume) {
                                if (pc++ == 0) {
                                    guard = mutex.lock();
                                    waiting.countDown();
                                    return OresScheduler.await(awaited);
                                }
                                if (pc == 2) {
                                    assertTrue(OresScheduler.cancellationCleanupTurn());
                                    context.runtime().schedulerSafepoint();
                                    cleanupEntered.countDown();
                                    return OresScheduler.await(closeReceipt);
                                }
                                guard.release();
                                released.incrementAndGet();
                                return OresScheduler.done(null);
                            }
                        });
            });
            actor.send("start");
            assertTrue(waiting.await(3, TimeUnit.SECONDS));
            actor.send("crash");
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS));
            assertFalse(actor.done().isDone(),
                    "failed actor retains its cleanup-owned resources");
            assertTrue(mutex.tryLock().isEmpty());

            closeReceipt.completeFromRuntime(4);
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(1, released.get());
            assertSame(crash, actor.failure().orElseThrow(),
                    "cleanup must not overwrite the original actor failure");
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> actor.done().get(3, TimeUnit.SECONDS));
            assertSame(crash, failure.getCause());
            var verifier = mutex.tryLock().orElseThrow();
            verifier.release();
        }
    }

    @Test
    void actorDoneWaitsForBlockedTerminalSubscriberAfterGuestCleanup()
            throws Exception {
        CountDownLatch awaitingProducer = new CountDownLatch(1);
        CountDownLatch subscriberEntered = new CountDownLatch(1);
        CountDownLatch releaseSubscriber = new CountDownLatch(1);
        AtomicInteger cleanupExecutions = new AtomicInteger();
        AtomicInteger subscriberExecutions = new AtomicInteger();
        AtomicReference<Throwable> subscriberFailure = new AtomicReference<>();
        OresFuture<Integer> producer = new OresFuture<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                OresFuture<Void> task = context.runtime().startActorTask(
                        new OresScheduler.CleanupAwareTask<Void>() {
                            int stage;
                            @Override public OresScheduler.Step<Void> resume(
                                    OresScheduler.Resume resume) {
                                if (stage++ == 0) {
                                    awaitingProducer.countDown();
                                    return OresScheduler.await(producer);
                                }
                                assertTrue(OresScheduler.cancellationCleanupTurn());
                                assertInstanceOf(CancellationException.class,
                                        resume.failure());
                                cleanupExecutions.incrementAndGet();
                                return OresScheduler.done(null);
                            }
                        });
                task.whenCompleteRuntime((ignored, failure) -> {
                    subscriberExecutions.incrementAndGet();
                    subscriberEntered.countDown();
                    try {
                        if (!releaseSubscriber.await(5, TimeUnit.SECONDS)) {
                            subscriberFailure.compareAndSet(null,
                                    new AssertionError("terminal subscriber timed out"));
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        subscriberFailure.compareAndSet(null, interrupted);
                    }
                });
            });
            actor.send("start");
            assertTrue(awaitingProducer.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(actor.cancel());
                assertTrue(subscriberEntered.await(3, TimeUnit.SECONDS),
                        "terminal notification must be physically in flight");
                assertFalse(actor.done().isDone(),
                        "terminal Future settlement does not retire subscriber execution");
                assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS));
                assertEquals(1, cleanupExecutions.get(),
                        "guest lexical cleanup must finish before terminal notification");
            } finally {
                releaseSubscriber.countDown();
            }
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS),
                    "actor DONE must join the terminal subscriber drain");
            assertEquals(1, subscriberExecutions.get());
            assertNull(subscriberFailure.get(),
                    () -> String.valueOf(subscriberFailure.get()));
            assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                    assertThrows(ExecutionException.class,
                            () -> actor.done().get(3, TimeUnit.SECONDS)).getCause());
        }
    }

    @Test
    void parentStopRequestsChildCancellationBeforeBlockedParentRevoker()
            throws Exception {
        CountDownLatch childCreated = new CountDownLatch(1);
        CountDownLatch revokerEntered = new CountDownLatch(1);
        CountDownLatch releaseParentRevoker = new CountDownLatch(1);
        AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();
        AtomicReference<Throwable> cancellationFailure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.<String>spawnShared(() -> (message, context) -> {
                var child = context.runtime().<String>spawnShared(() ->
                        (childMessage, childContext) -> { });
                childRef.set(child);
                context.runtime().ownCurrentActorFuture(new OresFuture<Void>(() -> {
                    revokerEntered.countDown();
                    try {
                        if (!releaseParentRevoker.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test parent revoker timeout");
                        }
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interruption);
                    }
                }));
                childCreated.countDown();
            });
            parent.send("start");
            assertTrue(childCreated.await(3, TimeUnit.SECONDS));
            Thread cancelling = Thread.ofVirtual().start(() -> {
                try {
                    parent.cancel();
                } catch (Throwable failure) {
                    cancellationFailure.set(failure);
                }
            });
            try {
                assertTrue(revokerEntered.await(3, TimeUnit.SECONDS));
                assertFalse(parent.done().isDone(),
                        "parent's own physical cleanup cannot be advertised complete");
                assertTrue(childRef.get().awaitTermination(3, TimeUnit.SECONDS),
                        "parent STOP must propagate to children before a blocking parent revoker");
                assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                        childRef.get().failure().orElseThrow());
                assertFalse(parent.done().isDone(),
                        "child termination cannot release the parent's outstanding resource");
            } finally {
                releaseParentRevoker.countDown();
            }
            cancelling.join(TimeUnit.SECONDS.toMillis(3));
            assertFalse(cancelling.isAlive(), "STOP must finish after revoker release");
            assertNull(cancellationFailure.get(),
                    () -> String.valueOf(cancellationFailure.get()));
            assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                    assertThrows(ExecutionException.class,
                            () -> parent.done().get(3, TimeUnit.SECONDS)).getCause());
        }
    }

    @Test
    void actorStopJoinsExistingFutureRevokerBeforePublishingDone()
            throws Exception {
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch revokerEntered = new CountDownLatch(1);
        CountDownLatch releaseRevoker = new CountDownLatch(1);
        AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                OresFuture<Void> leasedOperation = new OresFuture<>(() -> {
                    revokerEntered.countDown();
                    try {
                        if (!releaseRevoker.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "test held the operation revoker too long");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                });
                context.runtime().ownCurrentActorFuture(leasedOperation);
                admitted.countDown();
            });
            actor.send("own-resource");
            assertTrue(admitted.await(3, TimeUnit.SECONDS));
            Thread cancellation = Thread.ofVirtual().start(() -> {
                try {
                    actor.cancel();
                } catch (Throwable failure) {
                    cancelFailure.set(failure);
                }
            });
            try {
                assertTrue(revokerEntered.await(3, TimeUnit.SECONDS));
                assertFalse(actor.done().isDone(),
                        "logical STOP must not reclaim ahead of owned revoker");
                assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS));
            } finally {
                releaseRevoker.countDown();
            }
            cancellation.join(TimeUnit.SECONDS.toMillis(3));
            assertFalse(cancellation.isAlive());
            assertNull(cancelFailure.get(), () -> String.valueOf(cancelFailure.get()));
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            ExecutionException failed = assertThrows(ExecutionException.class,
                    () -> actor.done().get(3, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                    failed.getCause());
        }
    }

    @Test
    void stopBeforeQueuedSourceStartDoesNotRunDiscardedInitialTask()
            throws Exception {
        var admitted = new CountDownLatch(1);
        var leaveActorTurn = new CountDownLatch(1);
        var startedGuestTask = new AtomicInteger();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                context.runtime().startActorTask(
                        new OresScheduler.CleanupAwareTask<Void>() {
                            @Override public OresScheduler.Step<Void> resume(
                                    OresScheduler.Resume resume) {
                                startedGuestTask.incrementAndGet();
                                return OresScheduler.done(null);
                            }
                        });
                admitted.countDown();
                if (!leaveActorTurn.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test turn remained blocked");
                }
            });
            actor.send("start");
            assertTrue(admitted.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(actor.cancel());
            } finally {
                leaveActorTurn.countDown();
            }
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS),
                    "STOP must drain a now-terminal queued scheduler command");
            assertEquals(0, startedGuestTask.get(),
                    "pre-start cancellation has no guest lexical frame to resume");

            var successorProcessed = new CountDownLatch(1);
            var successor = runtime.<String>spawnShared(() ->
                    (message, context) -> successorProcessed.countDown());
            assertNotEquals(actor.id(), successor.id(), "new actor has a distinct incarnation");
            successor.send("work");
            assertTrue(successorProcessed.await(2, TimeUnit.SECONDS));
            successor.stop();
        }
    }
}
