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
