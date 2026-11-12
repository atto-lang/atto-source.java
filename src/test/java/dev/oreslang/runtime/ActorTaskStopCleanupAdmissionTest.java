package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STOP permits already admitted task lexical cleanup, not new task admission.
 * A nested actor-owned task requests STOP after the parent has yielded into
 * Await; this also works for UNTRUSTED actors, whose quota forbids unrelated
 * synchronous mailbox turns while a logical task cohort is suspended.
 * The cleanup assertions catch sourceScheduler() remaining reachable after STOP.
 */
@Timeout(30)
final class ActorTaskStopCleanupAdmissionTest {
    @Test
    void cleanupOnlyTurnCannotStartNewTrackedOrUntrackedActorTasks()
            throws Exception {
        for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
            OresFuture<Integer> unresolved = new OresFuture<>();
            AtomicInteger initialTurns = new AtomicInteger();
            AtomicInteger cleanupTurns = new AtomicInteger();
            AtomicReference<Throwable> assertionFailure = new AtomicReference<>();

            try (SharedCodeImageStore store = new SharedCodeImageStore();
                 ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
                var image = store.publish("actor-task-cleanup.ores",
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
                        assertEquals("start", mail.value());
                        context.runtime().startActorTask(
                                new OresScheduler.CleanupAwareTask<Integer>() {
                                    @Override
                                    public OresScheduler.Step<Integer> resume(
                                            OresScheduler.Resume resume) {
                                        if (resume.initial()) {
                                            // A nested task on this SAME actor
                                            // scheduler requests STOP on its
                                            // next turn. The parent must have
                                            // yielded into WAITING first; no
                                            // host sleep/marker/other actor mail
                                            // can bypass untrusted quota rules.
                                            context.runtime().startActorTask(stopTurn -> {
                                                assertTrue(stopTurn.initial());
                                                assertEquals(context.self().id(),
                                                        ActorRuntime.currentActorId().orElseThrow());
                                                context.self().stop();
                                                return OresScheduler.done(0);
                                            });
                                            initialTurns.incrementAndGet();
                                            return OresScheduler.await(unresolved);
                                        }
                                        try {
                                            assertTrue(
                                                    OresScheduler.cancellationCleanupTurn(),
                                                    "this must be an authenticated cleanup turn");
                                            assertNotNull(resume.failure(),
                                                    "STOP must deliver cancellation");
                                            assertTrue(ActorRuntime.inActorExecution());
                                            assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                                            assertThrows(RuntimeException.class,
                                                    () -> runtime.startActorTask(
                                                            r -> OresScheduler.done(99)));
                                            assertThrows(RuntimeException.class,
                                                    () -> runtime.startTrackedActorTask(
                                                            r -> OresScheduler.done(99)));
                                            cleanupTurns.incrementAndGet();
                                        } catch (RuntimeException | Error failure) {
                                            assertionFailure.compareAndSet(null, failure);
                                            throw failure;
                                        }
                                        return OresScheduler.done(7);
                                    }
                                });
                    }
                };
                ActorRuntime.ActorRef<Object> actor =
                        runtime.spawnCodeActor(kind, executor, "Worker");
                try {
                    actor.ready().get(5, TimeUnit.SECONDS);
                    actor.send("start");
                    // STOP is scheduled by the nested actor Task only after
                    // the parent has returned an asynchronous Await.
                    actor.done().get(5, TimeUnit.SECONDS);
                    assertEquals(1, initialTurns.get());
                    assertNull(assertionFailure.get(),
                            () -> "cleanup admission guard failed: " + assertionFailure.get());
                    assertEquals(1, cleanupTurns.get(),
                            "one cancellation cleanup turn must retire before actor DONE");
                    assertTrue(actor.failure().isEmpty(), () -> String.valueOf(actor.failure()));

                    // Late external completion is never permitted to revive a
                    // retired Task or resurrect an actor incarnation.
                    assertTrue(unresolved.completeFromRuntime(42));
                    assertEquals(1, initialTurns.get());
                    assertEquals(1, cleanupTurns.get());
                } finally {
                    unresolved.completeFromRuntime(42);
                    actor.cancel();
                }
            }
        }
    }
}
