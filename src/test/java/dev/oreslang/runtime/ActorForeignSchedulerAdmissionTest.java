package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A physical actor carrier is not a blanket license to create actor Tasks:
 * an unrelated scheduler can run a synchronous nested host callback on the
 * same OS thread, while the actor's ThreadLocal identity remains installed.
 *
 * Use code-actor fixtures for ALL actor kinds: hostile/untrusted actor
 * construction does not permit arbitrary captured host Behavior factories.
 */
@Timeout(30)
final class ActorForeignSchedulerAdmissionTest {
    @Test
    void foreignInlineSchedulerCannotMintTasksFromAnyActorKind() throws Exception {
        for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
            AtomicInteger denied = new AtomicInteger();
            AtomicInteger foreignTurns = new AtomicInteger();
            AtomicInteger controlCalls = new AtomicInteger();
            ActorRuntime.ControlDispatcher forbiddenControl = runnable -> {
                controlCalls.incrementAndGet();
                throw new AssertionError("actor Task entered supervisor CONTROL");
            };

            try (SharedCodeImageStore store = new SharedCodeImageStore();
                 ActorRuntime runtime = new ActorRuntime(
                         IsolatePolicy.developer(),
                         new ActorRuntime.DispatcherConfig(2, 2, 1, 64),
                         ActorRuntime.TurnExecutor.direct(),
                         forbiddenControl)) {
                var image = store.publish("foreign-actor-scheduler.ores",
                        Parser.parse("pub routine main(): void { return; }"));
                ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                    @Override
                    public SharedCodeImageStore.CodeImage codeImage() {
                        return image;
                    }

                    @Override
                    public ActorRuntime.ActorOwnedGuestState initializeActor(
                            String type, ActorRuntime.ActorContext<Object> context) {
                        assertEquals("Worker", type);
                        return context.self()::id;
                    }

                    @Override
                    public void receiveActor(
                            String type, ActorRuntime.ActorOwnedGuestState state,
                            ActorRuntime.ActorInboxMail<Object> mail,
                            ActorRuntime.ActorContext<Object> context) {
                        assertEquals("Worker", type);
                        assertEquals(context.self().id(), state.ownerActorId());
                        assertEquals("check", mail.value());
                        assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                        assertNull(OresScheduler.current(),
                                "ordinary mailbox turns have no Task scheduler");
                        assertFalse(hasInitializedSourceScheduler(runtime, context.self().id()),
                                "new actor must not initialize a source scheduler before any task");
                        try (OresScheduler foreign = OresScheduler.runtimeOwned(
                                "foreign-inline-task-" + kind, 1, Runnable::run)) {
                            OresFuture<Integer> completed = foreign.start(resume -> {
                                assertTrue(resume.initial());
                                assertSame(foreign, OresScheduler.requireCurrent());
                                assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                                assertEquals(context.self().id(),
                                        ActorRuntime.currentActorId().orElseThrow());
                                foreignTurns.incrementAndGet();

                                assertThrows(SecurityException.class,
                                        () -> runtime.startTrackedActorTask(
                                                nested -> OresScheduler.done(17)),
                                        "tracked Task admission requires actor scheduler");
                                denied.incrementAndGet();
                                assertThrows(SecurityException.class,
                                        () -> runtime.startActorTask(
                                                nested -> OresScheduler.done(19)),
                                        "untracked Task admission requires actor scheduler");
                                denied.incrementAndGet();
                                return OresScheduler.done(1);
                            });
                            assertTrue(completed.isDone(),
                                    "direct foreign executor must run synchronously");
                        }

                        assertFalse(hasInitializedSourceScheduler(runtime, context.self().id()),
                                "foreign scheduler rejection must not allocate actor scheduler");
                        // Legitimate actor receive roots must still be admitted
                        // to the current actor-kind mailbox pool.
                        context.runtime().startActorTask(resume -> {
                            assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                            assertSame(runtime, ActorRuntime.currentActorRuntime());
                            assertEquals(kind, ActorRuntime.currentActorKind());
                            return OresScheduler.done(23);
                        });
                        context.self().stop();
                    }
                };

                var actor = runtime.spawnCodeActor(kind, executor, "Worker");
                try {
                    actor.ready().get(5, TimeUnit.SECONDS);
                    actor.send("check");
                    actor.done().get(5, TimeUnit.SECONDS);
                    assertTrue(actor.failure().isEmpty(),
                            () -> kind + ": " + actor.failure());
                    assertEquals(1, foreignTurns.get(), kind + " nested foreign turn");
                    assertEquals(2, denied.get(), kind + " admission denials");
                    assertEquals(0, controlCalls.get(),
                            kind + " supervisor CONTROL is not a Task carrier");
                } finally {
                    actor.cancel();
                }
            }
        }
    }

    /** Test-only reflective probe; not an API or capability for guest code. */
    private static boolean hasInitializedSourceScheduler(
            ActorRuntime runtime, ActorRuntime.ActorId id) {
        try {
            Field actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((Map<?, ?>) actorsField.get(runtime)).get(id);
            if (cell == null) throw new AssertionError("actor cell missing");
            Field sourceSchedulerField = cell.getClass().getDeclaredField("sourceScheduler");
            sourceSchedulerField.setAccessible(true);
            return sourceSchedulerField.get(cell) != null;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not inspect test-only scheduler state", failure);
        }
    }
}
