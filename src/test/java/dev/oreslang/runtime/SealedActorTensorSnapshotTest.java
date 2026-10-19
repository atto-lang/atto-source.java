package dev.oreslang.runtime;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime coverage for immutable f64 parameter snapshots.
 *
 * This proves the Java runtime substrate only. It is not source-level TensorF64
 * lowering, a Graal guest compiler pass, LLVM parity, or a distributed lease.
 */
final class SealedActorTensorSnapshotTest {
    @Test void immutableSnapshotEscapesLoanButNotActorIncarnation() throws Exception {
        var produced = new CountDownLatch(1);
        var crossActorChecked = new CountDownLatch(1);
        var retired = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var snapshotRef = new AtomicReference<SealedActorState.TensorSnapshotF64>();
        var stateRef = new AtomicReference<SealedActorState>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var owner = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                try {
                    if (mail == 1) {
                        SealedActorState state = new SealedActorState(
                                context.self().id(),
                                Map.of("weights", "tensor:f64",
                                       "version", "int",
                                       "run_id", "int",
                                       "graph_id", "int",
                                       "incarnation", "int"),
                                Map.of("version", 0L,
                                       "run_id", 7L,
                                       "graph_id", 9L,
                                       "incarnation", 1L));
                        stateRef.set(state);

                        var authority = state.critical(false,
                                read -> read.snapshotScalars(
                                        "version", "run_id", "graph_id", "incarnation"));
                        assertEquals(0L, authority.version());
                        assertEquals(0L, authority.revision());

                        assertThrows(IllegalArgumentException.class,
                                () -> state.prepareTensorPublication(
                                        authority, "weights",
                                        "run_id", "graph_id", "incarnation",
                                        new int[] {2, 2},
                                        new double[] {1, 2, 3}));
                        IllegalArgumentException duplicateField = assertThrows(
                                IllegalArgumentException.class,
                                () -> state.prepareTensorPublication(
                                        authority, "weights",
                                        "version", "graph_id", "incarnation",
                                        new int[] {1},
                                        new double[] {1}));
                        assertEquals("ML_DUPLICATE_TENSOR_PUBLICATION_FIELD",
                                duplicateField.getMessage());
                        assertThrows(IllegalArgumentException.class,
                                () -> state.prepareTensorPublication(
                                        authority, "weights",
                                        "run_id", "graph_id", "incarnation",
                                        new int[] {1},
                                        new double[] {Double.NaN}));
                        assertThrows(IllegalArgumentException.class,
                                () -> state.prepareTensorPublication(
                                        authority, "weights",
                                        "run_id", "graph_id", "incarnation",
                                        new int[] {1_048_577, 0},
                                        new double[0]));

                        int[] shape = {2, 2};
                        double[] values = {1.0, 2.0, 3.0, 4.0};
                        var prepared = state.prepareTensorPublication(
                                authority, "weights",
                                "run_id", "graph_id", "incarnation", shape, values);

                        // Mutating caller-owned inputs after preparation cannot
                        // mutate the prepared/published immutable backing.
                        shape[0] = 99;
                        values[0] = 99.0;

                        SealedActorState.TensorSnapshotF64 snapshot =
                                state.critical(true,
                                        write -> write.publishTensorSnapshot(prepared));
                        snapshotRef.set(snapshot);

                        assertEquals(7L, snapshot.runId());
                        assertEquals(9L, snapshot.graphId());
                        assertEquals(1L, snapshot.incarnation());
                        assertEquals(1L, snapshot.version());
                        assertEquals(2, snapshot.rank());
                        assertEquals(2, snapshot.dimension(0));
                        assertEquals(4, snapshot.length());
                        assertEquals(1.0, snapshot.read(0));

                        int[] shapeCopy = snapshot.copyShape();
                        double[] valueCopy = snapshot.copyValues();
                        shapeCopy[0] = 1234;
                        valueCopy[0] = 1234.0;
                        assertEquals(2, snapshot.dimension(0));
                        assertEquals(1.0, snapshot.read(0));

                        // Immutable snapshots are the one complex value allowed
                        // to leave a synchronous state loan.
                        var escaped = state.critical(false,
                                read -> (SealedActorState.TensorSnapshotF64)
                                        read.read("weights"));
                        assertSame(snapshot, escaped);
                        assertEquals(4.0, escaped.read(3));

                        assertThrows(SecurityException.class,
                                () -> state.critical(true, write -> {
                                    write.write("weights", snapshot);
                                    return null;
                                }));
                        var scalarSnapshot = new AtomicReference<SealedActorState.ScalarSnapshot>();
                        state.critical(false, read -> {
                            scalarSnapshot.set(read.snapshotScalars("version", "run_id"));
                            return null;
                        });
                        assertThrows(IllegalArgumentException.class,
                                () -> state.prepareScalarPublication(
                                        scalarSnapshot.get(),
                                        Map.of("version", 2L,
                                               "weights", snapshot)));

                        // Raw transaction tuples are no longer caller-supplied.
                        // Every identity value comes from one issuer-bound snapshot.
                        var incompleteAuthority = state.critical(false,
                                read -> read.snapshotScalars(
                                        "version", "run_id", "graph_id"));
                        IllegalArgumentException incomplete = assertThrows(
                                IllegalArgumentException.class,
                                () -> state.prepareTensorPublication(
                                        incompleteAuthority, "weights",
                                        "run_id", "graph_id", "incarnation",
                                        new int[] {2, 2},
                                        new double[] {5, 6, 7, 8}));
                        assertEquals("ML_TENSOR_IDENTITY_NOT_CAPTURED",
                                incomplete.getMessage());

                        var other = new SealedActorState(
                                context.self().id(),
                                Map.of("weights", "tensor:f64",
                                       "version", "int",
                                       "run_id", "int",
                                       "graph_id", "int",
                                       "incarnation", "int"),
                                Map.of("version", 1L,
                                       "run_id", 7L,
                                       "graph_id", 9L,
                                       "incarnation", 1L));
                        var foreignAuthority = other.critical(false,
                                read -> read.snapshotScalars(
                                        "version", "run_id", "graph_id", "incarnation"));
                        assertThrows(SecurityException.class,
                                () -> state.prepareTensorPublication(
                                        foreignAuthority, "weights",
                                        "run_id", "graph_id", "incarnation",
                                        new int[] {2, 2},
                                        new double[] {5, 6, 7, 8}));
                        other.retireFromRuntime(context.self().id());

                        assertThrows(IllegalStateException.class,
                                () -> state.critical(false, read -> {
                                    state.prepareTensorPublication(
                                            authority, "weights",
                                            "run_id", "graph_id", "incarnation",
                                            new int[] {2, 2},
                                            new double[] {5, 6, 7, 8});
                                    return null;
                                }));

                        // Candidate staged from a real snapshot becomes stale if
                        // any actor state mutation occurs before publication.
                        var afterFirstAuthority = state.critical(false,
                                read -> read.snapshotScalars(
                                        "version", "run_id", "graph_id", "incarnation"));
                        var staleRevision = state.prepareTensorPublication(
                                afterFirstAuthority, "weights",
                                "run_id", "graph_id", "incarnation",
                                new int[] {2, 2},
                                new double[] {5, 6, 7, 8});
                        state.critical(true, write -> {
                            write.write("graph_id", 10L);
                            return null;
                        });
                        assertThrows(IllegalStateException.class,
                                () -> state.critical(true,
                                        write -> write.publishTensorSnapshot(staleRevision)));
                        assertSame(snapshot, state.critical(false,
                                read -> read.read("weights")));
                        assertEquals(1L, state.critical(false,
                                read -> ((Number) read.read("version")).longValue()));

                        produced.countDown();
                    } else if (mail == 2) {
                        SealedActorState state = stateRef.get();
                        SealedActorState.TensorSnapshotF64 snapshot = snapshotRef.get();
                        assertNotNull(state);
                        assertNotNull(snapshot);
                        state.retireFromRuntime(context.self().id());
                        assertThrows(IllegalStateException.class, () -> snapshot.read(0));
                        retired.countDown();
                    }
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                    produced.countDown();
                    retired.countDown();
                }
            });

            var foreign = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                try {
                    assertTrue(produced.await(15, TimeUnit.SECONDS),
                            "owner did not publish tensor snapshot");
                    SealedActorState.TensorSnapshotF64 snapshot = snapshotRef.get();
                    assertNotNull(snapshot);
                    assertThrows(SecurityException.class, () -> snapshot.read(0));
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                } finally {
                    crossActorChecked.countDown();
                }
            });

            owner.send(1);
            assertTrue(produced.await(15, TimeUnit.SECONDS),
                    "tensor snapshot was not published");
            foreign.send(1);
            assertTrue(crossActorChecked.await(15, TimeUnit.SECONDS),
                    "cross-actor snapshot check did not complete");
            owner.send(2);
            assertTrue(retired.await(15, TimeUnit.SECONDS),
                    "tensor snapshot state did not retire");
            if (failure.get() != null) fail(failure.get());
        }
    }

    @Test void tensorPreparationRequiresIssuerSnapshotAuthority() {
        var methods = Arrays.stream(SealedActorState.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("prepareTensorPublication"))
                .toList();
        assertEquals(1, methods.size());
        assertEquals(SealedActorState.ScalarSnapshot.class,
                methods.getFirst().getParameterTypes()[0]);
        assertFalse(Arrays.stream(methods.getFirst().getParameterTypes())
                .anyMatch(type -> type == long.class),
                "raw transaction identity counters must not be caller parameters");
    }

    @Test void snapshotClassDoesNotExposeMutableBackingFields() {
        for (var field : SealedActorState.TensorSnapshotF64.class.getDeclaredFields()) {
            if (field.getType().isArray()) {
                assertTrue(Modifier.isPrivate(field.getModifiers()),
                        "tensor backing array must remain private: " + field.getName());
                assertTrue(Modifier.isFinal(field.getModifiers()),
                        "tensor backing reference must remain final: " + field.getName());
            }
        }
        var publicArrayMethods = Arrays.stream(
                        SealedActorState.TensorSnapshotF64.class.getMethods())
                .filter(method -> method.getReturnType().isArray())
                .map(method -> method.getName())
                .sorted()
                .toList();
        assertEquals(java.util.List.of("copyShape", "copyValues"), publicArrayMethods);
    }
}
