package dev.oreslang.runtime;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the real Java actor scheduler and SealedActorState's bounded,
 * single-state publication path. Does not claim compiler-lowered tensor VJP.
 */
final class SealedActorMlScalarPublicationTest {
    @Test void atomicScalarVersionPublicationAndAdversarialRecovery() throws Exception {
        var completion = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                try {
                    SealedActorState state = new SealedActorState(context.self().id(),
                            Map.of("version", "int", "weight", "int",
                                   "optimizer", "int", "name", "string"),
                            Map.of("version", 1L, "weight", 5L,
                                   "optimizer", 0L, "name", "stage"));

                    // Real host scalar forward/backward: y = w * x,
                    // loss = (y - target)^2, dLoss/dWeight = 2*x*(y-target).
                    // Compute all numerical operations AFTER releasing READ loan.
                    var inputRevision = new AtomicLong(-1);
                    long w = state.critical(false, read -> {
                        inputRevision.set(read.revision());
                        return ((Number) read.read("weight")).longValue();
                    });
                    assertEquals(0L, inputRevision.get());
                    long x = 2, target = 8, learningRateDenominator = 8;
                    long prediction = Math.multiplyExact(w, x);
                    long gradient = Math.multiplyExact(
                            2 * x, Math.subtractExact(prediction, target));
                    assertEquals(8L, gradient);
                    long nextWeight = Math.subtractExact(
                            w, gradient / learningRateDenominator);
                    assertEquals(4L, nextWeight);
                    // Candidate computation/staging occurs with no loan.
                    var first = state.prepareScalarPublication("version", 1, inputRevision.get(),
                            Map.of("version", 2L, "weight", nextWeight, "optimizer", 1L));
                    assertThrows(IllegalStateException.class,
                            () -> state.critical(false, read -> {
                                state.prepareScalarPublication("version", 1, 0,
                                        Map.of("version", 2L));
                                return null;
                            }));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.prepareScalarPublication("version", 1, 0,
                                    Map.of("version", 3L, "weight", 4L)));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.prepareScalarPublication("version", 1, 0,
                                    Map.of("version", 2L, "weight", "bad")));

                    state.critical(false, read -> {
                        assertThrows(SecurityException.class, () -> read.publishScalars(first));
                        assertEquals(5L, read.read("weight"));
                        assertEquals(1L, read.read("version"));
                        return null;
                    });

                    assertTrue(state.critical(true, write -> write.publishScalars(first)));
                    state.critical(false, read -> {
                        assertEquals(2L, read.read("version"));
                        assertEquals(4L, read.read("weight"));
                        assertEquals(1L, read.read("optimizer"));
                        assertEquals("stage", read.read("name"));
                        return null;
                    });
                    assertThrows(SecurityException.class,
                            () -> state.critical(true, write -> write.publishScalars(first)));

                    var invalidPublish = state.prepareScalarPublication("version", 2, 1,
                            Map.of("version", 3L, "weight", 3L,
                                   "name", "x".repeat(64 * 1024 + 1)));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.critical(true,
                                    write -> write.publishScalars(invalidPublish)));
                    state.critical(false, read -> {
                        assertEquals(2L, read.read("version"));
                        assertEquals(4L, read.read("weight"));
                        assertEquals(1L, read.read("optimizer"));
                        return null;
                    });

                    var stale = state.prepareScalarPublication("version", 2, 1,
                            Map.of("version", 3L, "weight", 3L, "optimizer", 2L));
                    state.critical(true, write -> {
                        write.write("weight", 77L);  // bumps mutation revision
                        return null;
                    });
                    assertThrows(IllegalStateException.class,
                            () -> state.critical(true, write -> write.publishScalars(stale)));
                    state.critical(false, read -> {
                        assertEquals(2L, read.read("version"));
                        assertEquals(77L, read.read("weight"));
                        assertEquals(1L, read.read("optimizer"));
                        return null;
                    });

                    // TOCTOU: stage the candidate *after* a newer write,
                    // but bind it to the revision observed before that write.
                    long observed = state.critical(false, read -> read.revision());
                    assertEquals(2L, observed);
                    state.critical(true, write -> {
                        write.write("weight", 78L);
                        return null;
                    });
                    var delayed = state.prepareScalarPublication("version", 2, observed,
                            Map.of("version", 3L, "weight", 55L, "optimizer", 2L));
                    assertThrows(IllegalStateException.class,
                            () -> state.critical(true,
                                    write -> write.publishScalars(delayed)));
                    assertEquals(78L, state.critical(false, read -> read.read("weight")));

                    var retry = state.prepareScalarPublication("version", 2, 3,
                            Map.of("version", 3L, "weight", 3L, "optimizer", 2L));
                    // Different issuer, even if it has same logical ActorId.
                    var other = new SealedActorState(context.self().id(),
                            Map.of("version", "int", "weight", "int"),
                            Map.of("version", 2L, "weight", 99L));
                    var foreign = other.prepareScalarPublication("version", 2, 0,
                            Map.of("version", 3L, "weight", 98L));
                    assertThrows(SecurityException.class,
                            () -> state.critical(true, write -> write.publishScalars(foreign)));
                    assertTrue(state.critical(true, write -> write.publishScalars(retry)));
                    assertEquals(3L, state.critical(false, read -> read.read("version")));
                    assertEquals(3L, state.critical(false, read -> read.read("weight")));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.prepareScalarPublication("version", Long.MAX_VALUE, 2,
                                    Map.of("version", 1L)));
                    other.retireFromRuntime(context.self().id());
                    state.retireFromRuntime(context.self().id());
                } catch (Throwable thrown) {
                    failure.set(thrown);
                } finally {
                    completion.countDown();
                }
            });
            actor.send(1);
            assertTrue(completion.await(15, TimeUnit.SECONDS), "actor callback did not retire");
            if (failure.get() != null) fail(failure.get());
        }
    }
}
