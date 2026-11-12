package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STOP permits already admitted task lexical cleanup, not new task admission.
 * Running the assertions from a real cleanup-only actor scheduler continuation
 * catches the case where sourceScheduler() remains allocated after STOP.
 */
@Timeout(30)
final class ActorTaskStopCleanupAdmissionTest {
    @Test
    void cleanupOnlyTurnCannotStartNewTrackedOrUntrackedActorTasks()
            throws Exception {
        for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
            OresFuture<Integer> unresolved = new OresFuture<>();
            CountDownLatch afterSuspension = new CountDownLatch(1);
            AtomicInteger initialTurns = new AtomicInteger();
            AtomicInteger cleanupTurns = new AtomicInteger();
            AtomicReference<Throwable> assertionFailure = new AtomicReference<>();

            try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
                var actor = runtime.<String>spawn(
                        kind, IsolatePolicy.developer(),
                        () -> (message, context) -> {
                            if ("marker".equals(message)) {
                                // Actor source Task continuations have priority
                                // over ordinary user mail. This receipt means
                                // its initial turn has already suspended.
                                afterSuspension.countDown();
                                return;
                            }
                            assertEquals("start", message);
                            context.runtime().startActorTask(
                                    new OresScheduler.CleanupAwareTask<Integer>() {
                                        @Override
                                        public OresScheduler.Step<Integer> resume(
                                                OresScheduler.Resume resume) {
                                            if (resume.initial()) {
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
                                            } catch (Throwable failure) {
                                                assertionFailure.compareAndSet(null, failure);
                                                throw failure;
                                            }
                                            return OresScheduler.done(7);
                                        }
                                    });
                        });
                try {
                    actor.ready().get(5, TimeUnit.SECONDS);
                    actor.send("start");
                    actor.send("marker");
                    assertTrue(afterSuspension.await(5, TimeUnit.SECONDS),
                            kind + " source Task did not suspend before STOP");
                    assertEquals(1, initialTurns.get());

                    actor.stop();
                    actor.done().get(5, TimeUnit.SECONDS);
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
