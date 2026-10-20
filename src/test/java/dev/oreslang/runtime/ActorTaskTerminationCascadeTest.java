package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Exact-runtime actor shutdown ordering regression; no synthetic model. */
@Timeout(20)
final class ActorTaskTerminationCascadeTest {
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Test
    void exitHookCanRequestOwnActorStopWithoutWaitingOnItself() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<ActorRuntime.ActorRef<Integer>> owned =
                    new AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger callbacks =
                    new java.util.concurrent.atomic.AtomicInteger();
            runtime.setActorExitHook(ignored -> {
                callbacks.incrementAndGet();
                // The hook executes on the actor's terminal host finalizer.
                // stop() must not wait for this same finalizer's done witness.
                owned.get().stop();
            });
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> context.self().stop());
            owned.set(actor);
            actor.send(1);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(actor.done().get(3, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
        }
    }

    @Test
    void exitHookCannotHoldActorLifecycleMonitorWhileBlocked() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch exitHookEntered = new CountDownLatch(1);
            CountDownLatch releaseHook = new CountDownLatch(1);
            runtime.setActorExitHook(ignored -> {
                exitHookEntered.countDown();
                awaitUninterruptibly(releaseHook);
            });
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> context.self().stop());
            actor.send(1);
            assertTrue(exitHookEntered.await(5, TimeUnit.SECONDS));

            // awaitTermination must acquire the lifecycle monitor to wait
            // with a bounded timeout. Before the finalizer refactor, a
            // blocked host exit hook held that monitor indefinitely.
            AtomicReference<Boolean> probeResult = new AtomicReference<>();
            AtomicReference<Throwable> probeFailure = new AtomicReference<>();
            Thread probe = new Thread(() -> {
                try {
                    probeResult.set(actor.awaitTermination(50, TimeUnit.MILLISECONDS));
                } catch (Throwable failure) {
                    probeFailure.set(failure);
                }
            });
            probe.start();
            try {
                probe.join(1000);
                assertFalse(probe.isAlive(),
                        "a blocked exit hook must not pin lifecycleLock");
                assertNull(probeFailure.get());
                assertEquals(Boolean.FALSE, probeResult.get());
                assertFalse(actor.done().isDone());
            } finally {
                releaseHook.countDown();
            }
            probe.join(5000);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(actor.done().get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void oneFinalizerForConcurrentActorStopRequests() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            java.util.concurrent.atomic.AtomicInteger exitHookCount =
                    new java.util.concurrent.atomic.AtomicInteger();
            CountDownLatch hookEntered = new CountDownLatch(1);
            CountDownLatch releaseHook = new CountDownLatch(1);
            runtime.setActorExitHook(ignored -> {
                exitHookCount.incrementAndGet();
                hookEntered.countDown();
                awaitUninterruptibly(releaseHook);
            });
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            assertNull(actor.ready().get(5, TimeUnit.SECONDS));
            java.util.concurrent.atomic.AtomicReference<Throwable> failures =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread a = new Thread(() -> {
                try { actor.stop(); }
                catch (Throwable failure) { failures.compareAndSet(null, failure); }
            });
            Thread b = new Thread(() -> {
                try { actor.stop(); }
                catch (Throwable failure) { failures.compareAndSet(null, failure); }
            });
            a.start();
            b.start();
            try {
                assertTrue(hookEntered.await(5, TimeUnit.SECONDS));
                assertFalse(actor.done().isDone());
                assertEquals(1, exitHookCount.get(),
                        "only one teardown may claim the terminal actor state");
            } finally {
                releaseHook.countDown();
            }
            a.join(5000);
            b.join(5000);
            assertFalse(a.isAlive());
            assertFalse(b.isAlive());
            assertNull(failures.get());
            assertEquals(1, exitHookCount.get());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void successfulAwaitTerminationRequiresPublishedDoneWitness() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(2, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            // Registry removal and finalizer callback publication occur on
            // separate operations. The successful blocking wait must target
            // doneFuture, never assume map absence means done was published.
            for (int iteration = 0; iteration < 100; iteration++) {
                var ref = runtime.<Integer>spawnShared(
                        () -> (message, context) -> context.self().stop());
                ref.send(1);
                assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS),
                        "termination timed out at iteration " + iteration);
                assertTrue(ref.done().isDone(),
                        "awaitTermination must not return true before getDone settles");
                assertNull(ref.done().get(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void failingActorAwaitTerminationStillObservesFailedDoneWitness() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                throw new IllegalStateException("expected failure");
            });
            ref.send(1);
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.done().isDone());
            assertTrue(ref.done().isCompletedExceptionally());
            assertInstanceOf(IllegalStateException.class, ref.failure().orElseThrow());
        }
    }

    @Test
    void zeroDurationAwaitCannotForgeFinalizationWhileExitHookBlocks()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch enteredHook = new CountDownLatch(1);
            CountDownLatch releaseHook = new CountDownLatch(1);
            runtime.setActorExitHook(ignored -> {
                enteredHook.countDown();
                awaitUninterruptibly(releaseHook);
            });
            var ref = runtime.<Integer>spawnShared(
                    () -> (message, context) -> context.self().stop());
            ref.send(1);
            try {
                assertTrue(enteredHook.await(5, TimeUnit.SECONDS));
                assertFalse(ref.awaitTermination(0, TimeUnit.NANOSECONDS));
                assertFalse(ref.done().isDone());
            } finally {
                releaseHook.countDown();
            }
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.done().isDone());
        }
    }

    @Test
    @Timeout(60)
    void boundedShutdownStormCompletesEveryActorExactlyOnce() throws Exception {
        // Burst actor termination without allocating a dedicated OS thread
        // for each one. Each actor has its own finalization witness.
        var config = new ActorRuntime.DispatcherConfig(4, 4, 512, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            java.util.concurrent.atomic.AtomicInteger exitCount =
                    new java.util.concurrent.atomic.AtomicInteger();
            runtime.setActorExitHook(ignored -> exitCount.incrementAndGet());
            for (int burst = 0; burst < 8; burst++) {
                java.util.ArrayList<ActorRuntime.ActorRef<Integer>> refs =
                        new java.util.ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    refs.add(runtime.<Integer>spawnShared(
                            () -> (message, context) -> context.self().stop()));
                }
                for (var ref : refs) ref.send(1);
                for (var ref : refs) {
                    assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS),
                            "actor did not reach its final witness during burst " + burst);
                    assertTrue(ref.done().isDone(),
                            "successful termination must have a settled done witness");
                    assertNull(ref.done().get(2, TimeUnit.SECONDS));
                }
                assertEquals((burst + 1) * 32, exitCount.get(),
                        "exit hooks must run exactly once per actor");
            }
        }
    }

    @Test
    void noTaskScopeActorStillWaitsForOwnedFutureCancellationCallbacks()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            OresFuture<Integer> pending = new OresFuture<>();
            CountDownLatch admitted = new CountDownLatch(1);
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> {
                // Deliberately never create an actor-owned TaskScope.
                context.runtime().ownCurrentActorFuture(pending);
                admitted.countDown();
            });
            actor.send(1);
            assertTrue(admitted.await(5, TimeUnit.SECONDS));

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            pending.whenCompleteRuntime((value, failure) -> {
                entered.countDown();
                awaitUninterruptibly(release);
            });

            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            Thread stopper = new Thread(() -> {
                try { actor.stop(); }
                catch (Throwable failure) { stopFailure.set(failure); }
            });
            stopper.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS),
                        "owned-Future cancellation must reach its callback");
                assertFalse(actor.done().isDone(),
                        "actor without a TaskScope cannot bypass pending callback teardown");
                assertFalse(actor.awaitTermination(0, TimeUnit.NANOSECONDS));
            } finally {
                release.countDown();
            }
            stopper.join(5000);
            assertFalse(stopper.isAlive());
            assertNull(stopFailure.get());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.done().isDone());
        }
    }

    @Test
    void finalizerPreservesFirstResourceFailureAndRecordsEveryLaterFailure() {
        IllegalStateException first = new IllegalStateException("output teardown");
        IllegalArgumentException memory = new IllegalArgumentException("slice release");
        RuntimeException registry = new RuntimeException("deregistration");

        Throwable aggregate = ActorRuntime.appendCleanupFailure(null, first);
        assertSame(first, aggregate);
        aggregate = ActorRuntime.appendCleanupFailure(aggregate, memory);
        aggregate = ActorRuntime.appendCleanupFailure(aggregate, registry);
        assertSame(first, aggregate,
                "later cleanup exceptions must not erase the initial error");
        assertArrayEquals(new Throwable[] { memory, registry },
                aggregate.getSuppressed(),
                "teardown should retain independent resource failures");
    }

    @Test
    void finalizerNeverSelfSuppressesOrAcceptsNullCleanupFailure() {
        RuntimeException failure = new RuntimeException("cleanup");
        assertSame(failure, ActorRuntime.appendCleanupFailure(failure, failure));
        assertEquals(0, failure.getSuppressed().length);
        assertThrows(NullPointerException.class,
                () -> ActorRuntime.appendCleanupFailure(failure, null));
    }

    @Test
    void actorDoneWaitsForOwnedFutureCancellationCallbacksAfterTaskScopeJoin()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            OresFuture<Integer> pending = new OresFuture<>();
            AtomicReference<TaskHandle<Integer>> child = new AtomicReference<>();
            CountDownLatch admitted = new CountDownLatch(1);
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                context.runtime().ownCurrentActorFuture(pending);
                child.set(context.runtime().startActorOwnedTask(r -> OresScheduler.done(3)));
                admitted.countDown();
            });
            ref.send(1);
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            assertEquals(new TaskHandle.Success<>(3),
                    child.get().getDone().get(5, TimeUnit.SECONDS));

            CountDownLatch callbackEntered = new CountDownLatch(1);
            CountDownLatch releaseCallback = new CountDownLatch(1);
            pending.whenCompleteRuntime((value, failure) -> {
                callbackEntered.countDown();
                awaitUninterruptibly(releaseCallback);
            });

            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            Thread stopper = new Thread(() -> {
                try {
                    ref.stop();
                } catch (Throwable error) {
                    stopFailure.set(error);
                }
            });
            stopper.start();
            try {
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS),
                        "owned Future cancellation callback must run");
                assertTrue(pending.isCancelled());
                assertFalse(ref.done().isDone(),
                        "actor done must wait for termination cascade subscriber dispatch");
            } finally {
                releaseCallback.countDown();
            }
            stopper.join(5000);
            assertFalse(stopper.isAlive());
            if (stopFailure.get() != null) throw new AssertionError(stopFailure.get());
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(ref.done().get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void twoConcurrentStopRequestsCannotEndCascadePrematurely()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            OresFuture<Integer> pending = new OresFuture<>();
            AtomicReference<TaskHandle<Integer>> child = new AtomicReference<>();
            CountDownLatch admitted = new CountDownLatch(1);
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                context.runtime().ownCurrentActorFuture(pending);
                child.set(context.runtime().startActorOwnedTask(r -> OresScheduler.done(3)));
                admitted.countDown();
            });
            ref.send(1);
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            child.get().getDone().get(5, TimeUnit.SECONDS);

            CountDownLatch blocked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            pending.whenCompleteRuntime((value, failure) -> {
                blocked.countDown();
                awaitUninterruptibly(release);
            });

            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread first = new Thread(() -> {
                try { ref.stop(); } catch (Throwable failure) { error.set(failure); }
            });
            first.start();
            try {
                assertTrue(blocked.await(5, TimeUnit.SECONDS));
                Thread second = new Thread(() -> {
                    try { ref.stop(); } catch (Throwable failure) { error.set(failure); }
                });
                second.start();
                // Both host stops must leave the actor finalization witness
                // unpublished while the initial pending Future callback blocks.
                assertFalse(ref.done().isDone());
                release.countDown();
                first.join(5000);
                second.join(5000);
                assertFalse(first.isAlive());
                assertFalse(second.isAlive());
            } finally {
                release.countDown();
            }
            if (error.get() != null) throw new AssertionError(error.get());
            assertTrue(ref.done().isDone());
        }
    }
}
