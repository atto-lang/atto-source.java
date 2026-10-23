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
