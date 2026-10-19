package dev.oreslang.types;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proof construction tests. These certify the *compiler metadata model*
 * only, not real Oreslang Task execution or compiler-minted allocation sites.
 */
final class TaskCertifiedLayoutTest {
    private static final Types.Record RECORD = new Types.Record(Map.of(
            "position", Types.Primitive.INT,
            "velocity", Types.Primitive.FLOAT,
            "health", Types.Primitive.BOOL), false);

    private static Types.Tuple array(Types.Type... elements) {
        return new Types.Tuple(List.of(elements), Types.TupleKind.FIXED_ARRAY);
    }

    @Test void independentlyVerifiedScalarFieldsCanWriteConcurrently() {
        var issuer = new TaskCertifiedLayout.Emitter();
        var record = issuer.freshRecord(RECORD);
        var movement = issuer.admit(List.of(record.writeField("position")));
        var velocity = issuer.admit(List.of(record.writeField("velocity")));
        assertEquals(2, issuer.active());
        assertThrows(IllegalArgumentException.class,
                () -> issuer.admit(List.of(record.readField("position"))));
        issuer.quiescent(movement);
        issuer.quiescent(velocity);
        issuer.finish();
    }

    @Test void readReadCompatibleButWriteConflicts() {
        var issuer = new TaskCertifiedLayout.Emitter();
        var record = issuer.freshRecord(RECORD);
        var first = issuer.admit(List.of(record.readField("position")));
        var second = issuer.admit(List.of(record.readField("position")));
        assertThrows(IllegalArgumentException.class,
                () -> issuer.admit(List.of(record.writeField("position"))));
        issuer.quiescent(first);
        issuer.quiescent(second);
        assertNotNull(issuer.admit(List.of(record.writeField("position"))));
    }

    @Test void adjacentFixedArrayRangesDoNotOverlap() {
        var issuer = new TaskCertifiedLayout.Emitter();
        var array = issuer.freshFixedArray(array(
                Types.Primitive.INT, Types.Primitive.INT,
                Types.Primitive.INT, Types.Primitive.INT));
        issuer.admit(List.of(array.writeRange(0, 2)));
        issuer.admit(List.of(array.writeRange(2, 4)));
        assertEquals(2, issuer.active());
        assertThrows(IllegalArgumentException.class,
                () -> issuer.admit(List.of(array.writeRange(1, 3))));
    }

    @Test void foreignCertificatesCannotBeAdmittedEvenIfReadOnly() {
        var first = new TaskCertifiedLayout.Emitter();
        var second = new TaskCertifiedLayout.Emitter();
        var foreign = first.freshRecord(RECORD).readField("position");
        assertThrows(IllegalArgumentException.class,
                () -> second.admit(List.of(foreign)));
        assertEquals(0, second.active());
    }

    @Test void rejectsPointerAndUnknownFieldLayouts() {
        var issuer = new TaskCertifiedLayout.Emitter();
        for (Types.Type unknown : List.of(
                Types.Unknown.INSTANCE,
                new Types.Named("ActorRef", List.of()),
                new Types.Borrow(Types.Primitive.INT, false),
                new Types.ListType(Types.Primitive.INT),
                new Types.Generic("T"),
                Types.Primitive.STRING,
                Types.Primitive.DECIMAL)) {
            assertThrows(IllegalArgumentException.class,
                    () -> issuer.freshRecord(new Types.Record(
                            Map.of("scalar", Types.Primitive.INT, "bad", unknown))),
                    () -> "unsafe record member: " + unknown);
        }
        assertEquals(0, issuer.active());
        assertNotNull(issuer.freshRecord(RECORD));
    }

    @Test void cannotWriteReadonlyRecordAndCannotInventUnknownFields() {
        var issuer = new TaskCertifiedLayout.Emitter();
        var shape = new Types.Record(Map.of("position", Types.Primitive.INT), true);
        var record = issuer.freshRecord(shape);
        assertNotNull(issuer.admit(List.of(record.readField("position"))));
        assertThrows(IllegalArgumentException.class,
                () -> record.writeField("position"));
        assertThrows(IllegalArgumentException.class,
                () -> record.readField("fake"));
    }

    @Test void onlyFixedArraysArePartitionable() {
        var issuer = new TaskCertifiedLayout.Emitter();
        assertThrows(IllegalArgumentException.class,
                () -> issuer.freshFixedArray(new Types.Tuple(
                        List.of(Types.Primitive.INT), Types.TupleKind.TUPLE)));
        assertThrows(IllegalArgumentException.class,
                () -> issuer.freshFixedArray(new Types.Tuple(
                        List.of(Types.Primitive.INT), Types.TupleKind.FIXED_LIST)));
        assertThrows(IllegalArgumentException.class,
                () -> issuer.freshFixedArray(array(Types.Primitive.INT, Types.Primitive.STRING)));
    }

