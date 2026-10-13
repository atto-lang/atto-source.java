package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Mailbox admission, processed barriers, and graceful close are distinct milestones. */
@Timeout(20)
final class ActorSendContractTest {
    @Test
    void startupFactoryCannotDrainMailAdmittedWhileItWasRunning() throws Exception {
        CountDownLatch startupEntered = new CountDownLatch(1);
        CountDownLatch letStartupFinish = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<OresFuture<Void>> startupSelfDrain = new AtomicReference<>();
        AtomicInteger handled = new AtomicInteger();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnSharedTrusted(context -> {
                startupEntered.countDown();
                if (!letStartupFinish.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("startup gate timed out");
                // No activeMail exists during actor factory initialization,
                // but a queued send still cannot complete until startup yields.
                startupSelfDrain.set(context.self().drain());
                observed.countDown();
                return (message, selfContext) -> handled.addAndGet(message);
            });
            assertTrue(startupEntered.await(5, TimeUnit.SECONDS));
            ref.send(7);
            letStartupFinish.countDown();
            assertTrue(observed.await(5, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> startupSelfDrain.get().get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("own active mailbox"));
            ref.drain().get(5, TimeUnit.SECONDS);
            assertEquals(7, handled.get());
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            letStartupFinish.countDown();
        }
    }

    @Test
    void actorCannotAwaitItsOwnActiveDrainButCanGracefullyStopAfterTurn() throws Exception {
        AtomicReference<OresFuture<Void>> forbiddenSelfDrain = new AtomicReference<>();
        CountDownLatch observed = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                forbiddenSelfDrain.set(context.self().drain());
                // A self shutdown is different: it must seal now but only
                // terminate once the active receive completes.
                context.self().gracefulStop();
                observed.countDown();
            });
            ref.send(1);
            assertTrue(observed.await(5, TimeUnit.SECONDS));
            OresFuture<Void> rejected = forbiddenSelfDrain.get();
            assertNotNull(rejected);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> rejected.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("own active mailbox"));
            ref.getDone().get(5, TimeUnit.SECONDS);
            assertFalse(ref.isAlive());
        }
    }

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

}
