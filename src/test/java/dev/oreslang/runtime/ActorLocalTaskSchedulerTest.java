package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actor-owned source tasks must be multiplexed onto their actor kind's
 * worker pool, never the supervisor/root CONTROL pool or a task-owned pool.
 */
@Timeout(20)
final class ActorLocalTaskSchedulerTest {

    @Test
    void trackedTaskResumesOnSameActorSchedulerAfterForeignCompletion()
            throws Exception {
        for (ActorRuntime.ActorKind kind : new ActorRuntime.ActorKind[] {
                ActorRuntime.ActorKind.PRIVATE, ActorRuntime.ActorKind.SHARED }) {
            try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
                OresFuture<Integer> producer = new OresFuture<>();
                AtomicReference<TaskHandle<Integer>> started = new AtomicReference<>();
                AtomicReference<OresScheduler> logicalScheduler = new AtomicReference<>();
                CountDownLatch submitted = new CountDownLatch(1);
                CountDownLatch firstTurn = new CountDownLatch(1);
                CountDownLatch secondTurn = new CountDownLatch(1);
                String expectedPrefix = kind == ActorRuntime.ActorKind.PRIVATE
                        ? "ores-private-actor-dispatcher-"
                        : "ores-shared-actor-dispatcher-";

                ActorRuntime.BehaviorFactory<String> factory = context ->
                        (message, ignored) -> {
                            assertEquals("start", message);
                            TaskHandle<Integer> child =
                                    context.runtime().startActorTrackedTask(
                                            new OresScheduler.Task<>() {
                                                private int pc;

                                                private void checkActorDomain() {
                                                    assertSame(runtime,
                                                            ActorRuntime.currentActorRuntime());
                                                    assertEquals(context.self().id(),
                                                            ActorRuntime.currentActorId()
                                                                    .orElseThrow());
                                                    assertTrue(ActorRuntime.inActorExecution());
                                                    assertFalse(ActorRuntime
                                                            .inActorGroupMailmanExecution());
                                                    assertTrue(Thread.currentThread()
                                                                    .getName()
                                                                    .startsWith(expectedPrefix),
                                                            Thread.currentThread().getName());
                                                    OresScheduler scheduler =
                                                            OresScheduler.requireCurrent();
                                                    OresScheduler first =
                                                            logicalScheduler.get();
                                                    if (first == null) {
                                                        assertTrue(logicalScheduler
                                                                .compareAndSet(null, scheduler));
                                                    } else {
                                                        assertSame(first, scheduler,
                                                                "await must preserve actor scheduler");
                                                    }
                                                }

                                                @Override
                                                public OresScheduler.Step<Integer> resume(
                                                        OresScheduler.Resume resume) {
                                                    checkActorDomain();
                                                    if (pc++ == 0) {
                                                        assertTrue(resume.initial());
                                                        firstTurn.countDown();
                                                        return OresScheduler.await(producer);
                                                    }
                                                    assertEquals(1, pc - 1);
                                                    assertFalse(resume.initial());
                                                    assertNull(resume.failure());
                                                    secondTurn.countDown();
                                                    return OresScheduler.done(
                                                            (Integer) resume.value() + 1);
                                                }
                                            });
                            context.runtime().ownCurrentActorFuture(
                                    SourceTaskHandles.register(child));
                            started.set(child);
                            submitted.countDown();
                        };

                ActorRuntime.ActorRef<String> actor = kind == ActorRuntime.ActorKind.PRIVATE
                        ? runtime.spawnPrivateTrusted(factory)
                        : runtime.spawnSharedTrusted(factory);
                actor.ready().get(5, TimeUnit.SECONDS);
                actor.send("start");
                assertTrue(submitted.await(5, TimeUnit.SECONDS));
                assertTrue(firstTurn.await(5, TimeUnit.SECONDS));
                assertNotNull(logicalScheduler.get());
                // Complete from this foreign test thread, never from an actor
                // worker. It must only enqueue the actor's continuation.
                assertTrue(producer.completeFromRuntime(41));
                assertTrue(secondTurn.await(5, TimeUnit.SECONDS));
                assertEquals(42, started.get().result().get(5, TimeUnit.SECONDS));
                assertInstanceOf(TaskHandle.Success.class,
                        started.get().getDone().get(5, TimeUnit.SECONDS));
                actor.stop();
                actor.done().get(5, TimeUnit.SECONDS);
                assertTrue(actor.failure().isEmpty(), () -> actor.failure().toString());
            }
        }
    }

    @Test
    void taskAdmissionRequiresAuthenticatedActorTurn() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            assertThrows(IllegalStateException.class,
                    () -> runtime.startActorTrackedTask(
                            ignored -> OresScheduler.done(1)));
        }
    }
}
