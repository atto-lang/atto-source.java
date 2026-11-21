package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

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
    void stopCannotCompleteWhileCarrierStillHoldsNativeReadLoan() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch admitted = new CountDownLatch(1);
            CountDownLatch releaseCarrier = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ActorShardColumns> retained = new AtomicReference<>();
            var actor = runtime.<Integer>spawnSharedTrusted(factory -> (message, turn) -> {
                try {
                    ActorShardColumns shard = new ActorShardColumns(
                            ActorShardColumns.Layout.SOA, 16, 2, 1);
                    retained.set(shard);
                    shard.write(0, 1, 12.0);
                    try (var loan = shard.leaseColumnReadOnly(1)) {
                        admitted.countDown();
                        if (!releaseCarrier.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("native borrow did not receive carrier release");
                        }
                        assertEquals(12.0, loan.read(0));
                    }
                    shard.close();
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    admitted.countDown();
                }
            });
            try {
                actor.send(1);
                assertTrue(admitted.await(5, TimeUnit.SECONDS));
                actor.stop();
                assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS),
                        "stopping may not publish done while a physical carrier owns the loan");
                assertTrue(runtime.sharedMemoryBytes() > 0L,
                        "native shard quota must remain charged until carrier quiescence");
            } finally {
                releaseCarrier.countDown();
            }
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertNotNull(retained.get());
            assertTrue(retained.get().nativeBackingReleased());
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

    @Test
    void inFlightCloseAndConcurrentStopDoNotLeaveRetainedBacking() throws Exception {
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
                    assertTrue(shard.nativeBackingReleased(),
                            "retained shard cannot access freed native backing after stop");
                    assertEquals(0, runtime.sharedMemoryBytes(),
                            "no stale shared shard reservation after actor termination");
                } finally {
                    finishTurn.countDown();
                }
            }
        }
    }
}
