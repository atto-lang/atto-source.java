package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actor authority regression for adapting a physical SharedMutex GuardFuture
 * into the scheduler-owned OresFuture. Host completion callbacks are allowed
 * to enqueue only; actor guest work must execute on the receiving actor turn.
 */
@Timeout(15)
final class ActorMutexFutureAuthorityTest {

    @Test
    void mutexHandoffResumesWaitingActorOnlyThroughItsOwnMailboxTurn() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var ownerReady = new CountDownLatch(1);
        var waiterQueued = new CountDownLatch(1);
        var resumed = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var ownerGuard = new AtomicReference<OresMutex.Guard<int[]>>();
        var waitingGuard = new AtomicReference<OresMutex.Guard<int[]>>();
        var waitingDomain = new AtomicReference<Object>();
        var ownerDomain = new AtomicReference<Object>();
        var callbackDomain = new AtomicReference<Object>();
        var foreignCallbackObserved = new CountDownLatch(1);
        var waiterRef = new AtomicReference<ActorRuntime.ActorRef<String>>();

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 4, 32))) {
            var waiter = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    if ("queue".equals(message)) {
                        waitingDomain.set(ActorRuntime.currentExecutionDomain());
                        var stage = mutex.lockAsync();
                        // Raw CompletionStage callbacks are HOST work. They
                        // must neither inherit actor A's releasing execution
                        // authority nor run actor B's guest continuation.
                        stage.whenComplete((guard, completionError) -> {
                            try {
                                assertNull(completionError);
                                assertNull(ActorRuntime.currentActorRuntime(),
                                        "raw host callback inherited actor turn authority");
                                assertTrue(Thread.currentThread().getName()
                                                .startsWith("ores-shared-mutex-notifications-"),
                                        "handoff completion ran on an actor carrier");
                                assertThrows(OresMutex.WrongMutexDomainException.class,
                                        guard::release,
                                        "producer callback must not release B's guard");
                            } catch (Throwable problem) {
                                failure.compareAndSet(null, problem);
                            } finally {
                                foreignCallbackObserved.countDown();
                            }
                        });
                        OresFuture<OresMutex.Guard<int[]>> awaited = OresFuture.from(stage);
                        // Runtime-only plumbing; never execute B's guest logic here.
                        awaited.whenCompleteRuntime((guard, problem) -> {
                            callbackDomain.set(ActorRuntime.currentExecutionDomain());
                            if (problem != null) {
                                failure.compareAndSet(null, problem);
                                resumed.countDown();
                                return;
                            }
                            waitingGuard.set(guard);
                            waiterRef.get().send("resume");
                        });
                        waiterQueued.countDown();
                    } else if ("resume".equals(message)) {
                        assertEquals(waitingDomain.get(), ActorRuntime.currentExecutionDomain(),
                                "actor B resumed with another actor's execution authority");
                        assertNotEquals(ownerDomain.get(), ActorRuntime.currentExecutionDomain(),
                                "actor B guard work ran in releasing actor A's domain");
                        var guard = waitingGuard.getAndSet(null);
                        assertNotNull(guard);
                        guard.value()[0] = 71;
                        guard.release();
                        resumed.countDown();
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                    waiterQueued.countDown();
                    resumed.countDown();
                }
            });
            waiterRef.set(waiter);

            var owner = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    if ("lock".equals(message)) {
                        ownerDomain.set(ActorRuntime.currentExecutionDomain());
                        ownerGuard.set(mutex.lockAsync().join());
                        ownerReady.countDown();
                    } else if ("release".equals(message)) {
                        ownerGuard.getAndSet(null).release();
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                    ownerReady.countDown();
                    resumed.countDown();
                }
            });

            owner.send("lock");
            assertTrue(ownerReady.await(3, TimeUnit.SECONDS), "actor A never took mutex");
            assertNull(failure.get());
            waiter.send("queue");
            assertTrue(waiterQueued.await(3, TimeUnit.SECONDS), "actor B never queued mutex");
            assertNull(failure.get());
            owner.send("release");
            assertTrue(resumed.await(4, TimeUnit.SECONDS), "actor B did not resume from mailbox");
            assertTrue(foreignCallbackObserved.await(2, TimeUnit.SECONDS),
                    "raw CompletableFuture completion callback was never exercised");
            assertNull(failure.get(), String.valueOf(failure.get()));
            assertNotNull(callbackDomain.get(), "runtime completion plumbing was never notified");
            var observed = mutex.lockFor(Duration.ofSeconds(2)).orElseThrow();
            try {
                assertEquals(71, observed.value()[0]);
            } finally {
                observed.release();
            }
        }
    }

    @Test
    void actorCancellationDoesNotExecuteHostFutureCallbacksInsideActorTurn() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var held = mutex.lock();
        var callbackEntered = new CountDownLatch(1);
        var unblockCallback = new CountDownLatch(1);
        var actorFinished = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var cancelled = new AtomicBoolean();
        var pending = new AtomicReference<CompletableFuture<OresMutex.Guard<int[]>>>();

        try {
            try (ActorRuntime runtime = new ActorRuntime(
                    IsolatePolicy.developer(),
                    new ActorRuntime.DispatcherConfig(2, 4, 32))) {
                var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                    try {
                        var queued = mutex.lockAsync();
                        pending.set(queued);
                        queued.whenComplete((guard, problem) -> {
                            try {
                                assertNull(ActorRuntime.currentActorRuntime(),
                                        "raw Java completion inherited cancelling actor's authority");
                                assertTrue(Thread.currentThread().getName()
                                                .startsWith("ores-shared-mutex-notifications-"),
                                        "cancellation ran host dependent on actor carrier");
                                callbackEntered.countDown();
                                assertTrue(unblockCallback.await(4, TimeUnit.SECONDS));
                            } catch (Throwable callbackFailure) {
                                failure.compareAndSet(null, callbackFailure);
                                callbackEntered.countDown();
                            }
                        });
                        cancelled.set(queued.cancel(false));
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                    } finally {
                        actorFinished.countDown();
                    }
                });
                actor.send("cancel");
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS),
                        "cancelled Future never invoked its host completion callback");
                assertTrue(actorFinished.await(1, TimeUnit.SECONDS),
                        "actor's cancel() synchronously blocked inside a host callback");
                assertTrue(cancelled.get(), "queued cancellation was not accepted");
                assertNull(failure.get(), String.valueOf(failure.get()));
            } finally {
                unblockCallback.countDown();
            }
            assertNotNull(pending.get());
            long terminalDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!pending.get().isCancelled() && System.nanoTime() < terminalDeadline) {
                Thread.sleep(2);
            }
            assertTrue(pending.get().isCancelled(),
                    "accepted actor cancellation did not publish public Future terminal state");
            assertNull(failure.get(), String.valueOf(failure.get()));
        } finally {
            unblockCallback.countDown();
            held.release();
        }
        var next = mutex.lockFor(Duration.ofSeconds(1)).orElseThrow();
        next.release();
    }

    @Test
    void cancelledAwaitRestoresActorLockDomainBeforeNextMailboxTurn() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var held = mutex.lock();
        try {
            var queued = new CountDownLatch(1);
            var retried = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            var pending = new AtomicReference<OresFuture<OresMutex.Guard<int[]>>>();
            var original = new AtomicReference<CompletableFuture<OresMutex.Guard<int[]>>>();
            var originalDomain = new AtomicReference<Object>();
            var actorRef = new AtomicReference<ActorRuntime.ActorRef<String>>();
            var inTurn = new AtomicBoolean();

            try (ActorRuntime runtime = new ActorRuntime(
                    IsolatePolicy.developer(),
                    new ActorRuntime.DispatcherConfig(2, 4, 32))) {
                var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                    if (!inTurn.compareAndSet(false, true)) {
                        failure.compareAndSet(null,
                                new AssertionError("one actor executed overlapping mailbox turns"));
                        queued.countDown();
                        retried.countDown();
                        return;
                    }
                    try {
                        if ("queue".equals(message)) {
                            originalDomain.set(ActorRuntime.currentExecutionDomain());
                            CompletableFuture<OresMutex.Guard<int[]>> producer =
                                    mutex.lockAsync();
                            original.set(producer);
                            OresFuture<OresMutex.Guard<int[]>> awaited =
                                    OresFuture.from(producer);
                            pending.set(awaited);
                            awaited.whenCompleteRuntime((guard, problem) -> {
                                // Runtime-only callback may execute on the host
                                // cancelling thread. It never accesses the guard.
                                if (!(problem instanceof CancellationException)) {
                                    failure.compareAndSet(null, new AssertionError(
                                            "expected cancellation, not " + problem));
                                }
                                try {
                                    actorRef.get().send("retry");
                                } catch (Throwable callbackFailure) {
                                    failure.compareAndSet(null, callbackFailure);
                                    retried.countDown();
                                }
                            });
                            queued.countDown();
                        } else if ("retry".equals(message)) {
                            assertEquals(originalDomain.get(),
                                    ActorRuntime.currentExecutionDomain(),
                                    "cancellation must preserve actor identity");
                            var second = mutex.lockAsync();
                            assertTrue(second.cancel(false),
                                    "cancelled first waiter must free its domain reservation");
                            retried.countDown();
                        }
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                        queued.countDown();
                        retried.countDown();
                    } finally {
                        inTurn.set(false);
                    }
                });
                actorRef.set(actor);
                actor.send("queue");
                assertTrue(queued.await(3, TimeUnit.SECONDS), "actor never queued mutex");
                assertNull(failure.get(), String.valueOf(failure.get()));
                assertNotNull(pending.get());
                assertTrue(pending.get().cancel(false), "queued wait must accept cancellation");
                assertTrue(original.get().isCancelled(), "producer did not cancel");
                assertTrue(retried.await(3, TimeUnit.SECONDS),
                        "actor did not process retry after cancellation");
                assertNull(failure.get(), String.valueOf(failure.get()));
            }
        } finally {
            held.release();
        }

        var guard = mutex.lockFor(Duration.ofSeconds(2)).orElseThrow();
        guard.release();
    }

}
