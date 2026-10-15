package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A mailbox drain proves guest message processing, but not that the carrier
 * has crossed back out of TurnExecutor (and the polyglot context). HTTP
 * shutdown must wait for that second, physical-lifetime boundary.
 */
@Timeout(15)
final class ActorCarrierQuiescenceTest {
    @org.junit.jupiter.api.RepeatedTest(20)
    void freshActorStartupCannotAppearPhysicallyQuiescentBeforeFirstTurn()
            throws Exception {
        CountDownLatch enteredCarrier = new CountDownLatch(1);
        CountDownLatch resumeStartup = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicBoolean firstTurn = new AtomicBoolean();

        ActorRuntime.TurnExecutor executor = turn -> {
            // Stop the first scheduled physical carrier before it enters its
            // actor initializer. This is a deterministic startup race: no
            // HTTP timeout, sleep, or heuristic scheduling assumption.
            if (firstTurn.compareAndSet(false, true)) {
                enteredCarrier.countDown();
                awaitTestRelease(resumeStartup);
            }
            turn.run();
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 16),
                executor)) {
            ActorRuntime.ActorRef<Integer> fresh = runtime.spawnShared(() ->
                    (message, context) -> delivered.countDown());
            try {
                assertTrue(enteredCarrier.await(5, TimeUnit.SECONDS),
                        "fresh actor startup did not enter its physical carrier");
                // Mail is accepted while its initializer is stalled. Neither
                // the initial turn nor the later mailbox turn may be skipped
                // by the host-only quiescence observer.
                fresh.send(1);
                OresFuture<Void> quiescence = runtime.quiesceAcceptedMessages(fresh);
                assertFalse(quiescence.isDone(),
                        "startup carrier must not appear idle before entering guest code");
                resumeStartup.countDown();
                quiescence.get(5, TimeUnit.SECONDS);
                assertEquals(0L, delivered.getCount(),
                        "accepted mail was not processed before quiescence");
            } finally {
                resumeStartup.countDown();
            }
        }
    }

    @Test
    void mailboxDrainCannotImpersonatePhysicalCarrierExit() throws Exception {
        CountDownLatch guestProcessed = new CountDownLatch(1);
        CountDownLatch afterGuestBeforeCarrierExit = new CountDownLatch(1);
        CountDownLatch releaseCarrier = new CountDownLatch(1);
        AtomicBoolean gateOnce = new AtomicBoolean();

        ActorRuntime.TurnExecutor executor = turn -> {
            turn.run();
            if (guestProcessed.getCount() == 0 && gateOnce.compareAndSet(false, true)) {
                afterGuestBeforeCarrierExit.countDown();
                try {
                    if (!releaseCarrier.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release test carrier");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("carrier test interrupted", interrupted);
                }
            }
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 16),
                executor)) {
            ActorRuntime.ActorRef<Integer> actor =
                    runtime.spawnShared(() -> (message, context) -> guestProcessed.countDown());
            try {
                actor.send(1);
                assertTrue(guestProcessed.await(5, TimeUnit.SECONDS));
                assertTrue(afterGuestBeforeCarrierExit.await(5, TimeUnit.SECONDS));
                assertTrue(actor.drain().isDone(),
                        "mailbox completion precedes physical TurnExecutor exit");

                OresFuture<Void> first = runtime.quiesceAcceptedMessages(actor);
                OresFuture<Void> second = runtime.quiesceAcceptedMessages(actor);
                assertFalse(first.isDone(),
                        "quiescence must not finish inside the physical carrier boundary");
                assertFalse(second.isDone(), "each shutdown observer must stay pending");
                assertFalse(first.cancel(false), "lifecycle observers are noncancellable");
                releaseCarrier.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
                assertFalse(ActorRuntime.inActorExecution());
            } finally {
                releaseCarrier.countDown();
            }
        }
    }

    @Test
    void alreadyQueuedFollowupTurnMustNotProduceFalseQuiescence() throws Exception {
        CountDownLatch firstProcessed = new CountDownLatch(1);
        CountDownLatch firstCarrierPaused = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondProcessed = new CountDownLatch(1);
        CountDownLatch secondCarrierPaused = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicBoolean pausedFirst = new AtomicBoolean();
        AtomicBoolean pausedSecond = new AtomicBoolean();

        ActorRuntime.TurnExecutor executor = turn -> {
            turn.run();
            if (firstProcessed.getCount() == 0
                    && pausedFirst.compareAndSet(false, true)) {
                firstCarrierPaused.countDown();
                awaitTestRelease(releaseFirst);
            } else if (secondProcessed.getCount() == 0
                    && pausedSecond.compareAndSet(false, true)) {
                secondCarrierPaused.countDown();
                awaitTestRelease(releaseSecond);
            }
        };

        // Throughput one guarantees that the second accepted mail is queued
        // behind the first physical TurnExecutor exit, not processed in the
        // same batch. Observe the first, then admit the follow-up.
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 16),
                executor)) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(() ->
                    (message, context) -> {
                        if (message == 1) firstProcessed.countDown();
                        else if (message == 2) secondProcessed.countDown();
                    });
            try {
                actor.send(1);
                assertTrue(firstCarrierPaused.await(5, TimeUnit.SECONDS));
                OresFuture<Void> barrier = runtime.quiesceAcceptedMessages(actor);
                assertFalse(barrier.isDone());
                actor.send(2); // accepted after the barrier snapshot
                releaseFirst.countDown();
                assertTrue(secondCarrierPaused.await(5, TimeUnit.SECONDS));
                assertFalse(barrier.isDone(),
                        "the first carrier exit cannot authorize Context.close with a queued successor");
                releaseSecond.countDown();
                barrier.get(5, TimeUnit.SECONDS);
                assertTrue(secondProcessed.getCount() == 0);
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
            }
        }
    }

    @Test
    void cancellationWithDiscardedMailSettlesOnlyAfterPhysicalFinalization()
            throws Exception {
        CountDownLatch messageProcessed = new CountDownLatch(1);
        CountDownLatch carrierHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean pause = new AtomicBoolean();

        ActorRuntime.TurnExecutor executor = turn -> {
            turn.run();
            if (messageProcessed.getCount() == 0
                    && pause.compareAndSet(false, true)) {
                carrierHeld.countDown();
                awaitTestRelease(release);
            }
        };
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 16),
                executor)) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(() ->
                    (message, context) -> messageProcessed.countDown());
            try {
                actor.send(1);
                assertTrue(carrierHeld.await(5, TimeUnit.SECONDS));
                actor.send(2); // will be dropped by cancellation
                OresFuture<Void> observer = runtime.quiesceAcceptedMessages(actor);
                assertFalse(observer.isDone());
                actor.cancel();
                assertFalse(observer.isDone(),
                        "logical stop is not physical termination");
                // A new observer after stop must also remain pending until
                // physical finalization and cannot cancel the actor's own
                // internal finalizedFuture.
                OresFuture<Void> stoppedObserver = runtime.quiesceAcceptedMessages(actor);
                assertFalse(stoppedObserver.isDone());
                assertFalse(stoppedObserver.cancel(false));
                release.countDown();
                observer.get(5, TimeUnit.SECONDS);
                stoppedObserver.get(5, TimeUnit.SECONDS);
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    @org.junit.jupiter.api.RepeatedTest(20)
    void terminalCarrierObserversPublishOutsideLifecycleMonitor() throws Exception {
        CountDownLatch processed = new CountDownLatch(1);
        CountDownLatch carrierHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch notified = new CountDownLatch(2);
        AtomicBoolean holdOnce = new AtomicBoolean();
        AtomicBoolean callbackHeldMonitor = new AtomicBoolean();
        AtomicBoolean callbackBlockedCompetingMonitor = new AtomicBoolean();

        ActorRuntime.TurnExecutor executor = turn -> {
            turn.run();
            if (processed.getCount() == 0 && holdOnce.compareAndSet(false, true)) {
                carrierHeld.countDown();
                awaitTestRelease(release);
            }
        };
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 16),
                executor)) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(() ->
                    (message, context) -> processed.countDown());
            try {
                actor.send(1);
                assertTrue(carrierHeld.await(5, TimeUnit.SECONDS));
                Object monitor = lifecycleMonitor(runtime, actor);
                OresFuture<Void> beforeStop = runtime.quiesceAcceptedMessages(actor);
                actor.cancel();
                // This second observer used to subscribe to finalizedFuture,
                // whose callbacks execute synchronously inside finalizeStopLocked.
                OresFuture<Void> afterStop = runtime.quiesceAcceptedMessages(actor);
                for (OresFuture<Void> observer :
                        java.util.List.of(beforeStop, afterStop)) {
                    observer.whenCompleteRuntime((ignored, failure) -> {
                        if (Thread.holdsLock(monitor)) callbackHeldMonitor.set(true);
                        CountDownLatch acquired = new CountDownLatch(1);
                        Thread.ofVirtual().start(() -> {
                            synchronized (monitor) { acquired.countDown(); }
                        });
                        try {
                            if (!acquired.await(2, TimeUnit.SECONDS)) {
                                callbackBlockedCompetingMonitor.set(true);
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            callbackBlockedCompetingMonitor.set(true);
                        } finally {
                            notified.countDown();
                        }
                    });
                }
                assertFalse(beforeStop.isDone());
                assertFalse(afterStop.isDone());
                release.countDown();
                beforeStop.get(5, TimeUnit.SECONDS);
                afterStop.get(5, TimeUnit.SECONDS);
                assertTrue(notified.await(5, TimeUnit.SECONDS),
                        "both terminal observers must notify their host subscribers");
                assertFalse(callbackHeldMonitor.get(),
                        "a terminal callback ran while lifecycleLock was held");
                assertFalse(callbackBlockedCompetingMonitor.get(),
                        "a terminal callback blocked another thread's lifecycle admission");
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void stoppedActorCarrierObserversShareTheBoundedQuota() throws Exception {
        CountDownLatch processed = new CountDownLatch(1);
        CountDownLatch carrierHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean holdOnce = new AtomicBoolean();
        ActorRuntime.TurnExecutor executor = turn -> {
            turn.run();
            if (processed.getCount() == 0 && holdOnce.compareAndSet(false, true)) {
                carrierHeld.countDown();
                awaitTestRelease(release);
            }
        };
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 16),
                executor)) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(() ->
                    (message, context) -> processed.countDown());
            try {
                actor.send(1);
                assertTrue(carrierHeld.await(5, TimeUnit.SECONDS));
                actor.cancel();
                var admitted = new java.util.ArrayList<OresFuture<Void>>();
                for (int i = 0; i < 1_024; i++) {
                    OresFuture<Void> observer = runtime.quiesceAcceptedMessages(actor);
                    assertFalse(observer.isDone(), "premature stopped observer " + i);
                    admitted.add(observer);
                }
                OresFuture<Void> overflow = runtime.quiesceAcceptedMessages(actor);
                var denied = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> overflow.get(1, TimeUnit.SECONDS));
                assertInstanceOf(ActorRuntime.ActorResourceExhaustedException.class,
                        denied.getCause());
                release.countDown();
                for (OresFuture<Void> observer : admitted) {
                    observer.get(5, TimeUnit.SECONDS);
                }
                assertTrue(runtime.quiesceAcceptedMessages(actor).isDone(),
                        "fully finalized actor must not retain stopped observer quota");
            } finally {
                release.countDown();
            }
        }
    }

    @org.junit.jupiter.api.RepeatedTest(12)
    void successfulStartupReadinessDoesNotNotifyUnderLifecycleLock() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CountDownLatch notified = new CountDownLatch(1);
        AtomicBoolean heldLock = new AtomicBoolean();
        AtomicBoolean blockedCompetingThread = new AtomicBoolean();
        ActorRuntime.TurnExecutor executor = turn -> {
            entered.countDown();
            awaitTestRelease(resume);
            turn.run();
        };
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 16),
                executor)) {
            ActorRuntime.ActorRef<Integer> actor =
                    runtime.spawnShared(() -> (message, context) -> { });
            try {
                OresFuture<Void> ready = actor.ready();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Object monitor = lifecycleMonitor(runtime, actor);
                ready.whenCompleteRuntime((ignored, failure) -> {
                    if (Thread.holdsLock(monitor)) heldLock.set(true);
                    CountDownLatch acquired = new CountDownLatch(1);
                    Thread.ofVirtual().start(() -> {
                        synchronized (monitor) { acquired.countDown(); }
                    });
                    try {
                        if (!acquired.await(2, TimeUnit.SECONDS)) {
                            blockedCompetingThread.set(true);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        blockedCompetingThread.set(true);
                    } finally {
                        notified.countDown();
                    }
                });
                assertFalse(ready.isDone(), "the initializer is still gated");
                resume.countDown();
                ready.get(5, TimeUnit.SECONDS);
                assertTrue(notified.await(5, TimeUnit.SECONDS));
                assertFalse(heldLock.get(), "readiness callback held lifecycleLock");
                assertFalse(blockedCompetingThread.get(),
                        "readiness notification prevented competing lifecycle entry");
            } finally {
                resume.countDown();
            }
        }
    }

    @org.junit.jupiter.api.RepeatedTest(12)
    void cancelledBeforeStartupNotifiesAllTerminalFuturesOutsideLifecycleLock()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CountDownLatch notified = new CountDownLatch(4);
        AtomicBoolean heldLock = new AtomicBoolean();
        AtomicBoolean blockedCompetingThread = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger notifications =
                new java.util.concurrent.atomic.AtomicInteger();

        ActorRuntime.TurnExecutor executor = turn -> {
            entered.countDown();
            awaitTestRelease(resume);
            turn.run();
        };
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 16),
                executor)) {
            ActorRuntime.ActorRef<Integer> actor =
                    runtime.spawnShared(() -> (message, context) -> { });
            try {
                OresFuture<Void> ready = actor.ready();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Object monitor = lifecycleMonitor(runtime, actor);
                OresFuture<Void> done = actor.done();
                // The internal finalization Future must follow the same
                // after-lock rule as public ready/done/quiescence observers.
                var registryField = ActorRuntime.class.getDeclaredField("actors");
                registryField.setAccessible(true);
                Object cell = ((java.util.Map<?, ?>) registryField.get(runtime))
                        .get(actor.id());
                assertNotNull(cell);
                var finalizedField = cell.getClass().getDeclaredField("finalizedFuture");
                finalizedField.setAccessible(true);
                @SuppressWarnings("unchecked")
                OresFuture<Void> finalized = (OresFuture<Void>) finalizedField.get(cell);

                actor.cancel();
                OresFuture<Void> quiescence = runtime.quiesceAcceptedMessages(actor);
                for (OresFuture<Void> future :
                        java.util.List.of(ready, done, finalized, quiescence)) {
                    future.whenCompleteRuntime((ignored, failure) -> {
                        notifications.incrementAndGet();
                        if (Thread.holdsLock(monitor)) heldLock.set(true);
                        CountDownLatch acquired = new CountDownLatch(1);
                        Thread.ofVirtual().start(() -> {
                            synchronized (monitor) { acquired.countDown(); }
                        });
                        try {
                            if (!acquired.await(2, TimeUnit.SECONDS)) {
                                blockedCompetingThread.set(true);
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            blockedCompetingThread.set(true);
                        } finally {
                            notified.countDown();
                        }
                    });
                }
                assertFalse(done.isDone(), "physical carrier still held");
                assertFalse(finalized.isDone());
                assertFalse(quiescence.isDone());
                resume.countDown();
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> ready.get(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> done.get(5, TimeUnit.SECONDS));
                finalized.get(5, TimeUnit.SECONDS);
                quiescence.get(5, TimeUnit.SECONDS);
                assertTrue(notified.await(5, TimeUnit.SECONDS));
                assertEquals(4, notifications.get(), "one notification per Future");
                assertFalse(heldLock.get(),
                        "terminal readiness, done, or finalization notified while locked");
                assertFalse(blockedCompetingThread.get(),
                        "terminal notification blocked competing lifecycle admission");
            } finally {
                resume.countDown();
            }
        }
    }

    private static Object lifecycleMonitor(
            ActorRuntime runtime, ActorRuntime.ActorRef<?> ref)
            throws ReflectiveOperationException {
        // Intentionally inspect the actual monitor rather than a surrogate
        // helper predicate: a callback running under it can deadlock another
        // host operation that needs actor lifecycle admission.
        var registryField = ActorRuntime.class.getDeclaredField("actors");
        registryField.setAccessible(true);
        var registry = (java.util.Map<?, ?>) registryField.get(runtime);
        Object cell = registry.get(ref.id());
        assertNotNull(cell, "actor must still be registered during its physical turn");
        var monitorField = cell.getClass().getDeclaredField("lifecycleLock");
        monitorField.setAccessible(true);
        return monitorField.get(cell);
    }

    private static void awaitTestRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test carrier release timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test carrier interrupted", interrupted);
        }
    }

    @Test
    void boundedObserverQuotaRejectsOverflowAndReleasesEveryAdmittedObserver()
            throws Exception {
        CountDownLatch processed = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean holdOnce = new AtomicBoolean();

        ActorRuntime.TurnExecutor executor = turn -> {
            turn.run();
            if (processed.getCount() == 0 && holdOnce.compareAndSet(false, true)) {
                held.countDown();
                awaitTestRelease(release);
            }
        };
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 16),
                executor)) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(() ->
                    (message, context) -> processed.countDown());
            try {
                actor.send(1);
                assertTrue(held.await(5, TimeUnit.SECONDS));
                var observers = new java.util.ArrayList<OresFuture<Void>>();
                for (int i = 0; i < 1_024; i++) {
                    OresFuture<Void> observer = runtime.quiesceAcceptedMessages(actor);
                    assertFalse(observer.isDone(), "observer " + i + " settled before carrier exit");
                    observers.add(observer);
                }

                // Exhaustion must fail closed, never allocate unbounded
                // pending shutdown observers or silently return success.
                for (int i = 0; i < 50; i++) {
                    OresFuture<Void> overflow = runtime.quiesceAcceptedMessages(actor);
                    assertTrue(overflow.isDone());
                    var denied = assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> overflow.get(1, TimeUnit.SECONDS));
                    assertInstanceOf(ActorRuntime.ActorResourceExhaustedException.class,
                            denied.getCause());
                }
                release.countDown();
                for (OresFuture<Void> observer : observers) {
                    observer.get(5, TimeUnit.SECONDS);
                }
                assertTrue(runtime.quiesceAcceptedMessages(actor).isDone(),
                        "observer capacity must be reclaimed after quiescence");
            } finally {
                release.countDown();
            }
        }
    }

    @org.junit.jupiter.api.RepeatedTest(25)
    void cancellationAndCarrierExitCompleteObserverExactlyOnce() throws Exception {
        CountDownLatch processed = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch notified = new CountDownLatch(1);
        AtomicBoolean holdOnce = new AtomicBoolean();
        var notifications = new java.util.concurrent.atomic.AtomicInteger();
        ActorRuntime.TurnExecutor executor = turn -> {
            turn.run();
            if (processed.getCount() == 0 && holdOnce.compareAndSet(false, true)) {
                held.countDown();
                awaitTestRelease(release);
            }
        };
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 16),
                executor)) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(() ->
                    (message, context) -> processed.countDown());
            Thread canceller = null;
            try {
                actor.send(1);
                assertTrue(held.await(5, TimeUnit.SECONDS));
                OresFuture<Void> observer = runtime.quiesceAcceptedMessages(actor);
                observer.whenCompleteRuntime((ignored, failure) -> {
                    notifications.incrementAndGet();
                    notified.countDown();
                });
                canceller = Thread.ofVirtual().start(actor::cancel);
                release.countDown();
                canceller.join(5_000);
                assertFalse(canceller.isAlive(), "cancellation did not return");
                observer.get(5, TimeUnit.SECONDS);
                assertTrue(notified.await(5, TimeUnit.SECONDS),
                        "runtime-only completion callback did not fire");
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertEquals(1, notifications.get(),
                        "racing finalization replayed an observer callback");
            } finally {
                release.countDown();
                if (canceller != null) canceller.join(5_000);
            }
        }
    }

    @Test
    @Timeout(45)
    void thousandAcceptedMessageAndPhysicalCarrierCyclesPerActorKind() throws Exception {
        // Source-level NativeHttpTest exercises actor/isoactor under GraalVM;
        // this host test independently exercises the corresponding trusted
        // SHARED/PRIVATE scheduler carriers and the quiescence API 1,000
        // times each on the actual ActorRuntime, not a state-machine model.
        var sharedDelivered = new java.util.concurrent.atomic.AtomicInteger();
        var privateDelivered = new java.util.concurrent.atomic.AtomicInteger();
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> shared = runtime.spawnSharedTrusted(
                    context -> (message, actorContext) -> sharedDelivered.incrementAndGet());
            ActorRuntime.ActorRef<Integer> isolated = runtime.spawnPrivateTrusted(
                    context -> (message, actorContext) -> privateDelivered.incrementAndGet());

            for (int i = 1; i <= 1_000; ++i) {
                shared.send(i);
                isolated.send(i);
                runtime.quiesceAcceptedMessages(shared).get(5, TimeUnit.SECONDS);
                runtime.quiesceAcceptedMessages(isolated).get(5, TimeUnit.SECONDS);
                assertEquals(i, sharedDelivered.get(),
                        "shared actor quiescence preceded accepted mail processing");
                assertEquals(i, privateDelivered.get(),
                        "isolated actor quiescence preceded accepted mail processing");
            }
        }
    }

    @Test
    void foreignRuntimeCannotClaimQuiescenceOfAnotherActor() throws Exception {
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime foreign = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> actor =
                    owner.spawnShared(() -> (message, context) -> { });
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> foreign.quiesceAcceptedMessages(actor));
        }
    }
}
