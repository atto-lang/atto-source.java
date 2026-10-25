package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

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
            double[] copied = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                    -1, -1, -1, -1};
            copies.get(999).copyInto(copied, 1);
            assertEquals(-1.0, copied[0]);
            assertEquals(0.0, copied[1]);
            assertEquals(62.0, copied[32]);
            assertEquals(-1.0, copied[33]);
            assertThrows(IndexOutOfBoundsException.class,
                    () -> copies.get(999).copyInto(copied, 3));
            CountDownLatch foreignDone = new CountDownLatch(1);
            AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> foreign = runtime.spawnSharedTrusted(
                    ctx -> (value, turn) -> {
                        try {
                            assertThrows(SecurityException.class, () -> copies.get(999).read(0));
                            assertThrows(SecurityException.class, () -> copies.get(999).length());
                            assertThrows(SecurityException.class,
                                    () -> copies.get(999).copyInto(new double[32], 0));
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
                assertThrows(IllegalStateException.class,
                        () -> closed.copyInto(new double[32], 0));
            }
            assertTrue(runtime.sharedMemoryBytes() < before + snapshotBytes,
                    "close must refund snapshots exactly once");

            actor.gracefulStop().get(10, TimeUnit.SECONDS);
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> copies.get(999).read(0));
            assertThrows(IllegalStateException.class,
                    () -> copies.get(999).copyInto(new double[32], 0));
            assertTrue(runtime.sharedMemoryBytes() <= before,
                    "physical actor finalization revokes remaining snapshot allocations");
        }
    }

    @Test
    void perActorSnapshotQuotaFailureRollsBackRuntimeAccounting() throws Exception {
        long mib = 1024L * 1024L;
        IsolatePolicy ceiling = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                32L * mib,
                8192,
                java.time.Duration.ofMinutes(1));
        IsolatePolicy actorPolicy = new IsolatePolicy(
                ceiling.capabilities(),
                16L * mib,
                8192,
                java.time.Duration.ofMinutes(1));
        try (ActorRuntime runtime = new ActorRuntime(ceiling)) {
            long before = runtime.sharedMemoryBytes();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    actorPolicy,
                    ctx -> (value, turn) -> {
                        ActorShardColumns shard = null;
                        List<ActorShardColumns.ColumnSnapshot> snapshots = new ArrayList<>();
                        try {
                            // 524,288 doubles = 4 MiB. Shard + three snapshots
                            // exactly consume this actor's 16 MiB shard budget.
                            shard = new ActorShardColumns(
                                    ActorShardColumns.Layout.SOA, 524_288, 1, 1);
                            for (int i = 0; i < 3; i++) {
                                snapshots.add(shard.snapshotColumnLeased(0));
                            }
                            ActorShardColumns activeShard = shard;
                            assertThrows(IllegalStateException.class,
                                    () -> activeShard.snapshotColumnLeased(0));
                        } catch (Throwable error) {
                            failure.set(error);
                        } finally {
                            for (ActorShardColumns.ColumnSnapshot snapshot : snapshots) {
                                snapshot.close();
                            }
                            if (shard != null) shard.close();
                            done.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(done.await(10, TimeUnit.SECONDS));
            actor.drain().get(10, TimeUnit.SECONDS);
            assertNull(failure.get());
            assertEquals(before, runtime.sharedMemoryBytes(),
                    "failed per-actor reservation must not leak aggregate bytes");
            actor.gracefulStop().get(5, TimeUnit.SECONDS);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aggregateSnapshotQuotaFailureDoesNotPartiallyChargeContender() throws Exception {
        long mib = 1024L * 1024L;
        IsolatePolicy ceiling = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                // Leave room for both shards, two snapshots and mailbox metadata;
                // the third snapshot must be the operation that exceeds the cap.
                20L * mib,
                8192,
                java.time.Duration.ofMinutes(1));
        try (ActorRuntime runtime = new ActorRuntime(ceiling)) {
            long before = runtime.sharedMemoryBytes();
            CountDownLatch holderReady = new CountDownLatch(1);
            CountDownLatch holderClosed = new CountDownLatch(1);
            AtomicReference<Throwable> holderFailure = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> holder = runtime.spawnSharedTrusted(
                    ceiling,
                    ctx -> {
                        ActorShardColumns[] shard = new ActorShardColumns[1];
                        ActorShardColumns.ColumnSnapshot[] snapshot =
                                new ActorShardColumns.ColumnSnapshot[1];
                        return (message, turn) -> {
                            try {
                                if (message == 1) {
                                    shard[0] = new ActorShardColumns(
                                            ActorShardColumns.Layout.SOA, 524_288, 1, 1);
                                    snapshot[0] = shard[0].snapshotColumnLeased(0);
                                    holderReady.countDown();
                                } else {
                                    snapshot[0].close();
                                    shard[0].close();
                                    holderClosed.countDown();
                                }
                            } catch (Throwable error) {
                                holderFailure.set(error);
                                holderReady.countDown();
                                holderClosed.countDown();
                            }
                        };
                    });

            holder.send(1);
            assertTrue(holderReady.await(10, TimeUnit.SECONDS));
            holder.drain().get(10, TimeUnit.SECONDS);
            assertNull(holderFailure.get());
            long heldBytes = runtime.sharedMemoryBytes();
            assertTrue(heldBytes >= before + 8L * mib,
                    "holder must retain shard plus leased snapshot");

            CountDownLatch contenderDone = new CountDownLatch(1);
            AtomicReference<Throwable> contenderFailure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> contender = runtime.spawnSharedTrusted(
                    ceiling,
                    ctx -> (message, turn) -> {
                        ActorShardColumns shard = null;
                        ActorShardColumns.ColumnSnapshot snapshot = null;
                        try {
                            shard = new ActorShardColumns(
                                    ActorShardColumns.Layout.SOA, 524_288, 1, 1);
                            snapshot = shard.snapshotColumnLeased(0);
                            ActorShardColumns finalShard = shard;
                            assertThrows(IllegalStateException.class,
                                    () -> finalShard.snapshotColumnLeased(0));
                        } catch (Throwable error) {
                            contenderFailure.set(error);
                        } finally {
                            if (snapshot != null) snapshot.close();
                            if (shard != null) shard.close();
                            contenderDone.countDown();
                        }
                    });
            contender.send(1);
            assertTrue(contenderDone.await(10, TimeUnit.SECONDS));
            contender.drain().get(10, TimeUnit.SECONDS);
            assertNull(contenderFailure.get());
            assertEquals(heldBytes, runtime.sharedMemoryBytes(),
                    "aggregate quota rejection must not leave a partial charge");

            holder.send(2);
            assertTrue(holderClosed.await(10, TimeUnit.SECONDS));
            holder.drain().get(10, TimeUnit.SECONDS);
            assertNull(holderFailure.get());
            assertEquals(before, runtime.sharedMemoryBytes());

            contender.gracefulStop().get(5, TimeUnit.SECONDS);
            holder.gracefulStop().get(5, TimeUnit.SECONDS);
            assertTrue(contender.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(holder.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void hostCannotCreateSnapshotOutsideAnActorTurn() {
        assertThrows(SecurityException.class,
                () -> new ActorShardColumns(ActorShardColumns.Layout.SOA, 4, 2, 1));
    }
}
