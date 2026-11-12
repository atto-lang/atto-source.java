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
            AtomicInteger actorProducerRuns = new AtomicInteger();
            AtomicInteger actorSyncProducerRuns = new AtomicInteger();
            AtomicReference<Throwable> actorFailure = new AtomicReference<>();

            try (AsyncRuntime root = new AsyncRuntime();
                 ActorRuntime actors = new ActorRuntime()) {
                var outside = GeneratorRuntime.<Integer>asyncGenerator(root, emitter -> {
                    rootProducerRuns.incrementAndGet();
                    emitter.emit(42);
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

                    // The originating root owner retains valid pull authority
                    // after the actor's denied attempt.
                    assertEquals(42, outside.nextStep().get(2, TimeUnit.SECONDS).value());
                    assertTrue(outside.nextStep().get(2, TimeUnit.SECONDS).done());
                    assertEquals(1, rootProducerRuns.get());
                } finally {
                    outside.closeAsync().get(2, TimeUnit.SECONDS);
                }
            }
        });
    }
}
