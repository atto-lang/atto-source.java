package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The last logical actor turn may close its shard while another host thread
 * requests stop. Finalization must not complete or refund memory until the
 * callback is finished and the backing array has been detached.
 */
@Timeout(30)
final class ActorShardStopRaceTest {
    @Test
    void inFlightCloseAndConcurrentStopDoNotLeaveRetainedBacking() throws Exception {
        Field backing = ActorShardColumns.class.getDeclaredField("storage");
        backing.setAccessible(true);
        for (int trial = 0; trial < 30; trial++) {
            try (ActorRuntime runtime = new ActorRuntime()) {
                CountDownLatch began = new CountDownLatch(1);
                CountDownLatch finishTurn = new CountDownLatch(1);
                CountDownLatch exited = new CountDownLatch(1);
                AtomicReference<ActorShardColumns> retained = new AtomicReference<>();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                        ctx -> (message, turn) -> {
                            try {
                                ActorShardColumns shard = new ActorShardColumns(
                                        ActorShardColumns.Layout.AOSOA, 129, 2, 8);
                                retained.set(shard);
                                shard.write(128, 1, 42);
                                began.countDown();
                                if (!finishTurn.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("timeout before concurrent stop");
                                }
                                shard.close();
                                shard.close();
                            } catch (Throwable problem) {
                                failure.compareAndSet(null, problem);
                            } finally {
                                began.countDown();
                                exited.countDown();
                            }
                        });
                actor.send(1);
                assertTrue(began.await(5, TimeUnit.SECONDS));
                try {
                    // Graceful stop while an actor turn still owns its lease.
                    var stopped = actor.gracefulStop();
                    finishTurn.countDown();
                    assertTrue(exited.await(5, TimeUnit.SECONDS));
                    stopped.get(5, TimeUnit.SECONDS);
                    assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                    assertNull(failure.get(), "in-flight close must complete safely");
                    ActorShardColumns shard = retained.get();
                    assertNotNull(shard);
                    assertNull(backing.get(shard),
                            "retained shard cannot pin refunded JVM backing after stop");
                    assertEquals(0, runtime.sharedMemoryBytes(),
                            "no stale shared shard reservation after actor termination");
                } finally {
                    finishTurn.countDown();
                }
            }
        }
    }

    @Test
    void accountingCorruptionCannotStrandFinalizationAfterShardRevocation() throws Exception {
        Field backing = ActorShardColumns.class.getDeclaredField("storage");
        backing.setAccessible(true);
        Field reservation = ActorShardColumns.class.getDeclaredField("reservation");
        reservation.setAccessible(true);

        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch allocated = new CountDownLatch(1);
            AtomicReference<ActorShardColumns> retained = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    ctx -> (message, turn) -> {
                        ActorShardColumns shard = new ActorShardColumns(
                                ActorShardColumns.Layout.SOA, 32, 2, 1);
                        shard.write(31, 1, 42);
                        retained.set(shard);
                        allocated.countDown();
                    });

            actor.send(1);
            assertTrue(allocated.await(5, TimeUnit.SECONDS));
            actor.drain().get(5, TimeUnit.SECONDS);
            ActorShardColumns shard = retained.get();
            assertNotNull(shard);

            Object lease = reservation.get(shard);
            Field cellField = lease.getClass().getDeclaredField("cell");
            cellField.setAccessible(true);
            Object cell = cellField.get(lease);
            Field reserved = cell.getClass().getDeclaredField("shardReservedBytes");
            reserved.setAccessible(true);
            reserved.setLong(cell, 0L); // deterministic host-accounting fault

            long failuresBefore = runtime.runtimeStats().lifecyclePublicationFailures();
            actor.gracefulStop().get(5, TimeUnit.SECONDS);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));

            assertNull(backing.get(shard),
                    "terminal publication requires detached shard backing");
            assertEquals(0, runtime.sharedMemoryBytes(),
                    "runtime quota must still be refunded after per-cell underflow");
            assertTrue(runtime.runtimeStats().lifecyclePublicationFailures() > failuresBefore,
                    "accounting corruption must remain observable");
        }
    }
}
