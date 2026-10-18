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
