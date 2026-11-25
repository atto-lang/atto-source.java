package dev.oreslang.runtime;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Until generator lowering can produce actor-incarnation-owned continuations,
 * the process/root AsyncRuntime must not execute an actor's captured producer.
 */
final class ActorGeneratorPoolAdmissionTest {
    @Test
    void actorCannotCreateOrPullGeneratorUsingRootAsyncPool() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            AtomicInteger rootProducerRuns = new AtomicInteger();
            AtomicInteger rootSyncProducerRuns = new AtomicInteger();
            AtomicInteger actorProducerRuns = new AtomicInteger();
            AtomicInteger actorSyncProducerRuns = new AtomicInteger();
            AtomicReference<Throwable> actorFailure = new AtomicReference<>();

            try (AsyncRuntime root = new AsyncRuntime();
                 ActorRuntime actors = new ActorRuntime()) {
                var outside = GeneratorRuntime.<Integer>asyncGenerator(root, emitter -> {
                    rootProducerRuns.incrementAndGet();
                    emitter.emit(42);
                });
                var syncOutside = GeneratorRuntime.<Integer>generator(root, emitter -> {
                    rootSyncProducerRuns.incrementAndGet();
                    emitter.emit(43);
                });

                try {
                    ActorRuntime.ActorRef<Integer> actor = actors.spawnShared(
                            () -> (message, context) -> {
                                try {
                                    assertTrue(ActorRuntime.inActorExecution());
                                    var denied = assertThrows(IllegalStateException.class,
                                            () -> GeneratorRuntime.<Integer>asyncGenerator(
                                                    root, emitter -> {
                                                        actorProducerRuns.incrementAndGet();
                                                        emitter.emit(99);
                                                    }));
                                    assertTrue(denied.getMessage()
                                            .contains("ACTOR_GENERATOR_UNSUPPORTED"));

                                    var syncDenied = assertThrows(IllegalStateException.class,
                                            () -> GeneratorRuntime.<Integer>generator(
                                                    root, emitter -> {
                                                        actorSyncProducerRuns.incrementAndGet();
                                                        emitter.emit(98);
                                                    }));
                                    assertTrue(syncDenied.getMessage()
                                            .contains("ACTOR_GENERATOR_UNSUPPORTED"));

                                    // A preconstructed root iterator cannot
                                    // smuggle process/root task admission into
                                    // this mailbox turn. Async next still
                                    // returns a failed OresFuture, not a throw.
                                    OresFuture<GeneratorRuntime.Step<Integer>> pull =
                                            outside.nextStep();
                                    assertTrue(pull.isDone());
                                    ExecutionException failure = assertThrows(
                                            ExecutionException.class,
                                            () -> pull.get(2, TimeUnit.SECONDS));
                                    assertTrue(failure.getCause() instanceof IllegalStateException);
                                    assertTrue(failure.getCause().getMessage()
                                            .contains("ACTOR_GENERATOR_UNSUPPORTED"));

                                    // A foreign actor must not cancel a
                                    // root-owned producer through the async
                                    // close receipt or its sync close bridge.
                                    var deniedClose = outside.closeAsync();
                                    assertTrue(deniedClose.isDone());
                                    var closeFailure = assertThrows(
                                            ExecutionException.class,
                                            () -> deniedClose.get(2, TimeUnit.SECONDS));
                                    assertTrue(closeFailure.getCause() instanceof IllegalStateException);
                                    assertTrue(closeFailure.getCause().getMessage()
                                            .contains("ACTOR_GENERATOR_UNSUPPORTED"));
                                    var closeBlocking = assertThrows(
                                            IllegalStateException.class, outside::close);
                                    assertTrue(closeBlocking.getMessage()
                                            .contains("ACTOR_GENERATOR_UNSUPPORTED"));

                                    // Both sync pulls and sync close are
                                    // forbidden on the actor carrier. The
                                    // root owner can still resume/retire it.
                                    assertThrows(IllegalStateException.class,
                                            syncOutside::nextStep);
                                    var closeRejected = assertThrows(
                                            IllegalStateException.class, syncOutside::close);
                                    assertTrue(closeRejected.getMessage()
                                            .contains("ACTOR_GENERATOR_UNSUPPORTED"));
                                } catch (Throwable failure) {
                                    actorFailure.compareAndSet(null, failure);
                                } finally {
                                    context.self().stop();
                                }
                            });
                    actor.ready().get(5, TimeUnit.SECONDS);
                    actor.send(1);
                    actor.done().get(5, TimeUnit.SECONDS);
                    assertNull(actorFailure.get(),
                            "actor-root async generator escape must reject: "
                                    + actorFailure.get());
                    assertEquals(0, actorProducerRuns.get());
                    assertEquals(0, actorSyncProducerRuns.get());
                    assertEquals(0, rootProducerRuns.get(),
                            "actor pull must not have started process/root producer");
                    assertEquals(0, rootSyncProducerRuns.get(),
                            "actor sync pull/close must not start the producer");

                    // The originating root owner retains valid pull authority
                    // after the actor's denied attempt.
                    assertEquals(42, outside.nextStep().get(2, TimeUnit.SECONDS).value());
                    assertTrue(outside.nextStep().get(2, TimeUnit.SECONDS).done());
                    assertEquals(1, rootProducerRuns.get());
                    assertEquals(43, syncOutside.nextStep().value());
                    assertTrue(syncOutside.nextStep().done());
                    assertEquals(1, rootSyncProducerRuns.get());
                } finally {
                    outside.closeAsync().get(2, TimeUnit.SECONDS);
                    syncOutside.close();
                }
            }
        });
    }
}
