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

    // These fixtures are host-only deterministic test instrumentation. A
    // context-aware actor BehaviorFactory must itself be stateless: capturing
    // CountDownLatch/AtomicReference objects is intentionally rejected by the
    // production runtime's source-isolation admission policy.
    private static final java.util.concurrent.ConcurrentHashMap<String, StopFenceFixture>
            STOP_FENCE_FIXTURES = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class StopFenceFixture {
        final CountDownLatch childrenOwned = new CountDownLatch(2);
        final CountDownLatch revokerArmed = new CountDownLatch(1);
        final CountDownLatch firstRevokerEntered = new CountDownLatch(1);
        final CountDownLatch bothRevokersEntered = new CountDownLatch(2);
        final CountDownLatch releaseRevokers = new CountDownLatch(1);
        final CountDownLatch spawnerEntered = new CountDownLatch(1);
        final CountDownLatch grandchildCreated = new CountDownLatch(1);
        final AtomicReference<ActorRuntime.ActorRef<String>> grandchild =
                new AtomicReference<>();
        final CountDownLatch allowSpawner = new CountDownLatch(1);
        final CountDownLatch spawnerFinished = new CountDownLatch(1);
        final AtomicInteger forbiddenMessages = new AtomicInteger();
        final AtomicInteger forbiddenGrandchildren = new AtomicInteger();
        final AtomicReference<Throwable> spawnDenial = new AtomicReference<>();
        final AtomicReference<Throwable> guestFailure = new AtomicReference<>();
    }

    private static StopFenceFixture stopFenceFixture(String message) {
        int delimiter = message.indexOf('#');
        String token = delimiter < 0 ? message : message.substring(0, delimiter);
        StopFenceFixture fixture = STOP_FENCE_FIXTURES.get(token);
        if (fixture == null) throw new IllegalStateException("unknown host-only STOP fixture");
        return fixture;
    }

    private static ActorRuntime.Behavior<String> stopFenceResourceChild(
            ActorRuntime.ActorContext<String> ignored) {
        return (message, context) -> {
            StopFenceFixture fixture = stopFenceFixture(message);
            if (!message.endsWith("#own")) {
                fixture.forbiddenMessages.incrementAndGet();
                return;
            }
            OresFuture<Void> resource = new OresFuture<>(() -> {
                fixture.firstRevokerEntered.countDown();
                fixture.bothRevokersEntered.countDown();
                try {
                    if (!fixture.releaseRevokers.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test child revoker not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            });
            context.runtime().ownCurrentActorFuture(resource);
            if (resource.isCancelled()) {
                fixture.guestFailure.compareAndSet(null,
                        new AssertionError("child Future ownership was rejected"));
            }
            fixture.childrenOwned.countDown();
            fixture.revokerArmed.countDown();
        };
    }

    private static ActorRuntime.Behavior<String> stopFenceTreeOwnerChild(
            ActorRuntime.ActorContext<String> ignored) {
        return (message, context) -> {
            StopFenceFixture fixture = stopFenceFixture(message);
            var grandchild = context.runtime().spawnShared(
                    ActorStopCleanupAdmissionTest::stopFenceResourceChild);
            fixture.grandchild.set(grandchild);
            fixture.grandchildCreated.countDown();
        };
    }

    private static ActorRuntime.Behavior<String> stopFenceSpawnerChild(
            ActorRuntime.ActorContext<String> ignored) {
        return (message, context) -> {
            StopFenceFixture fixture = stopFenceFixture(message);
            fixture.spawnerEntered.countDown();
            try {
                if (!fixture.allowSpawner.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test guest turn not resumed");
                }
                context.runtime().spawnShared(
                        (ActorRuntime.BehaviorFactory<String>) child ->
                                (grandchildMessage, grandchildContext) -> { });
                fixture.forbiddenGrandchildren.incrementAndGet();
            } catch (Throwable denial) {
                fixture.spawnDenial.set(denial);
            } finally {
                fixture.spawnerFinished.countDown();
            }
        };
    }
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
                                    guard = mutex.tryLock().orElseThrow(); // actors never block on lock()
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
                                    guard = mutex.tryLock().orElseThrow(); // actors never block on lock()
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
    void blockedDrainSubscriberCannotDelayChildCancellationOrGuestStop()
            throws Exception {
        // Pending drain() observers must be notified only AFTER structured
        // children and source cancellation have been requested. A synchronous
        // observer can block forever; it cannot hold up child STOP admission.
        CountDownLatch childAdmitted = new CountDownLatch(1);
        CountDownLatch releaseParentTurn = new CountDownLatch(1);
        CountDownLatch parentTurnLeaving = new CountDownLatch(1);
        CountDownLatch drainSubscriberEntered = new CountDownLatch(1);
        CountDownLatch releaseDrainSubscriber = new CountDownLatch(1);
        AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        AtomicReference<Throwable> observerFailure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.<String>spawnShared(() -> (message, context) -> {
                var child = context.runtime().<String>spawnShared(
                        // Actor code must use the stateless factory path.
                        childFactoryContext -> (childMessage, childContext) -> { });
                childRef.set(child);
                childAdmitted.countDown();
                try {
                    if (!releaseParentTurn.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("parent turn did not release");
                    }
                } finally {
                    parentTurnLeaving.countDown();
                }
            });
            parent.send("hold");
            assertTrue(childAdmitted.await(3, TimeUnit.SECONDS));
            OresFuture<Void> observer = parent.drain();
            assertFalse(observer.isDone(), "mailbox turn is deliberately in flight");
            observer.whenCompleteRuntime((ignored, failure) -> {
                try {
                    assertNotNull(failure, "STOP must fail an incomplete mailbox drain");
                    drainSubscriberEntered.countDown();
                    if (!releaseDrainSubscriber.await(5, TimeUnit.SECONDS)) {
                        observerFailure.set(new AssertionError(
                                "drain observer was never released"));
                    }
                } catch (Throwable problem) {
                    observerFailure.compareAndSet(null, problem);
                    drainSubscriberEntered.countDown();
                }
            });
            Thread stopper = Thread.ofVirtual().start(() -> {
                try {
                    parent.cancel();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            });
            try {
                assertTrue(drainSubscriberEntered.await(3, TimeUnit.SECONDS),
                        "STOP must publish the failed drain");
                assertTrue(childRef.get().awaitTermination(3, TimeUnit.SECONDS),
                        "child STOP must be requested before a drain observer blocks");
                // Let the parent guest turn exit WHILE the drain callback
                // is still blocked. The observer receipt alone must now be
                // sufficient to prevent premature physical finalization.
                releaseParentTurn.countDown();
                assertTrue(parentTurnLeaving.await(3, TimeUnit.SECONDS));
                assertFalse(parent.done().isDone(),
                        "drain notification receipt must outlive the parent guest turn");
                assertFalse(parent.awaitTermination(150, TimeUnit.MILLISECONDS),
                        "subscriber still holds a terminal-notification receipt");
            } finally {
                releaseDrainSubscriber.countDown();
                releaseParentTurn.countDown();
            }
            stopper.join(TimeUnit.SECONDS.toMillis(3));
            assertFalse(stopper.isAlive());
            assertNull(stopFailure.get(), () -> String.valueOf(stopFailure.get()));
            assertNull(observerFailure.get(), () -> String.valueOf(observerFailure.get()));
            assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                    assertThrows(ExecutionException.class,
                            () -> parent.done().get(3, TimeUnit.SECONDS)).getCause());
        }
    }

    @Test
    void allGrandchildAdmissionsAreSealedBeforeAnyPhysicalStopDispatchRuns()
            throws Exception {
        String token = java.util.UUID.randomUUID().toString();
        StopFenceFixture fixture = new StopFenceFixture();
        STOP_FENCE_FIXTURES.put(token, fixture);
        CountDownLatch releasePhysical = new CountDownLatch(1);
        CountDownLatch directReady = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        try {
            // Deliberately pause worker START, not the caller's dispatch.
            // This isolates the logical pre-seal phase from any physical STOP.
            ActorRuntime.PhysicalStopDispatcher pausedWorkers = (work, discarded) -> {
                Thread.ofVirtual().start(() -> {
                    try {
                        if (!releasePhysical.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "test physical STOP workers never released");
                        }
                        work.run();
                    } catch (Throwable failure) {
                        workerFailure.compareAndSet(null, failure);
                    }
                });
            };
            try (ActorRuntime runtime = new ActorRuntime(
                    IsolatePolicy.developer(),
                    new ActorRuntime.DispatcherConfig(2, 4, 16, 64),
                    ActorRuntime.TurnExecutor.direct(),
                    ActorRuntime.ControlDispatcher.unavailable(),
                    pausedWorkers)) {
                var parent = runtime.<String>spawnShared(() -> (message, context) -> {
                    var resource = context.runtime().spawnShared(
                            ActorStopCleanupAdmissionTest::stopFenceResourceChild);
                    var owner = context.runtime().spawnShared(
                            ActorStopCleanupAdmissionTest::stopFenceTreeOwnerChild);
                    resource.send(token + "#own");
                    owner.send(token + "#create");
                    directReady.countDown();
                });
                parent.send("start");
                assertTrue(directReady.await(3, TimeUnit.SECONDS));
                assertTrue(fixture.revokerArmed.await(3, TimeUnit.SECONDS));
                assertTrue(fixture.grandchildCreated.await(3, TimeUnit.SECONDS));
                ActorRuntime.ActorRef<String> grandchild = fixture.grandchild.get();
                grandchild.ready().get(3, TimeUnit.SECONDS);
                assertTrue(parent.cancel());
                assertTrue(grandchild.isAlive(),
                        "no physical child/descendant STOP has been executed");
                RuntimeException denied = assertThrows(RuntimeException.class,
                        () -> grandchild.send(token + "#forbidden"));
                assertInstanceOf(ActorRuntime.ActorSealedException.class, denied,
                        "logical subtree STOP must fence mail in every generation");
                assertFalse(parent.done().isDone(),
                        "queued physical child STOP receipts still own parent lifetime");
                assertEquals(0, fixture.forbiddenMessages.get());
                releasePhysical.countDown();
                fixture.releaseRevokers.countDown();
                assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS));
                assertTrue(grandchild.awaitTermination(3, TimeUnit.SECONDS));
                assertNull(workerFailure.get(),
                        () -> String.valueOf(workerFailure.get()));
            }
        } finally {
            releasePhysical.countDown();
            fixture.releaseRevokers.countDown();
            STOP_FENCE_FIXTURES.remove(token);
        }
    }

    @Test
    void missingOrDuplicatedPhysicalStopDiscardReceiptCannotAuthorizeReclamation()
            throws Exception {
        CountDownLatch childSpawned = new CountDownLatch(1);
        AtomicInteger injectedDiscards = new AtomicInteger();
        ActorRuntime.PhysicalStopDispatcher defectiveHost = (work, discard) -> {
            // No STOP work was started. Even a broken dispatcher that reports
            // null (or reports twice) must never fabricate successful cleanup.
            injectedDiscards.incrementAndGet();
            discard.accept(null);
            discard.accept(new IllegalStateException("duplicate discard"));
        };
        ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 32),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(),
                defectiveHost);
        try {
            var parent = runtime.<String>spawnShared(() -> (message, context) -> {
                context.runtime().<String>spawnShared(
                        // Actor code must use the stateless factory path.
                        childFactoryContext -> (ignored, childContext) -> { });
                childSpawned.countDown();
            });
            parent.send("spawn");
            assertTrue(childSpawned.await(3, TimeUnit.SECONDS));
            assertTrue(parent.cancel());
            assertEquals(1, injectedDiscards.get());
            assertFalse(parent.done().isDone());
            assertFalse(parent.awaitTermination(100, TimeUnit.MILLISECONDS));
            assertThrows(IllegalStateException.class, runtime::close,
                    "malformed and duplicate discard receipts must quarantine actor STOP");
        } finally {
            try {
                runtime.close();
            } catch (IllegalStateException expectedQuarantine) {
                // A quarantined arena deliberately prevents clean VM teardown.
            }
        }
    }

    @Test
    void vmShutdownDiscardsQueuedSiblingStopAndQuarantinesParent()
            throws Exception {
        String token = java.util.UUID.randomUUID().toString();
        StopFenceFixture fixture = new StopFenceFixture();
        STOP_FENCE_FIXTURES.put(token, fixture);
        OresVM vm = OresVM.createWithPhysicalStopLimits(1, 1);
        ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 4, 16, 64),
                ActorRuntime.TurnExecutor.direct(),
                vm::executeControl,
                vm::executePhysicalStop);
        try {
            var parent = runtime.<String>spawnShared(() -> (message, context) -> {
                var first = context.runtime().spawnShared(
                        ActorStopCleanupAdmissionTest::stopFenceResourceChild);
                var second = context.runtime().spawnShared(
                        ActorStopCleanupAdmissionTest::stopFenceResourceChild);
                first.send(token + "#own");
                second.send(token + "#own");
            });
            parent.send("spawn");
            assertTrue(fixture.childrenOwned.await(3, TimeUnit.SECONDS));
            assertTrue(parent.cancel());
            assertTrue(fixture.firstRevokerEntered.await(3, TimeUnit.SECONDS),
                    "one worker should be in a physical child revoker");
            assertEquals(1L, fixture.bothRevokersEntered.getCount(),
                    "second child STOP must be admitted to the single queue slot");
            assertFalse(parent.done().isDone());

            // VM close interrupts the RUNNING child while dropping the
            // ADMITTED sibling. Neither event is proof of safe reclamation.
            vm.close();
            assertEquals(1L, fixture.bothRevokersEntered.getCount(),
                    "discarded child STOP must never invoke its guest revoker");
            assertFalse(parent.done().isDone(),
                    "discarded child STOP must quarantine parent physical ownership");
            assertFalse(parent.awaitTermination(150, TimeUnit.MILLISECONDS));
            assertThrows(IllegalStateException.class, runtime::close,
                    "a quarantined actor must not be reported as a clean shutdown");
            assertEquals(0, fixture.forbiddenMessages.get());
        } finally {
            fixture.releaseRevokers.countDown();
            try {
                runtime.close();
            } catch (IllegalStateException expectedQuarantine) {
                // This test intentionally leaves quarantined actor ownership.
            }
            vm.close();
            STOP_FENCE_FIXTURES.remove(token);
        }
    }

    @Test
    void vmOwnedStopDispatcherFansOutSiblingsWithoutBlockingParentOrSource()
            throws Exception {
        String token = java.util.UUID.randomUUID().toString();
        StopFenceFixture fixture = new StopFenceFixture();
        STOP_FENCE_FIXTURES.put(token, fixture);
        try {
            AtomicReference<java.util.List<ActorRuntime.ActorRef<String>>> children =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger accepted =
                    new java.util.concurrent.atomic.AtomicInteger();
            // Exercise the real OresVM bounded teardown lane rather than
            // creating a worker thread per test child.
            try (OresVM vm = OresVM.create(Runnable::run);
                 ActorRuntime runtime = new ActorRuntime(
                         IsolatePolicy.developer(),
                         ActorRuntime.DispatcherConfig.defaults(),
                         ActorRuntime.TurnExecutor.direct(),
                         vm::executeControl,
                         (stopWork, discarded) -> {
                             vm.executePhysicalStop(stopWork, discarded);
                             accepted.incrementAndGet();
                         })) {
                var parent = runtime.<String>spawnShared(() -> (message, context) -> {
                    var first = context.runtime().spawnShared(
                            ActorStopCleanupAdmissionTest::stopFenceResourceChild);
                    var second = context.runtime().spawnShared(
                            ActorStopCleanupAdmissionTest::stopFenceResourceChild);
                    children.set(java.util.List.of(first, second));
                    first.send(token + "#own");
                    second.send(token + "#own");
                });
                parent.send("spawn");
                assertTrue(fixture.childrenOwned.await(3, TimeUnit.SECONDS));
                Thread cancelling = Thread.ofVirtual().start(() -> {
                    try {
                        parent.cancel();
                    } catch (Throwable problem) {
                        failure.set(problem);
                    }
                });
                try {
                    assertTrue(fixture.bothRevokersEntered.await(3, TimeUnit.SECONDS),
                            "the second child's STOP must not wait on the first revoker");
                    cancelling.join(TimeUnit.SECONDS.toMillis(2));
                    assertFalse(cancelling.isAlive(),
                            "parent STOP dispatch must return before physical revokers");
                    assertEquals(2, accepted.get(),
                            "each direct child needs an independently admitted teardown");
                    assertFalse(parent.done().isDone(),
                            "DONE cannot precede both physical child receipts");
                    assertFalse(parent.awaitTermination(100, TimeUnit.MILLISECONDS));
                    for (var child : children.get()) {
                        assertFalse(child.done().isDone(),
                                "blocked revoker keeps the corresponding child alive");
                    }
                } finally {
                    fixture.releaseRevokers.countDown();
                }
                assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS));
                for (var child : children.get()) {
                    assertTrue(child.awaitTermination(3, TimeUnit.SECONDS));
                }
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                assertNull(fixture.guestFailure.get(),
                        () -> String.valueOf(fixture.guestFailure.get()));
                assertEquals(0, fixture.forbiddenMessages.get());
            }
        } finally {
            fixture.releaseRevokers.countDown();
            STOP_FENCE_FIXTURES.remove(token);
        }
    }

    @Test
    void inFlightChildCannotSpawnGrandchildAfterParentStopPreFence()
            throws Exception {
        String token = java.util.UUID.randomUUID().toString();
        StopFenceFixture fixture = new StopFenceFixture();
        STOP_FENCE_FIXTURES.put(token, fixture);
        try {
            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            try (ActorRuntime runtime = new ActorRuntime()) {
                var parent = runtime.<String>spawnShared(() -> (message, context) -> {
                    var resource = context.runtime().spawnShared(
                            ActorStopCleanupAdmissionTest::stopFenceResourceChild);
                    var spawner = context.runtime().spawnShared(
                            ActorStopCleanupAdmissionTest::stopFenceSpawnerChild);
                    resource.send(token + "#own");
                    spawner.send(token + "#spawn");
                });
                parent.send("start");
                assertTrue(fixture.revokerArmed.await(3, TimeUnit.SECONDS));
                assertTrue(fixture.spawnerEntered.await(3, TimeUnit.SECONDS));
                Thread stopping = Thread.ofVirtual().start(() -> {
                    try {
                        parent.cancel();
                    } catch (Throwable failure) {
                        stopFailure.set(failure);
                    }
                });
                try {
                    assertTrue(fixture.firstRevokerEntered.await(3, TimeUnit.SECONDS));
                    fixture.allowSpawner.countDown();
                    assertTrue(fixture.spawnerFinished.await(3, TimeUnit.SECONDS));
                    assertInstanceOf(CancellationException.class, fixture.spawnDenial.get(),
                            "a pre-sealed child cannot create a new grandchild");
                    assertEquals(0, fixture.forbiddenGrandchildren.get());
                } finally {
                    fixture.allowSpawner.countDown();
                    fixture.releaseRevokers.countDown();
                }
                stopping.join(TimeUnit.SECONDS.toMillis(3));
                assertFalse(stopping.isAlive());
                assertNull(stopFailure.get(), () -> String.valueOf(stopFailure.get()));
                assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS));
                assertEquals(0, fixture.forbiddenGrandchildren.get());
            }
        } finally {
            fixture.allowSpawner.countDown();
            fixture.releaseRevokers.countDown();
            STOP_FENCE_FIXTURES.remove(token);
        }
    }


    @Test
    void siblingMailboxesAreSealedBeforeAnyChildRevokerCanBlock()
            throws Exception {
        String token = java.util.UUID.randomUUID().toString();
        StopFenceFixture fixture = new StopFenceFixture();
        STOP_FENCE_FIXTURES.put(token, fixture);
        try {
            AtomicReference<java.util.List<ActorRuntime.ActorRef<String>>> children =
                    new AtomicReference<>();
            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            try (ActorRuntime runtime = new ActorRuntime()) {
                // The trusted HOST-created parent may capture the fixture token.
                // Every CHILD is spawned using a stateless factory method ref.
                var parent = runtime.<String>spawnShared(() -> (message, context) -> {
                    var first = context.runtime().spawnShared(
                            ActorStopCleanupAdmissionTest::stopFenceResourceChild);
                    var second = context.runtime().spawnShared(
                            ActorStopCleanupAdmissionTest::stopFenceResourceChild);
                    children.set(java.util.List.of(first, second));
                    first.send(token + "#own");
                    second.send(token + "#own");
                });
                parent.send("spawn");
                assertTrue(fixture.childrenOwned.await(3, TimeUnit.SECONDS));
                Thread stopping = Thread.ofVirtual().start(() -> {
                    try {
                        parent.cancel();
                    } catch (Throwable failure) {
                        stopFailure.set(failure);
                    }
                });
                try {
                    assertTrue(fixture.firstRevokerEntered.await(3, TimeUnit.SECONDS));
                    assertFalse(parent.done().isDone());
                    for (var child : children.get()) {
                        RuntimeException rejected = assertThrows(RuntimeException.class,
                                () -> child.send(token + "#forbidden"));
                        assertTrue(rejected instanceof ActorRuntime.ActorSealedException
                                        || rejected instanceof ActorRuntime.ActorTerminatedException,
                                "sibling pre-fence must deny post-STOP mail: " + rejected);
                    }
                    assertEquals(0, fixture.forbiddenMessages.get());
                } finally {
                    fixture.releaseRevokers.countDown();
                }
                stopping.join(TimeUnit.SECONDS.toMillis(3));
                assertFalse(stopping.isAlive());
                assertNull(stopFailure.get(), () -> String.valueOf(stopFailure.get()));
                assertNull(fixture.guestFailure.get(),
                        () -> String.valueOf(fixture.guestFailure.get()));
                assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS));
                for (var child : children.get()) {
                    assertTrue(child.awaitTermination(3, TimeUnit.SECONDS));
                }
                assertEquals(0, fixture.forbiddenMessages.get());
            }
        } finally {
            fixture.releaseRevokers.countDown();
            STOP_FENCE_FIXTURES.remove(token);
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
                var child = context.runtime().<String>spawnShared(
                        // Actor code must use the stateless factory path.
                        childFactoryContext -> (childMessage, childContext) -> { });
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
    void actorStopRetainsOwnedFutureAcrossOtherBlockedTerminalSubscribers()
            throws Exception {
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch subscriberEntered = new CountDownLatch(1);
        CountDownLatch releaseSubscriber = new CountDownLatch(1);
        AtomicReference<OresFuture<Void>> ownedOperation = new AtomicReference<>();
        AtomicReference<Throwable> foreignFailure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                OresFuture<Void> future = new OresFuture<>();
                context.runtime().ownCurrentActorFuture(future);
                // Install AFTER actor ownership. Previously that first
                // ownership waiter removed the Future before this subscriber
                // finished, allowing STOP to miss this in-flight callback.
                future.whenCompleteRuntime((ignored, failure) -> {
                    subscriberEntered.countDown();
                    try {
                        if (!releaseSubscriber.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("terminal subscriber held too long");
                        }
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interruption);
                    }
                });
                ownedOperation.set(future);
                admitted.countDown();
            });
            actor.send("own");
            assertTrue(admitted.await(3, TimeUnit.SECONDS));
            Thread settling = Thread.ofVirtual().start(() -> {
                try {
                    ownedOperation.get().cancel(false);
                } catch (Throwable failure) {
                    foreignFailure.set(failure);
                }
            });
            try {
                assertTrue(subscriberEntered.await(3, TimeUnit.SECONDS),
                        "a foreign producer is physically inside terminal delivery");
                assertTrue(ownedOperation.get().isCancelled());
                assertTrue(actor.cancel());
                assertFalse(actor.done().isDone(),
                        "the first terminal subscriber cannot release actor ownership");
                assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS),
                        "actor arena may still be reachable from a later subscriber");
            } finally {
                releaseSubscriber.countDown();
            }
            settling.join(TimeUnit.SECONDS.toMillis(3));
            assertFalse(settling.isAlive());
            assertNull(foreignFailure.get(), () -> String.valueOf(foreignFailure.get()));
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS),
                    "owner receipt must release the arena after every subscriber drains");
            assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                    assertThrows(ExecutionException.class,
                            () -> actor.done().get(3, TimeUnit.SECONDS)).getCause());
        }
    }

    @Test
    void actorStopJoinsCancellationAlreadyWinningOnAnotherThread()
            throws Exception {
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch foreignRevokerEntered = new CountDownLatch(1);
        CountDownLatch releaseForeignRevoker = new CountDownLatch(1);
        AtomicReference<OresFuture<Void>> ownedOperation = new AtomicReference<>();
        AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                OresFuture<Void> owned = new OresFuture<>(() -> {
                    foreignRevokerEntered.countDown();
                    try {
                        if (!releaseForeignRevoker.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("foreign revoker held too long");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                });
                context.runtime().ownCurrentActorFuture(owned);
                ownedOperation.set(owned);
                admitted.countDown();
            });
            actor.send("own");
            assertTrue(admitted.await(3, TimeUnit.SECONDS));
            Thread foreignCancel = Thread.ofVirtual().start(() -> {
                try {
                    ownedOperation.get().cancel(false);
                } catch (Throwable failure) {
                    foreignFailure.set(failure);
                }
            });
            try {
                assertTrue(foreignRevokerEntered.await(3, TimeUnit.SECONDS));
                assertTrue(ownedOperation.get().isCancelled(),
                        "Future cancellation state precedes physical revoker completion");
                assertTrue(actor.cancel(),
                        "actor STOP can begin while another cancellation wins");
                assertFalse(actor.done().isDone(),
                        "losing a cancellation CAS does not join the winning revoker");
                assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS),
                        "arena must remain owned while foreign cancellation hook runs");
            } finally {
                releaseForeignRevoker.countDown();
            }
            foreignCancel.join(TimeUnit.SECONDS.toMillis(3));
            assertFalse(foreignCancel.isAlive());
            assertNull(foreignFailure.get(), () -> String.valueOf(foreignFailure.get()));
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS),
                    "notification-drain receipt must allow final STOP retirement");
            assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                    assertThrows(ExecutionException.class,
                            () -> actor.done().get(3, TimeUnit.SECONDS)).getCause());
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
