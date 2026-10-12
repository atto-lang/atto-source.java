package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Mailbox admission, processed barriers, and graceful close are distinct milestones. */
@Timeout(20)
final class ActorSendContractTest {
    @Test
    void actorRefIsOpaqueGenerationBoundAndSendAfterStopIsTyped() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicInteger count = new AtomicInteger();
            var ref = runtime.<Integer>spawnShared(() -> (message, context) ->
                    count.addAndGet(message));
            assertTrue(ref.generation() > 0);
            assertEquals(ref.id(), ref.address().actorId());
            assertEquals(ref.generation(), ref.address().generation());
            assertEquals("local-process", ref.address().transport());
            ref.send(1);
            ref.send(2);
            ref.drain().get(5, TimeUnit.SECONDS);
            assertEquals(3, count.get());
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
            ActorRuntime.ActorTerminatedException failure = assertThrows(
                    ActorRuntime.ActorTerminatedException.class, () -> ref.send(4));
            assertEquals(ActorRuntime.SendError.Reason.TERMINATED, failure.reason());
        }
    }

    @Test
    void gracefulStopSealsAdmissionsButWaitsForExistingMail() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicInteger processed = new AtomicInteger();
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("test actor timed out");
                processed.incrementAndGet();
            });
            ref.send(42);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> barrier = ref.drain();
            assertFalse(barrier.isDone());
            OresFuture<Void> done = ref.gracefulStop();
            ActorRuntime.ActorSealedException rejected = assertThrows(
                    ActorRuntime.ActorSealedException.class, () -> ref.send(43));
            assertEquals(ActorRuntime.SendError.Reason.SEALED, rejected.reason());
            assertFalse(done.isDone());
            release.countDown();
            barrier.get(5, TimeUnit.SECONDS);
            done.get(5, TimeUnit.SECONDS);
            assertEquals(1, processed.get());
            assertFalse(ref.isAlive());
        } finally {
            release.countDown();
        }
    }

    @Test
    void abruptStopFailsOutstandingDrainRatherThanFabricatingProcessing() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            });
            ref.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            ref.send(2);
            OresFuture<Void> barrier = ref.drain();
            // stop cannot wait synchronously while the test deliberately holds
            // the guest turn. Unblock the carrier from another host thread.
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                release.countDown();
            });
            releaser.start();
            ref.stop();
            releaser.join();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> barrier.get(5, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void pendingDrainsHavePerActorQuotaAndGracefulStopKeepsReservedSlot() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("test actor timed out");
            });
            ref.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            List<OresFuture<Void>> observers = new ArrayList<>();
            for (int i = 0; i < 1_024; i++) {
                OresFuture<Void> drain = ref.drain();
                assertFalse(drain.isDone(), "actor has not processed its held message");
                observers.add(drain);
            }
            ExecutionException exhausted = assertThrows(ExecutionException.class,
                    () -> ref.drain().get(5, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorResourceExhaustedException.class,
                    exhausted.getCause());
            assertEquals(ActorRuntime.SendError.Reason.RESOURCE_EXHAUSTED,
                    ((ActorRuntime.ActorResourceExhaustedException) exhausted.getCause()).reason());

            // Exhausted public observers must not strand the actor in SEALED.
            OresFuture<Void> done = ref.gracefulStop();
            assertSame(done, ref.gracefulStop(), "repeated gracefulStop reuses termination");
            assertThrows(ActorRuntime.ActorSealedException.class, () -> ref.send(2));
            release.countDown();
            for (OresFuture<Void> observer : observers)
                observer.get(5, TimeUnit.SECONDS);
            done.get(5, TimeUnit.SECONDS);
            assertFalse(ref.isAlive());
        } finally {
            release.countDown();
        }
    }

    @Test
    void crossRuntimeRefDoesNotRouteAsSharedAddress() {
        try (ActorRuntime left = new ActorRuntime();
             ActorRuntime right = new ActorRuntime()) {
            var ref = left.<Integer>spawnPrivate(() -> (message, context) -> { });
            ActorRuntime.ActorInvalidReferenceException failure = assertThrows(
                    ActorRuntime.ActorInvalidReferenceException.class,
                    () -> right.send(ref, 7));
            assertEquals(ActorRuntime.SendError.Reason.INVALID_REFERENCE, failure.reason());
            assertNotEquals(ref.address().runtimeId(), 
                    right.<Integer>spawnPrivate(() -> (message, context) -> { })
                            .address().runtimeId());
        }
    }
    @Test
    void failedDrainObserverCallbacksRunOutsideActorLifecycleMonitor() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            });
            ref.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            // Test the runtime's lock-order contract directly. A failed
            // OresFuture invokes its registered runtime callbacks synchronously.
            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            var actors = (java.util.Map<?, ?>) actorsField.get(runtime);
            Object cell = actors.get(ref.id());
            assertNotNull(cell);
            var monitorField = cell.getClass().getDeclaredField("lifecycleLock");
            monitorField.setAccessible(true);
            Object lifecycleMonitor = monitorField.get(cell);

            AtomicInteger callbacksUnderLock = new AtomicInteger();
            CountDownLatch callbackObserved = new CountDownLatch(1);
            OresFuture<Void> pending = ref.drain();
            assertFalse(pending.isDone());
            pending.whenCompleteRuntime((ignored, failure) -> {
                if (Thread.holdsLock(lifecycleMonitor)) callbacksUnderLock.incrementAndGet();
                callbackObserved.countDown();
            });

            Thread stopper = new Thread(ref::stop, "actor-drain-monitor-regression");
            stopper.start();
            try {
                assertTrue(callbackObserved.await(5, TimeUnit.SECONDS),
                        "stopping an actor must fail pending drains");
                assertEquals(0, callbacksUnderLock.get(),
                        "runtime callbacks must never run inside lifecycleLock");
            } finally {
                release.countDown();
                stopper.join(5_000);
            }
            assertFalse(stopper.isAlive());
        } finally {
            release.countDown();
        }
    }

    @Test
    void exhaustedActorGenerationDoesNotLeakReservedCapacity() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var generationField = ActorRuntime.class.getDeclaredField("nextActorGeneration");
            var actorCountField = ActorRuntime.class.getDeclaredField("actorCount");
            generationField.setAccessible(true);
            actorCountField.setAccessible(true);
            var generations = (java.util.concurrent.atomic.AtomicLong)
                    generationField.get(runtime);
            var liveCount = (java.util.concurrent.atomic.AtomicInteger)
                    actorCountField.get(runtime);
            int baseline = liveCount.get();
            long originalGeneration = generations.get();
            try {
                // Force the fail-closed incarnation exhaustion branch without
                // waiting for a naturally impossible 2^63 actor allocations.
                generations.set(0L);
                IllegalStateException exhausted = assertThrows(
                        IllegalStateException.class,
                        () -> runtime.<Integer>spawnShared(
                                () -> (message, context) -> { }));
                assertTrue(exhausted.getMessage().contains("generation sequence exhausted"));
                assertEquals(baseline, liveCount.get(),
                        "failed actor construction must release its reserved slot");
            } finally {
                generations.set(originalGeneration);
            }
            var recovered = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            recovered.getReady().get(5, TimeUnit.SECONDS);
            recovered.stop();
            recovered.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void terminationFuturesPublishOutsideLifecycleMonitorExactlyOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("held actor timed out");
                }
            });
            ref.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((java.util.Map<?, ?>) actorsField.get(runtime)).get(ref.id());
            assertNotNull(cell);
            var lockField = cell.getClass().getDeclaredField("lifecycleLock");
            lockField.setAccessible(true);
            Object actorMonitor = lockField.get(cell);

            AtomicInteger callbackCount = new AtomicInteger();
            AtomicInteger callbackWhileLocked = new AtomicInteger();
            CountDownLatch observed = new CountDownLatch(1);
            ref.getDone().whenCompleteRuntime((value, error) -> {
                callbackCount.incrementAndGet();
                if (Thread.holdsLock(actorMonitor)) callbackWhileLocked.incrementAndGet();
                observed.countDown();
            });

            Thread first = new Thread(ref::stop, "actor-finalize-first-stop");
            Thread second = new Thread(ref::stop, "actor-finalize-racing-stop");
            first.start();
            second.start();
            try {
                assertFalse(observed.await(75, TimeUnit.MILLISECONDS),
                        "a running actor may not publish DONE before carrier exit");
            } finally {
                release.countDown();
                first.join(5_000);
                second.join(5_000);
            }
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertTrue(observed.await(5, TimeUnit.SECONDS));
            assertEquals(0, callbackWhileLocked.get(),
                    "DONE must be delivered after releasing actor lifecycle monitor");
            assertEquals(1, callbackCount.get(), "DONE must settle exactly once");
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void initializationFailureReadyCallbacksNeverHoldActorMonitor() throws Exception {
        CountDownLatch enteredFactory = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> {
                enteredFactory.countDown();
                try {
                    if (!releaseFactory.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("factory timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("factory interrupted", interrupted);
                }
                throw new IllegalStateException("expected initialization rejection");
            });

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((java.util.Map<?, ?>) actorsField.get(runtime)).get(ref.id());
            assertNotNull(cell);
            var lockField = cell.getClass().getDeclaredField("lifecycleLock");
            lockField.setAccessible(true);
            Object actorMonitor = lockField.get(cell);

            AtomicInteger callbackWhileLocked = new AtomicInteger();
            AtomicInteger readyCount = new AtomicInteger();
            CountDownLatch readyObserved = new CountDownLatch(1);
            ref.getReady().whenCompleteRuntime((value, error) -> {
                readyCount.incrementAndGet();
                if (Thread.holdsLock(actorMonitor)) callbackWhileLocked.incrementAndGet();
                readyObserved.countDown();
            });
            try {
                assertTrue(enteredFactory.await(5, TimeUnit.SECONDS));
                assertFalse(readyObserved.await(30, TimeUnit.MILLISECONDS));
            } finally {
                releaseFactory.countDown();
            }
            assertTrue(readyObserved.await(5, TimeUnit.SECONDS));
            assertEquals(0, callbackWhileLocked.get());
            assertEquals(1, readyCount.get());
            assertThrows(ExecutionException.class, () -> ref.getReady().get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> ref.getDone().get(5, TimeUnit.SECONDS));
        } finally {
            releaseFactory.countDown();
        }
    }

    @Test
    void parentChildStopPublishesReceiptsWithoutNestedActorMonitors() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            java.util.concurrent.atomic.AtomicReference<ActorRuntime.ActorRef<Integer>> childRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            CountDownLatch childCreated = new CountDownLatch(1);
            var parent = runtime.<Integer>spawnShared(() -> (message, context) -> {
                var child = context.runtime().<Integer>spawnShared(
                        () -> (childMessage, childContext) -> { });
                childRef.set(child);
                childCreated.countDown();
            });
            parent.send(1);
            assertTrue(childCreated.await(5, TimeUnit.SECONDS),
                    () -> "child spawn never completed; parent failure: "
                            + parent.failure() + ", parent done: " + parent.getDone().isDone());
            var child = childRef.get();
            assertNotNull(child);

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            var actors = (java.util.Map<?, ?>) actorsField.get(runtime);
            Object parentCell = actors.get(parent.id());
            Object childCell = actors.get(child.id());
            assertNotNull(parentCell);
            assertNotNull(childCell);
            var monitorField = parentCell.getClass().getDeclaredField("lifecycleLock");
            monitorField.setAccessible(true);
            Object parentMonitor = monitorField.get(parentCell);
            Object childMonitor = monitorField.get(childCell);

            AtomicInteger callbacksUnderMonitor = new AtomicInteger();
            AtomicInteger parentReceipts = new AtomicInteger();
            AtomicInteger childReceipts = new AtomicInteger();
            CountDownLatch settled = new CountDownLatch(2);
            parent.getDone().whenCompleteRuntime((value, error) -> {
                if (Thread.holdsLock(parentMonitor) || Thread.holdsLock(childMonitor)) {
                    callbacksUnderMonitor.incrementAndGet();
                }
                parentReceipts.incrementAndGet();
                settled.countDown();
            });
            child.getDone().whenCompleteRuntime((value, error) -> {
                if (Thread.holdsLock(parentMonitor) || Thread.holdsLock(childMonitor)) {
                    callbacksUnderMonitor.incrementAndGet();
                }
                childReceipts.incrementAndGet();
                settled.countDown();
            });

            Thread first = new Thread(parent::stop, "parent-stop-one");
            Thread second = new Thread(parent::stop, "parent-stop-two");
            first.start();
            second.start();
            first.join(5_000);
            second.join(5_000);
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertTrue(settled.await(5, TimeUnit.SECONDS),
                    "child and parent finalization receipts must both settle");
            assertEquals(0, callbacksUnderMonitor.get(),
                    "child/parent DONE callbacks must never hold either lifecycle monitor");
            assertEquals(1, parentReceipts.get());
            assertEquals(1, childReceipts.get());
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(child.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

}
