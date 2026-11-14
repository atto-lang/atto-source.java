package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ActorShardColumnsTest {
    @Test
    void leaseLifecycleHooksAreNotPublicShardApi() {
        assertThrows(NoSuchMethodException.class,
                () -> ActorShardColumns.class.getMethod("revokeBackingFromLease"));
        assertThrows(NoSuchMethodException.class,
                () -> ActorDenseIndexColumns.class.getMethod("revokeBackingFromLease"));
        assertThrows(NoSuchMethodException.class,
                () -> ActorShardColumns.class.getMethod(
                        "ownedBy", ActorRuntime.class, ActorRuntime.ActorId.class));
        assertThrows(NoSuchMethodException.class,
                () -> ActorDenseIndexColumns.class.getMethod(
                        "ownedBy", ActorRuntime.class, ActorRuntime.ActorId.class));
    }

    @Test
    void allLayoutsComputeSameResultsAcrossActorTurnAndWorkerChanges() throws Exception {
        for (ActorShardColumns.Layout layout : ActorShardColumns.Layout.values()) {
            try (ActorRuntime runtime = new ActorRuntime()) {
                CountDownLatch first = new CountDownLatch(1);
                CountDownLatch second = new CountDownLatch(1);
                AtomicReference<double[]> result = new AtomicReference<>();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                AtomicReference<ActorShardColumns> confined = new AtomicReference<>();

                ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                        factory -> {
                            ActorShardColumns[] store = new ActorShardColumns[1];
                            return (message, turn) -> {
                                try {
                                    if (message == 1) {
                                        store[0] = new ActorShardColumns(layout, 65, 3,
                                                layout == ActorShardColumns.Layout.AOSOA ? 8 : 1);
                                        confined.set(store[0]);
                                        for (int i = 0; i < 65; i++) {
                                            store[0].write(i, 0, i);
                                            store[0].write(i, 1, 2 * i);
                                            store[0].write(i, 2, -1);
                                        }
                                        store[0].integrate(0, 1, 0.5);
                                        first.countDown();
                                    } else {
                                        store[0].integrate(0, 1, 1);
                                        try (ActorShardColumns.ColumnSnapshot snapshot =
                                                     store[0].snapshotColumnLeased(0)) {
                                            double[] copy = new double[snapshot.length()];
                                            snapshot.copyInto(copy, 0);
                                            result.set(copy);
                                        }
                                        assertThrows(UnsupportedOperationException.class,
                                                () -> store[0].snapshotColumn(0));
                                        second.countDown();
                                    }
                                } catch (Throwable error) {
                                    failure.compareAndSet(null, error);
                                    first.countDown();
                                    second.countDown();
                                }
                            };
                        });
                actor.send(1);
                assertTrue(first.await(5, TimeUnit.SECONDS));
                assertNull(failure.get());
                assertThrows(SecurityException.class,
                        () -> confined.get().read(0, 0),
                        "host cannot access an actor's live region");
                assertThrows(SecurityException.class,
                        () -> confined.get().snapshotColumn(0));
                actor.send(2);
                assertTrue(second.await(5, TimeUnit.SECONDS));
                assertNull(failure.get());
                double[] copy = result.get();
                assertNotNull(copy);
                assertEquals(65, copy.length);
                for (int i = 0; i < 65; i++) assertEquals(4.0 * i, copy[i]);
                actor.stop();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                java.lang.reflect.Field storageField =
                        ActorShardColumns.class.getDeclaredField("storage");
                storageField.setAccessible(true);
                assertNull(storageField.get(confined.get()),
                        "actor finalization must detach shared backing before quota refund");
                assertThrows(SecurityException.class,
                        () -> confined.get().integrate(0, 1, 1));
            }
        }
    }

    @Test
    void privateActorUsesDirectMemoryQuotaAndReleasesOnTermination() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnPrivate(
                    context -> {
                        // Only this owner-checked shard is retained by the
                        // behavior. No mutable host AtomicReference or array
                        // capture is accepted by private actor validation.
                        final ActorShardColumns shard = new ActorShardColumns(
                                ActorShardColumns.Layout.AOSOA, 65, 2, 8);
                        if (!shard.isPrivateBacked()) {
                            throw new AssertionError("private shards require arena backing");
                        }
                        return (message, turn) -> {
                            for (int i = 0; i < shard.rows(); i++) {
                                shard.write(i, 0, i);
                                shard.write(i, 1, 2.0 * i);
                            }
                            shard.integrate(0, 1, 0.5);
                            if (shard.read(64, 0) != 128.0) {
                                throw new AssertionError("private AoSoA integration failed");
                            }
                        };
                    });
            actor.send(1);
            actor.drain().get(5, TimeUnit.SECONDS);
            assertTrue(runtime.privateMemoryBytes() >= 65L * 2L * Double.BYTES);
            actor.gracefulStop().get(5, TimeUnit.SECONDS);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.privateMemoryBytes(),
                    "private direct backing must release on termination");
        }
    }

    @Test
    void hostAllocationAndInvalidShapesFailClosed() {
        assertThrows(SecurityException.class,
                () -> new ActorShardColumns(ActorShardColumns.Layout.SOA, 32, 2, 1));
    }

    @Test
    void guestActorRejectsInvalidBoundsOrStaleColumns() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    factory -> (message, turn) -> {
                        try {
                            assertThrows(IllegalArgumentException.class,
                                    () -> new ActorShardColumns(ActorShardColumns.Layout.SOA, 1, 0, 1));
                            assertThrows(IllegalArgumentException.class,
                                    () -> new ActorShardColumns(ActorShardColumns.Layout.AOSOA, 10, 2, 3));
                            assertThrows(IllegalArgumentException.class,
                                    () -> new ActorShardColumns(ActorShardColumns.Layout.AOS, 3_000_000, 3, 1));
                            ActorShardColumns shard =
                                    new ActorShardColumns(ActorShardColumns.Layout.SOA, 3, 2, 1);
                            assertThrows(IndexOutOfBoundsException.class, () -> shard.read(3, 0));
                            assertThrows(IllegalArgumentException.class, () -> shard.integrate(1, 1, 1));
                            assertThrows(IllegalArgumentException.class,
                                    () -> shard.integrate(0, 1, Double.NaN));
                            shard.write(2, 1, 7);
                            try (ActorShardColumns.ColumnSnapshot snapshot =
                                         shard.snapshotColumnLeased(1)) {
                                double[] copy = new double[3];
                                snapshot.copyInto(copy, 0);
                                copy[2] = 1000;
                                assertEquals(7, shard.read(2, 1));
                            }
                            assertThrows(UnsupportedOperationException.class,
                                    () -> shard.snapshotColumn(1));
                            shard.close();
                            shard.close(); // no duplicate memory-budget release
                            assertThrows(IllegalStateException.class, () -> shard.read(2, 1));
                        } catch (Throwable problem) {
                            error.set(problem);
                        } finally {
                            finished.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
        }
    }
}
