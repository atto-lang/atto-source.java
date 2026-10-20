package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class ActorShardSnapshotLeaseTest {
    @Test
    void detachedSnapshotsStayChargedUntilClosedOrActorStops() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<List<ActorShardColumns.ColumnSnapshot>> retained =
                    new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    ctx -> (value, turn) -> {
                        try {
                            ActorShardColumns region =
                                    new ActorShardColumns(ActorShardColumns.Layout.SOA, 32, 2, 1);
                            for (int i = 0; i < 32; i++) region.write(i, 0, i * 2);
                            List<ActorShardColumns.ColumnSnapshot> copies = new ArrayList<>();
                            for (int k = 0; k < 1000; k++) {
                                copies.add(region.snapshotColumnLeased(0));
                            }
                            region.close();
                            retained.set(copies);
                        } catch (Throwable x) {
                            failure.set(x);
                        } finally {
                            done.countDown();
                        }
                    });
            long before = runtime.sharedMemoryBytes();
            actor.send(1);
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertNull(failure.get());
            List<ActorShardColumns.ColumnSnapshot> copies = retained.get();
            assertNotNull(copies);
            assertEquals(1000, copies.size());
            assertEquals(62.0, copies.get(999).read(31));
            CountDownLatch foreignDone = new CountDownLatch(1);
            AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> foreign = runtime.spawnSharedTrusted(
                    ctx -> (value, turn) -> {
                        try {
                            assertThrows(SecurityException.class, () -> copies.get(999).read(0));
                            assertThrows(SecurityException.class, () -> copies.get(999).length());
                            assertThrows(SecurityException.class, () -> copies.get(999).close());
                        } catch (Throwable error) {
                            foreignFailure.set(error);
                        } finally {
                            foreignDone.countDown();
                        }
                    });
            foreign.send(1);
            assertTrue(foreignDone.await(5, TimeUnit.SECONDS));
            assertNull(foreignFailure.get());
            foreign.gracefulStop().get(5, TimeUnit.SECONDS);
            assertTrue(foreign.awaitTermination(5, TimeUnit.SECONDS));
            long snapshotBytes = 1000L * 32L * Double.BYTES;
            assertTrue(runtime.sharedMemoryBytes() >= before + snapshotBytes,
                    "all 1000 detached snapshots remain charged");

            for (int i = 0; i < 500; i++) {
                copies.get(i).close();
                copies.get(i).close();
                ActorShardColumns.ColumnSnapshot closed = copies.get(i);
                assertThrows(IllegalStateException.class, () -> closed.read(0));
            }
            assertTrue(runtime.sharedMemoryBytes() < before + snapshotBytes,
                    "close must refund snapshots exactly once");

            actor.gracefulStop().get(10, TimeUnit.SECONDS);
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> copies.get(999).read(0));
            assertTrue(runtime.sharedMemoryBytes() <= before,
                    "physical actor finalization revokes remaining snapshot allocations");
        }
    }

    @Test
    void accountingCorruptionCannotStrandSnapshotRevocation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch allocated = new CountDownLatch(1);
            AtomicReference<ActorShardColumns.ColumnSnapshot> retained =
                    new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    ctx -> (value, turn) -> {
                        ActorShardColumns region =
                                new ActorShardColumns(ActorShardColumns.Layout.SOA, 16, 2, 1);
                        region.write(15, 0, 30);
                        retained.set(region.snapshotColumnLeased(0));
                        region.close();
                        allocated.countDown();
                    });

            actor.send(1);
            assertTrue(allocated.await(5, TimeUnit.SECONDS));
            actor.drain().get(5, TimeUnit.SECONDS);
            ActorShardColumns.ColumnSnapshot snapshot = retained.get();
            assertNotNull(snapshot);
            assertEquals(30.0, snapshot.read(15));

            Field leaseField =
                    ActorShardColumns.ColumnSnapshot.class.getDeclaredField("lease");
            leaseField.setAccessible(true);
            Object lease = leaseField.get(snapshot);
            Field cellField = lease.getClass().getDeclaredField("cell");
            cellField.setAccessible(true);
            Object cell = cellField.get(lease);
            Field reserved = cell.getClass().getDeclaredField("shardReservedBytes");
            reserved.setAccessible(true);
            reserved.setLong(cell, 0L); // deterministic host-accounting fault

            long failuresBefore = runtime.runtimeStats().lifecyclePublicationFailures();
            actor.gracefulStop().get(5, TimeUnit.SECONDS);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));

            assertThrows(IllegalStateException.class, () -> snapshot.read(0));
            assertEquals(0, runtime.sharedMemoryBytes(),
                    "snapshot refund must not be skipped by per-cell underflow");
            assertTrue(runtime.runtimeStats().lifecyclePublicationFailures() > failuresBefore,
                    "snapshot accounting corruption must remain observable");
        }
    }

    @Test
    void hostCannotCreateSnapshotOutsideAnActorTurn() {
        assertThrows(SecurityException.class,
                () -> new ActorShardColumns(ActorShardColumns.Layout.SOA, 4, 2, 1));
    }
}
