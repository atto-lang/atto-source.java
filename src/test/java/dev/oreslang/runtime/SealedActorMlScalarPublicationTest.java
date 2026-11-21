package dev.oreslang.runtime;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the real Java actor scheduler and SealedActorState's bounded,
 * single-state publication path. Does not claim compiler-lowered tensor VJP.
 */
final class SealedActorMlScalarPublicationTest {
    @Test void scalarSnapshotCannotBeInspectedByForeignActorOrHostThread() throws Exception {
        var snapshotReady = new CountDownLatch(1);
        var foreignChecked = new CountDownLatch(1);
        var retired = new CountDownLatch(1);
        var snapshotRef = new AtomicReference<SealedActorState.ScalarSnapshot>();
        var stateRef = new AtomicReference<SealedActorState>();
        var failure = new AtomicReference<Throwable>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var owner = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                try {
                    if (mail == 1) {
                        var state = new SealedActorState(context.self().id(),
                                Map.of("version", "int", "weight", "int"),
                                Map.of("version", 1L, "weight", 5L));
                        stateRef.set(state);
                        snapshotRef.set(state.critical(false,
                                read -> read.snapshotScalars("version", "weight")));
                        snapshotReady.countDown();
                    } else if (mail == 2) {
                        stateRef.get().retireFromRuntime(context.self().id());
                        retired.countDown();
                    }
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                    snapshotReady.countDown();
                    foreignChecked.countDown();
                    retired.countDown();
                }
            });
            var foreign = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                try {
                    assertTrue(snapshotReady.await(5, TimeUnit.SECONDS));
                    var snapshot = snapshotRef.get();
                    assertNotNull(snapshot);
                    assertThrows(SecurityException.class,
                            () -> snapshot.scalar("weight"));
                    assertThrows(SecurityException.class,
                            () -> snapshot.version());
                    assertThrows(SecurityException.class,
                            () -> snapshot.revision());
                    assertThrows(SecurityException.class,
                            () -> snapshot.preparePublication(
                                    Map.of("version", 2L, "weight", 4L)));
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                } finally {
                    foreignChecked.countDown();
                }
            });

            owner.send(1);
            assertTrue(snapshotReady.await(5, TimeUnit.SECONDS));
            var snapshot = snapshotRef.get();
            assertNotNull(snapshot);
            assertThrows(SecurityException.class, () -> snapshot.scalar("weight"),
                    "host thread must not inspect actor snapshot");
            assertThrows(SecurityException.class,
                    () -> snapshot.preparePublication(
                            Map.of("version", 2L, "weight", 4L)),
                    "host thread must not prepare actor publication");
            foreign.send(1);
            assertTrue(foreignChecked.await(5, TimeUnit.SECONDS));
            owner.send(2);
            assertTrue(retired.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,
                    () -> snapshot.scalar("weight"),
                    "retired snapshots must remain permanently revoked");
            assertThrows(IllegalStateException.class,
                    () -> snapshot.preparePublication(
                            Map.of("version", 2L, "weight", 4L)),
                    "retired snapshots must never mint publications");
            if (failure.get() != null) fail(failure.get());
        }
    }

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
                    var inputSnapshot = state.critical(false,
                            read -> read.snapshotScalars("version", "weight", "optimizer"));
                    assertEquals(0L, inputSnapshot.revision());
                    assertEquals(1L, inputSnapshot.version());
                    long w = ((Number) inputSnapshot.scalar("weight")).longValue();
                    assertEquals(0L,
                            ((Number) inputSnapshot.scalar("optimizer")).longValue());
                    assertThrows(IllegalArgumentException.class,
                            () -> inputSnapshot.scalar("not-captured"));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.critical(false,
                                    read -> read.snapshotScalars(
                                            "version", "weight", "weight")));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.critical(false,
                                    read -> read.snapshotScalars("version")));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.critical(false,
                                    read -> read.snapshotScalars(
                                            "version", "version")));
                    long x = 2, target = 8, learningRateDenominator = 8;
                    long prediction = Math.multiplyExact(w, x);
                    long gradient = Math.multiplyExact(
                            2 * x, Math.subtractExact(prediction, target));
                    assertEquals(8L, gradient);
                    long nextWeight = Math.subtractExact(
                            w, gradient / learningRateDenominator);
                    assertEquals(4L, nextWeight);
                    // Candidate computation/staging occurs with no loan.
                    var first = state.prepareScalarPublication(inputSnapshot,
                            Map.of("version", 2L, "weight", nextWeight, "optimizer", 1L));
                    assertThrows(IllegalStateException.class,
                            () -> state.critical(false, read -> {
                                state.prepareScalarPublication(inputSnapshot,
                                        Map.of("version", 2L));
                                return null;
                            }));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.prepareScalarPublication(inputSnapshot,
                                    Map.of("version", 3L, "weight", 4L)));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.prepareScalarPublication(inputSnapshot,
                                    Map.of("version", 2L, "weight", "bad")));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.prepareScalarPublication(inputSnapshot,
                                    Map.of("version", 2L, "name", "blind-overwrite")));

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

                    var afterFirstSnapshot = state.critical(false,
                            read -> read.snapshotScalars(
                                    "version", "weight", "optimizer", "name"));
                    assertEquals(2L, afterFirstSnapshot.version());
                    assertEquals(1L, afterFirstSnapshot.revision());
                    var invalidPublish = state.prepareScalarPublication(afterFirstSnapshot,
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

                    var stale = state.prepareScalarPublication(afterFirstSnapshot,
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
                    var observed = state.critical(false,
                            read -> read.snapshotScalars("version", "weight", "optimizer"));
                    assertEquals(2L, observed.revision());
                    assertEquals(77L, ((Number) observed.scalar("weight")).longValue());
                    state.critical(true, write -> {
                        write.write("weight", 78L);
                        return null;
                    });
                    var delayed = state.prepareScalarPublication(observed,
                            Map.of("version", 3L, "weight", 55L, "optimizer", 2L));
                    assertThrows(IllegalStateException.class,
                            () -> state.critical(true,
                                    write -> write.publishScalars(delayed)));
                    assertEquals(78L, state.critical(false, read -> read.read("weight")));

                    var retrySnapshot = state.critical(false,
                            read -> read.snapshotScalars("version", "weight", "optimizer"));
                    assertEquals(3L, retrySnapshot.revision());
                    var retry = state.prepareScalarPublication(retrySnapshot,
                            Map.of("version", 3L, "weight", 3L, "optimizer", 2L));
                    // Different issuer, even if it has same logical ActorId.
                    var other = new SealedActorState(context.self().id(),
                            Map.of("version", "int", "weight", "int"),
                            Map.of("version", 2L, "weight", 99L));
                    var otherSnapshot = other.critical(false,
                            read -> read.snapshotScalars("version", "weight"));
                    assertThrows(SecurityException.class,
                            () -> state.prepareScalarPublication(otherSnapshot,
                                    Map.of("version", 3L, "weight", 98L)));
                    var foreign = other.prepareScalarPublication(otherSnapshot,
                            Map.of("version", 3L, "weight", 98L));
                    assertThrows(SecurityException.class,
                            () -> state.critical(true, write -> write.publishScalars(foreign)));
                    assertTrue(state.critical(true, write -> write.publishScalars(retry)));
                    assertEquals(3L, state.critical(false, read -> read.read("version")));
                    assertEquals(3L, state.critical(false, read -> read.read("weight")));

                    var exhausted = new SealedActorState(context.self().id(),
                            Map.of("version", "int", "weight", "int"),
                            Map.of("version", Long.MAX_VALUE, "weight", 1L));
                    var exhaustedSnapshot = exhausted.critical(false,
                            read -> read.snapshotScalars("version", "weight"));
                    assertThrows(IllegalArgumentException.class,
                            () -> exhausted.prepareScalarPublication(exhaustedSnapshot,
                                    Map.of("version", Long.MIN_VALUE, "weight", 1L)));
                    exhausted.retireFromRuntime(context.self().id());
                    other.retireFromRuntime(context.self().id());
                    state.retireFromRuntime(context.self().id());
                    assertThrows(IllegalStateException.class,
                            () -> state.critical(false, read -> null));
                    assertThrows(IllegalStateException.class,
                            () -> state.allocateOwnedBytes(1));
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
