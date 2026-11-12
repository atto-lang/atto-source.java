package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actor-local child Tasks must inherit the executing tracked Task's lifetime,
 * not ROOT_TASK or supervisor CONTROL. All waits in these tests are host-side:
 * actor carriers themselves only yield through OresScheduler.await(...).
 */
@Timeout(30)
final class ActorNestedTaskLifetimeTest {

    @Test
    void terminalParentResultDoesNotForgePhysicalChildQuiescence() throws Exception {
        OresFuture<Integer> childGate = new OresFuture<>();
        AtomicReference<TaskHandle<Integer>> parent = new AtomicReference<>();
        AtomicReference<TaskHandle<Integer>> child = new AtomicReference<>();
        CountDownLatch childSuspended = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<String>spawnShared(
                    IsolatePolicy.developer(),
                    () -> (message, context) -> {
                        if ("stop".equals(message)) {
                            context.self().stop();
                            return;
                        }
                        assertEquals("start", message);
                        parent.set(context.runtime().startTrackedActorTask(resume -> {
                            assertTrue(resume.initial());
                            assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                            child.set(context.runtime().startTrackedActorTask(nested -> {
                                assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                                if (nested.initial()) {
                                    childSuspended.countDown();
                                    return OresScheduler.await(childGate);
                                }
                                return OresScheduler.done(17);
                            }));
                            return OresScheduler.done(11);
                        }));
                    });
            try {
                actor.ready().get(5, TimeUnit.SECONDS);
                actor.send("start");
                assertTrue(childSuspended.await(5, TimeUnit.SECONDS),
                        "nested child never suspended");
                TaskHandle<Integer> outer = parent.get();
                TaskHandle<Integer> inner = child.get();
                assertNotNull(outer);
                assertNotNull(inner);
                assertEquals(11, outer.result().get(5, TimeUnit.SECONDS));
                assertFalse(outer.getDone().isDone(),
                        "parent physical DONE must join the suspended nested child");
                assertFalse(inner.getDone().isDone());

                assertTrue(childGate.completeFromRuntime(1));
                assertEquals(17, inner.result().get(5, TimeUnit.SECONDS));
                assertInstanceOf(TaskHandle.Success.class,
                        inner.getDone().get(5, TimeUnit.SECONDS));
                assertInstanceOf(TaskHandle.Success.class,
                        outer.getDone().get(5, TimeUnit.SECONDS));

                actor.send("stop");
                actor.done().get(5, TimeUnit.SECONDS);
                assertTrue(actor.failure().isEmpty(), () -> String.valueOf(actor.failure()));
            } finally {
                childGate.completeFromRuntime(1);
                actor.cancel();
            }
        }
    }

    @Test
    void cancellingParentRevokesNestedTaskWithoutForeignProducerReentry() throws Exception {
        OresFuture<Integer> parentGate = new OresFuture<>();
        OresFuture<Integer> childGate = new OresFuture<>();
        AtomicReference<TaskHandle<Integer>> parent = new AtomicReference<>();
        AtomicReference<TaskHandle<Integer>> child = new AtomicReference<>();
        CountDownLatch childSuspended = new CountDownLatch(1);
        AtomicInteger childTurns = new AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<String>spawnShared(
                    IsolatePolicy.developer(),
                    () -> (message, context) -> {
                        if ("stop".equals(message)) {
                            context.self().stop();
                            return;
                        }
                        assertEquals("start", message);
                        parent.set(context.runtime().startTrackedActorTask(resume -> {
                            assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                            if (resume.initial()) {
                                child.set(context.runtime().startTrackedActorTask(nested -> {
                                    assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                                    childTurns.incrementAndGet();
                                    if (nested.initial()) {
                                        childSuspended.countDown();
                                        return OresScheduler.await(childGate);
                                    }
                                    return OresScheduler.done(17);
                                }));
                                return OresScheduler.await(parentGate);
                            }
                            return OresScheduler.done(11);
                        }));
                    });
            try {
                actor.ready().get(5, TimeUnit.SECONDS);
                actor.send("start");
                assertTrue(childSuspended.await(5, TimeUnit.SECONDS));
                TaskHandle<Integer> outer = parent.get();
                TaskHandle<Integer> inner = child.get();
                assertNotNull(outer);
                assertNotNull(inner);
                assertTrue(outer.cancel("cancel parent and descendants"));

                assertInstanceOf(TaskHandle.Cancelled.class,
                        inner.getDone().get(5, TimeUnit.SECONDS),
                        "parent cancellation must propagate to the nested child");
                assertInstanceOf(TaskHandle.Cancelled.class,
                        outer.getDone().get(5, TimeUnit.SECONDS),
                        "parent DONE joins physically retired descendants");

                Thread producer = new Thread(() -> {
                    assertFalse(ActorRuntime.inActorExecution());
                    assertTrue(childGate.completeFromRuntime(1));
                    assertTrue(parentGate.completeFromRuntime(1));
                    assertFalse(ActorRuntime.inActorExecution());
                }, "late-actor-task-producer");
                producer.start();
                producer.join(5000);
                assertFalse(producer.isAlive());
                assertEquals(1, childTurns.get(),
                        "late producer completion cannot resume a cancelled actor child");

                actor.send("stop");
                actor.done().get(5, TimeUnit.SECONDS);
                assertTrue(actor.failure().isEmpty(), () -> String.valueOf(actor.failure()));
            } finally {
                childGate.completeFromRuntime(1);
                parentGate.completeFromRuntime(1);
                actor.cancel();
            }
        }
    }

    @Test
    void actorStopRetiresNestedTasksBeforeDoneAndRejectsNewAdmission() throws Exception {
        OresFuture<Integer> parentGate = new OresFuture<>();
        OresFuture<Integer> childGate = new OresFuture<>();
        AtomicReference<TaskHandle<Integer>> parent = new AtomicReference<>();
        AtomicReference<TaskHandle<Integer>> child = new AtomicReference<>();
        CountDownLatch childSuspended = new CountDownLatch(1);
        AtomicInteger guestTurns = new AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<String>spawnShared(
                    IsolatePolicy.developer(),
                    () -> (message, context) -> {
                        if ("stop".equals(message)) {
                            context.self().stop();
                            assertThrows(RuntimeException.class,
                                    () -> context.runtime().startTrackedActorTask(
                                            resume -> OresScheduler.done(999)),
                                    "STOP must seal actor Task admission in the same turn");
                            return;
                        }
                        assertEquals("start", message);
                        parent.set(context.runtime().startTrackedActorTask(resume -> {
                            guestTurns.incrementAndGet();
                            if (resume.initial()) {
                                child.set(context.runtime().startTrackedActorTask(nested -> {
                                    guestTurns.incrementAndGet();
                                    if (nested.initial()) {
                                        childSuspended.countDown();
                                        return OresScheduler.await(childGate);
                                    }
                                    return OresScheduler.done(17);
                                }));
                                return OresScheduler.await(parentGate);
                            }
                            return OresScheduler.done(11);
                        }));
                    });
            try {
                actor.ready().get(5, TimeUnit.SECONDS);
                actor.send("start");
                assertTrue(childSuspended.await(5, TimeUnit.SECONDS));
                actor.send("stop");
                actor.done().get(5, TimeUnit.SECONDS);
                assertInstanceOf(TaskHandle.Cancelled.class,
                        child.get().getDone().get(5, TimeUnit.SECONDS));
                assertInstanceOf(TaskHandle.Cancelled.class,
                        parent.get().getDone().get(5, TimeUnit.SECONDS));
                assertEquals(2, guestTurns.get());
                assertTrue(actor.failure().isEmpty(), () -> String.valueOf(actor.failure()));

                // A different host thread settles the original awaited Futures
                // only AFTER actor done; it must not resurrect stopped guest code.
                assertTrue(childGate.completeFromRuntime(1));
                assertTrue(parentGate.completeFromRuntime(1));
                assertEquals(2, guestTurns.get());
            } finally {
                childGate.completeFromRuntime(1);
                parentGate.completeFromRuntime(1);
                actor.cancel();
            }
        }
    }
}
