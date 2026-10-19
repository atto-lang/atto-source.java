package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresFuture;
import dev.oreslang.runtime.OresScheduler;
import dev.oreslang.runtime.TaskHandle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime + OresScheduler interaction, not a standalone TaskScope
 * model. Run on exact-head JDK/GraalVM CI before allowing a merge.
 */
@Timeout(25)
final class ActorOwnedTaskScopeTest {
    @Test
    void actorSelfStopCancelsQueuedChildrenBeforeBodiesStart() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicInteger ran = new AtomicInteger();
            AtomicReference<TaskHandle<Integer>> childRef = new AtomicReference<>();
            CountDownLatch spawned = new CountDownLatch(1);
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                childRef.set(context.runtime().startActorOwnedTask(resume -> {
                    ran.incrementAndGet();
                    return OresScheduler.done(5);
                }));
                spawned.countDown();
                context.self().stop();
            });
            ref.send(1);
            assertTrue(spawned.await(5, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertNull(ref.done().get(3, TimeUnit.SECONDS));
            var child = childRef.get();
            assertNotNull(child);
            assertInstanceOf(TaskHandle.Cancelled.class,
                    child.getDone().get(3, TimeUnit.SECONDS));
            assertEquals(0, ran.get(),
                    "stopped actor must not execute queued source Task bodies");
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void hostStopWaitsForOwnedTaskFinallyBeforePublishingActorDone()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(2, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch childEntered = new CountDownLatch(1);
            CountDownLatch releaseChild = new CountDownLatch(1);
            AtomicInteger cleanupCount = new AtomicInteger();
            AtomicReference<TaskHandle<Integer>> childRef = new AtomicReference<>();
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> {
                childRef.set(context.runtime().startActorOwnedTask(resume -> {
                    try {
                        childEntered.countDown();
                        boolean interrupted = false;
                        for (;;) {
                            try {
                                releaseChild.await();
                                break;
                            } catch (InterruptedException ignored) {
                                interrupted = true;
                            }
                        }
                        if (interrupted) Thread.currentThread().interrupt();
                        OresScheduler.cancellationCheckpoint();
                        return OresScheduler.done(9);
                    } finally {
                        cleanupCount.incrementAndGet();
                    }
                }));
            });
            actor.send(1);
            assertTrue(childEntered.await(5, TimeUnit.SECONDS));

            AtomicReference<Throwable> stopError = new AtomicReference<>();
            Thread stopper = new Thread(() -> {
                try { actor.stop(); }
                catch (Throwable problem) { stopError.set(problem); }
            });
            stopper.start();
            try {
                TaskHandle<Integer> child = childRef.get();
                assertNotNull(child);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!child.isCancellationRequested()
                        && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertTrue(child.isCancellationRequested(),
                        "actor termination must request child cancellation");
                assertFalse(child.result().isDone(),
                        "running Task result waits for safe cancellation checkpoint");
                assertFalse(child.getDone().isDone());
                assertFalse(actor.done().isDone(),
                        "actor may not finalize while its Task is in finally");
                assertEquals(0, cleanupCount.get());
            } finally {
                releaseChild.countDown();
            }

            stopper.join(10000);
            assertFalse(stopper.isAlive());
            assertNull(stopError.get());
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
            assertNull(actor.done().get(3, TimeUnit.SECONDS));
            assertEquals(1, cleanupCount.get());
            assertInstanceOf(TaskHandle.Cancelled.class,
                    childRef.get().getDone().get(3, TimeUnit.SECONDS));
            assertTrue(childRef.get().result().isCancelled());
        }
    }

    @Test
    void actorStopCancelsSuspendedChildWithoutCancellingBorrowedFuture()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            OresFuture<Integer> awaited = new OresFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);
            AtomicReference<TaskHandle<Integer>> childRef = new AtomicReference<>();
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                if (message == 1) {
                    childRef.set(context.runtime().startActorOwnedTask(resume -> {
                        suspended.countDown();
                        return OresScheduler.await(awaited);
                    }));
                } else {
                    context.self().stop();
                }
            });
            ref.send(1);
            assertTrue(suspended.await(5, TimeUnit.SECONDS),
                    "source Task must reach await before actor stop");
            ref.send(2);
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertNull(ref.done().get(3, TimeUnit.SECONDS));
            assertInstanceOf(TaskHandle.Cancelled.class,
                    childRef.get().getDone().get(3, TimeUnit.SECONDS));
            assertFalse(awaited.isCancelled(),
                    "an actor-owned Task must not cancel a borrowed producer Future");
        }
    }

    @Test
    void actorCannotStartTrackedTaskOutsideItsExecutionTurn() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            assertThrows(IllegalStateException.class, () ->
                    runtime.startActorOwnedTask(resume -> OresScheduler.done(2)));
        }
    }

    @Test
    void afterOneTaskCompletedLaterActorStopDoesNotRewriteItsSuccess()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<TaskHandle<Integer>> childRef = new AtomicReference<>();
            CountDownLatch taskStarted = new CountDownLatch(1);
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                if (message == 1) {
                    childRef.set(context.runtime().startActorOwnedTask(resume -> {
                        taskStarted.countDown();
                        return OresScheduler.done(42);
                    }));
                } else {
                    context.self().stop();
                }
            });
            ref.send(1);
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS));
            TaskHandle<Integer> child = childRef.get();
            assertNotNull(child);
            assertEquals(new TaskHandle.Success<>(42),
                    child.getDone().get(3, TimeUnit.SECONDS));
            ref.send(2);
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(new TaskHandle.Success<>(42),
                    child.getDone().get(3, TimeUnit.SECONDS));
            assertFalse(child.isCancellationRequested());
        }
    }

    @Test
    void parentCancellationRetiresEightQueuedTaskHandles() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            java.util.concurrent.CopyOnWriteArrayList<TaskHandle<Integer>> children =
                    new java.util.concurrent.CopyOnWriteArrayList<>();
            AtomicInteger bodiesRun = new AtomicInteger();
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                for (int i = 0; i < 8; i++) {
                    children.add(context.runtime().startActorOwnedTask(resume -> {
                        bodiesRun.incrementAndGet();
                        return OresScheduler.done(1);
                    }));
                }
                context.self().stop();
            });
            ref.send(1);
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(8, children.size());
            assertEquals(0, bodiesRun.get());
            for (TaskHandle<Integer> child : children) {
                assertInstanceOf(TaskHandle.Cancelled.class,
                        child.getDone().get(3, TimeUnit.SECONDS));
            }
            assertTrue(ref.done().isDone());
        }
    }

    @Test
    void externalHostCannotForgeActorOwnedTaskAcrossActorRef() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            // Mere possession of an ActorRef cannot mint the actor's Task scope
            // or run work in its sealed actor-owned execution domain.
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> { });
            assertNotNull(ref);
            assertThrows(IllegalStateException.class, () ->
                    runtime.startActorOwnedTask(r -> OresScheduler.done(1)));
        }
    }

    @Test
    void untrustedActorCannotBypassQuotaAwareTaskAdmission() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            AtomicInteger guestTaskBodies = new AtomicInteger();
            var ref = runtime.<Integer>spawnUntrusted(() -> (message, context) -> {
                assertThrows(SecurityException.class, () ->
                        context.runtime().startActorOwnedTask(resume -> {
                            guestTaskBodies.incrementAndGet();
                            return OresScheduler.done(1);
                        }));
                context.self().stop();
            });
            ref.send(1);
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(0, guestTaskBodies.get());
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void actorFailureCancelsOwnedTasksAndPreservesActorFailure() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<TaskHandle<Integer>> childRef = new AtomicReference<>();
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                childRef.set(context.runtime().startActorOwnedTask(resume ->
                        OresScheduler.done(7)));
                throw new IllegalStateException("actor handler failed");
            });
            ref.send(1);
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertNotNull(childRef.get());
            assertInstanceOf(TaskHandle.Cancelled.class,
                    childRef.get().getDone().get(3, TimeUnit.SECONDS));
            assertTrue(ref.failure().isPresent(),
                    "actor failure must not be swallowed by Task cancellation");
            assertInstanceOf(IllegalStateException.class, ref.failure().orElseThrow());
        }
    }

    @Test
    void hostStopCannotForgeCompletionBeforeActorTaskScopeTeardown()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<TaskHandle<Integer>> childRef = new AtomicReference<>();
            CountDownLatch accepted = new CountDownLatch(1);
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                childRef.set(context.runtime().startActorOwnedTask(resume ->
                        OresScheduler.done(9)));
                accepted.countDown();
            });
            ref.send(1);
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            // A host stop may race the queued child continuation, but must not
            // expose ActorRef.done before all admitted tracked tasks are done.
            ref.stop();
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(childRef.get().getDone().isDone());
            assertTrue(ref.done().isDone());
        }
    }
}