    @Test void arrayPartitionsCannotExceedProofBounds() {
        var issuer = new TaskCertifiedLayout.Emitter();
        var array = issuer.freshFixedArray(array(Types.Primitive.INT, Types.Primitive.INT));
        assertThrows(IllegalArgumentException.class, () -> array.writeRange(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> array.writeRange(0, 3));
        assertThrows(IllegalArgumentException.class, () -> array.writeRange(0, 0));
        assertThrows(IllegalArgumentException.class, () -> array.writeRange(2, 1));
        assertNotNull(issuer.admit(List.of(array.writeRange(0, 2))));
    }

    @Test void heterogeneousRangesRequireActualLayoutLowering() {
        var issuer = new TaskCertifiedLayout.Emitter();
        var array = issuer.freshFixedArray(array(
                Types.Primitive.INT, Types.Primitive.FLOAT, Types.Primitive.INT));
        assertThrows(IllegalArgumentException.class,
                () -> array.writeRange(0, 2));
        var first = issuer.admit(List.of(array.writeRange(0, 1)));
        var third = issuer.admit(List.of(array.writeRange(2, 3)));
        assertEquals(2, issuer.active());
        issuer.quiescent(first);
        issuer.quiescent(third);
    }

    @Test void cancellationDoesNotReleaseVerifiedWriteLoan() {
        var issuer = new TaskCertifiedLayout.Emitter();
        var record = issuer.freshRecord(RECORD);
        var ticket = issuer.admit(List.of(record.writeField("health")));
        issuer.cancel(ticket);
        assertThrows(IllegalArgumentException.class,
                () -> issuer.admit(List.of(record.readField("health"))));
        assertThrows(IllegalStateException.class, issuer::finish);
        issuer.quiescent(ticket);
        assertNotNull(issuer.admit(List.of(record.readField("health"))));
    }

    @Test void lateUseAfterFinalScopeCloseFailsClosed() {
        var issuer = new TaskCertifiedLayout.Emitter();
        var record = issuer.freshRecord(RECORD);
        var ticket = issuer.admit(List.of(record.readField("health")));
        issuer.quiescent(ticket);
        issuer.finish();
        assertThrows(IllegalStateException.class, () -> record.readField("health"));
        assertThrows(IllegalStateException.class, () -> issuer.admit(List.of()));
        assertThrows(IllegalStateException.class, () -> issuer.freshRecord(RECORD));
    }

    @Test void multipleFreshObjectsHaveDistinctCompilerAllocatedRoots() {
        var issuer = new TaskCertifiedLayout.Emitter();
        var first = issuer.freshRecord(RECORD);
        var second = issuer.freshRecord(RECORD);
        issuer.admit(List.of(first.writeField("position")));
        issuer.admit(List.of(second.writeField("position")));
        assertEquals(2, issuer.active());
    }

    @Test void admissionOfMixedGoodAndForeignProofsIsAtomic() {
        var issuer = new TaskCertifiedLayout.Emitter();
        var local = issuer.freshRecord(RECORD);
        var foreign = new TaskCertifiedLayout.Emitter().freshRecord(RECORD);
        assertThrows(IllegalArgumentException.class,
                () -> issuer.admit(List.of(
                        local.writeField("position"),
                        foreign.readField("position"))));
        assertEquals(0, issuer.active());
        assertNotNull(issuer.admit(List.of(local.writeField("position"))));
    }

    @Test void parallelProofMintingCannotReuseAllocationIds() throws Exception {
        var issuer = new TaskCertifiedLayout.Emitter();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var barrier = new java.util.concurrent.CountDownLatch(1);
        try {
            var outcomes = new java.util.ArrayList<java.util.concurrent.Future<
                    TaskCertifiedLayout.Access>>();
            for (int i = 0; i < 64; i++) {
                outcomes.add(pool.submit(() -> {
                    barrier.await();
                    return issuer.freshRecord(RECORD).writeField("position");
                }));
            }
            barrier.countDown();
            for (var outcome : outcomes) {
                assertNotNull(issuer.admit(List.of(outcome.get())));
            }
            assertEquals(64, issuer.active(),
                    "distinct freshly issued allocations must not collide under concurrency");
        } finally {
            barrier.countDown();
            pool.shutdownNow();
        }
    }

    @Test void unsupportedLayoutDoesNotAccidentallyIssueAnyProof() {
        var issuer = new TaskCertifiedLayout.Emitter();
        var members = new java.util.HashMap<String, Types.Type>();
        for (int index = 0; index < 1025; index++) {
            members.put("f" + index, Types.Primitive.INT);
        }
        assertThrows(IllegalArgumentException.class,
                () -> issuer.freshRecord(new Types.Record(members, false)));
        assertEquals(0, issuer.active());
        assertNotNull(issuer.freshRecord(RECORD));
    }
}
