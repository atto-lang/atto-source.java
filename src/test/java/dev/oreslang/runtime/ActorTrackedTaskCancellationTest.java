package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for the compiler's tracked actor-task admission bridge.
 *
 * A Future producer can settle the awaited value, but it cannot run guest
 * code, acquire an actor lease, or complete the Task's physical done barrier.
 */
@Timeout(30)
final class ActorTrackedTaskCancellationTest {

    @Test
    void trackedActorTaskCancellationRetiresBeforeLateForeignCompletion() throws Exception {
        OresFuture<Integer> foreignFuture = new OresFuture<>();
        AtomicReference<TaskHandle<Integer>> tracked = new AtomicReference<>();
        CountDownLatch suspended = new CountDownLatch(1);
        AtomicInteger guestTurns = new AtomicInteger();
        AtomicInteger controlCalls = new AtomicInteger();

        ActorRuntime.ControlDispatcher denyControl = turn -> {
            controlCalls.incrementAndGet();
            throw new AssertionError("actor task must never run on supervisor CONTROL");
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 128),
                ActorRuntime.TurnExecutor.direct(),
                denyControl)) {
            var actor = runtime.<String>spawnShared(
                    IsolatePolicy.developer(),
                    () -> (message, context) -> {
                        if ("stop".equals(message)) {
                            context.self().stop();
                            return;
                        }
                        assertEquals("start", message);
                        tracked.set(context.runtime().startTrackedActorTask(resume -> {
                            assertTrue(ActorRuntime.isActorCarrierThread());
                            assertTrue(ActorRuntime.inActorExecution());
                            assertSame(runtime, ActorRuntime.currentActorRuntime());
                            assertEquals(context.self().id(),
                                    ActorRuntime.currentActorId().orElseThrow());
                            assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                            guestTurns.incrementAndGet();
                            if (resume.initial()) {
                                suspended.countDown();
                                return OresScheduler.await(foreignFuture);
                            }
                            return OresScheduler.done(99);
                        }));
                    });

            try {
                actor.ready().get(4, TimeUnit.SECONDS);
                actor.send("start");
                assertTrue(suspended.await(4, TimeUnit.SECONDS),
                        "tracked actor task did not suspend on the actor pool");
                TaskHandle<Integer> task = tracked.get();
                assertNotNull(task);
                assertFalse(task.result().isDone());
                assertFalse(task.getDone().isDone());
                assertEquals(1, guestTurns.get());

                assertTrue(task.cancel("test actor task cancellation"));
                assertInstanceOf(TaskHandle.Cancelled.class,
                        task.getDone().get(4, TimeUnit.SECONDS),
                        "getDone must wait for the actor-owned scheduler to retire the task");

                Thread producer = new Thread(() -> {
                    assertFalse(ActorRuntime.inActorExecution());
                    assertNull(OresScheduler.current());
                    assertTrue(foreignFuture.completeFromRuntime(42));
                    assertFalse(ActorRuntime.inActorExecution());
                }, "late-foreign-future-completion");
                producer.start();
                producer.join(4000);
                assertFalse(producer.isAlive());
                assertEquals(1, guestTurns.get(),
                        "late Future completion must not reenter a retired actor task");
                assertEquals(0, controlCalls.get(),
                        "CONTROL is not the actor task execution pool");

                actor.send("stop");
                actor.done().get(4, TimeUnit.SECONDS);
                assertTrue(actor.failure().isEmpty(), () -> String.valueOf(actor.failure()));
            } finally {
                foreignFuture.completeFromRuntime(42);
                actor.cancel();
            }
        }
    }

    @Test
    void trackedActorTaskCannotBeMintedByAnExternalThread() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            assertThrows(IllegalStateException.class,
                    () -> runtime.startTrackedActorTask(
                            resume -> OresScheduler.done(1)));
        }
    }
}
