package dev.oreslang.runtime;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A physical actor carrier is not a blanket license to create actor Tasks:
 * an unrelated scheduler can run a synchronous nested host callback on the
 * same OS thread, while the physical actor ThreadLocals remain installed.
 */
@Timeout(20)
final class ActorForeignSchedulerAdmissionTest {
    @Test
    void foreignInlineSchedulerCannotMintTasksFromActorCarrier() throws Exception {
        AtomicInteger denied = new AtomicInteger();
        AtomicInteger foreignTurns = new AtomicInteger();
        AtomicInteger controlCalls = new AtomicInteger();
        ActorRuntime.ControlDispatcher forbiddenControl = runnable -> {
            controlCalls.incrementAndGet();
            throw new AssertionError("actor Task entered supervisor CONTROL");
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 64),
                ActorRuntime.TurnExecutor.direct(),
                forbiddenControl)) {
            var actor = runtime.<String>spawnShared(
                    IsolatePolicy.developer(),
                    () -> (message, context) -> {
                        assertEquals("check", message);
                        assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                        assertNull(OresScheduler.current(),
                                "ordinary mailbox turns have no Task scheduler");
                        try (OresScheduler foreign = OresScheduler.runtimeOwned(
                                "foreign-inline-task", 1, Runnable::run)) {
                            OresFuture<Integer> completed = foreign.start(resume -> {
                                assertTrue(resume.initial());
                                assertSame(foreign, OresScheduler.requireCurrent());
                                assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                                foreignTurns.incrementAndGet();

                                assertThrows(SecurityException.class,
                                        () -> runtime.startTrackedActorTask(
                                                nested -> OresScheduler.done(17)),
                                        "tracked actor Task admission requires actor scheduler");
                                denied.incrementAndGet();
                                assertThrows(SecurityException.class,
                                        () -> runtime.startActorTask(
                                                nested -> OresScheduler.done(19)),
                                        "untracked actor Task admission requires actor scheduler");
                                denied.incrementAndGet();
                                return OresScheduler.done(1);
                            });
                            assertTrue(completed.isDone(),
                                    "direct foreign executor should execute synchronously");
                        }
                        // A regular actor receive can still create its own actor
                        // Task; the wrong scheduler check must not ban actor roots.
                        context.runtime().startActorTask(
                                resume -> OresScheduler.done(23));
                        context.self().stop();
                    });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("check");
            actor.done().get(5, TimeUnit.SECONDS);
            assertTrue(actor.failure().isEmpty(), () -> String.valueOf(actor.failure()));
            assertEquals(1, foreignTurns.get(),
                    "foreign scheduler did not enter its nested synchronous turn");
            assertEquals(2, denied.get(),
                    "both tracked and ordinary actor admission must fail closed");
            assertEquals(0, controlCalls.get(),
                    "supervisor CONTROL is not an actor task carrier");
        }
    }
}
