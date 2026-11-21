package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Actor Tasks must resume through the actor's DATA/continuation carrier, never CONTROL. */
@Timeout(20)
final class ActorOwnedTaskPoolTest {
    @Test
    void trackedTaskStaysOnAuthenticatedActorWorkersAfterForeignFutureCompletion()
            throws Exception {
        CountDownLatch firstTurn = new CountDownLatch(1);
        CountDownLatch secondTurn = new CountDownLatch(1);
        OresFuture<Integer> producer = new OresFuture<>();
        AtomicReference<TaskHandle<Integer>> handleRef = new AtomicReference<>();
        AtomicReference<OresScheduler> schedulerRef = new AtomicReference<>();
        AtomicReference<Object> domainRef = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var worker = runtime.<String>spawnShared(() -> (message, context) -> {
                assertEquals("run", message);
                TaskHandle<Integer> handle = context.runtime().startTrackedActorTask(
                        new OresScheduler.Task<Integer>() {
                            private int turns;

                            @Override
                            public OresScheduler.Step<Integer> resume(
                                    OresScheduler.Resume resume) {
                                assertSame(runtime, ActorRuntime.currentActorRuntime());
                                assertEquals(context.self().id(),
                                        ActorRuntime.currentActorId().orElseThrow());
                                assertTrue(ActorRuntime.isActorCarrierThread(),
                                        "actor Task must run on an actor carrier");
                                assertEquals(ActorRuntime.ActorKind.SHARED,
                                        ActorRuntime.currentActorKind());
                                OresScheduler scheduler = OresScheduler.requireCurrent();
                                assertTrue(scheduler.name().startsWith("ores-actor-"),
                                        "actor Task cannot use root/CONTROL scheduler");
                                Object domain = ActorRuntime.currentActorExecutionDomain();
                                assertNotNull(domain);
                                if (turns++ == 0) {
                                    assertTrue(resume.initial());
                                    schedulerRef.set(scheduler);
                                    domainRef.set(domain);
                                    firstTurn.countDown();
                                    return OresScheduler.await(producer);
                                }
                                assertFalse(resume.initial());
                                assertSame(schedulerRef.get(), scheduler,
                                        "await must preserve logical actor scheduler");
                                assertSame(domainRef.get(), domain,
                                        "await must preserve the actor execution domain");
                                secondTurn.countDown();
                                return OresScheduler.done(42);
                            }
                        });
                handleRef.set(handle);
            });

            worker.send("run");
            assertTrue(firstTurn.await(4, TimeUnit.SECONDS));
            assertThrows(SecurityException.class,
                    () -> runtime.startTrackedActorTask(
                            resume -> OresScheduler.done(0)),
                    "foreign producer cannot mint an actor-owned Task");
            assertTrue(producer.completeFromRuntime(7));
            assertTrue(secondTurn.await(4, TimeUnit.SECONDS),
                    "foreign Future completion must enqueue an actor turn");
            TaskHandle<Integer> handle = handleRef.get();
            assertNotNull(handle);
            assertEquals(42, handle.result().join());
            assertEquals(new TaskHandle.Success<>(42), handle.getDone().join());
            worker.stop();
            assertTrue(worker.awaitTermination(4, TimeUnit.SECONDS));
            assertTrue(worker.failure().isEmpty(), () -> worker.failure().toString());
        }
    }
}
