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
    void shardActorIdCompatibilityStillRequiresExactExecutingRef() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch allocated = new CountDownLatch(1);
            CountDownLatch foreignChecked = new CountDownLatch(1);
            AtomicReference<ActorShardColumns> retained = new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorId> ownerId = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> owner = runtime.spawnSharedTrusted(
                    ctx -> (message, turn) -> {
                        try {
                            ActorShardColumns shard = new ActorShardColumns(
                                    ActorShardColumns.Layout.SOA, 8, 1, 1);
                            retained.set(shard);
                            ownerId.set(ctx.self().id());
                            assertSame(ctx.self(), ActorRuntime.currentActorRef(),
                                    "runtime must expose the exact executing ActorRef internally");
                            assertTrue(shard.ownedBy(runtime, ctx.self().id()),
                                    "legacy ActorId wrapper may authorize only through exact current ref");
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            allocated.countDown();
                        }
                    });

            owner.send(1);
            assertTrue(allocated.await(5, TimeUnit.SECONDS));
            owner.drain().get(5, TimeUnit.SECONDS);
            assertNull(failure.get());

            ActorRuntime.ActorRef<Integer> foreign = runtime.spawnSharedTrusted(
                    ctx -> (message, turn) -> {
                        try {
                            assertFalse(retained.get().ownedBy(runtime, ownerId.get()),
                                    "another exact ActorRef must not gain authority by presenting the owner's ActorId");
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            foreignChecked.countDown();
                        }
                    });
            foreign.send(1);
            assertTrue(foreignChecked.await(5, TimeUnit.SECONDS));
            foreign.drain().get(5, TimeUnit.SECONDS);
            assertNull(failure.get());

            assertFalse(retained.get().ownedBy(runtime, ownerId.get()),
                    "ambient host code must not gain shard authority from ActorId alone");

            owner.gracefulStop().get(5, TimeUnit.SECONDS);
            foreign.gracefulStop().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void sameLogicalIdCannotReadOrCloseRetainedSnapshotFromAnotherActorTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch snapshotted = new CountDownLatch(1);
            CountDownLatch attacked = new CountDownLatch(1);
            AtomicReference<ActorShardColumns.ColumnSnapshot> retainedSnapshot =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> owner = runtime.spawnSharedTrusted(
                    ctx -> (message, turn) -> {
                        try {
                            ActorShardColumns shard = new ActorShardColumns(
                                    ActorShardColumns.Layout.SOA, 2, 1, 1);
                            shard.write(0, 0, 42.0);
                            retainedSnapshot.set(shard.snapshotColumnLeased(0));
                            shard.close();
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            snapshotted.countDown();
                        }
                    });

            owner.send(1);
            assertTrue(snapshotted.await(5, TimeUnit.SECONDS));
            owner.drain().get(5, TimeUnit.SECONDS);
            assertNull(failure.get());

            ActorRuntime.ActorRef<Integer> foreign = runtime.spawnSharedTrusted(
                    ctx -> (message, turn) -> {
                        try {
                            Field executionField =
                                    ActorRuntime.class.getDeclaredField("CURRENT_ACTOR_EXECUTION");
                            executionField.setAccessible(true);
                            @SuppressWarnings("unchecked")
                            ThreadLocal<Object> execution =
                                    (ThreadLocal<Object>) executionField.get(null);
                            Object original = execution.get();
                            assertNotNull(original);

                            var constructor = original.getClass().getDeclaredConstructors()[0];
                            constructor.setAccessible(true);
                            Object forged = constructor.newInstance(
                                    runtime,
                                    owner.id(),
                                    ActorRuntime.currentActorKind(),
                                    ActorRuntime.currentActorPolicy(),
                                    ActorRuntime.currentActorExecutionDomain());

                            execution.set(forged);
                            try {
                                assertThrows(
                                        SecurityException.class,
                                        () -> retainedSnapshot.get().read(0),
                                        "same logical ActorId must not authorize a different actor incarnation");
                                assertThrows(
                                        SecurityException.class,
                                        retainedSnapshot.get()::close,
                                        "same logical ActorId must not authorize snapshot revocation");
                            } finally {
                                execution.set(original);
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            attacked.countDown();
                        }
                    });

            foreign.send(1);
            assertTrue(attacked.await(5, TimeUnit.SECONDS));
            foreign.drain().get(5, TimeUnit.SECONDS);
            assertNull(failure.get());

            assertEquals(42.0, retainedSnapshot.get().read(0),
                    "rejected foreign access must leave the retained snapshot intact");
            retainedSnapshot.get().close();

            owner.gracefulStop().get(5, TimeUnit.SECONDS);
            foreign.gracefulStop().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void forgedSameIdAndGenerationRefCannotOwnShardLease() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch allocated = new CountDownLatch(1);
            AtomicReference<ActorShardColumns> retained = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> owner = runtime.spawnSharedTrusted(
                    ctx -> (message, turn) -> {
                        retained.set(new ActorShardColumns(
                                ActorShardColumns.Layout.SOA, 4, 1, 1));
                        allocated.countDown();
                    });

            owner.send(1);
            assertTrue(allocated.await(5, TimeUnit.SECONDS));
            owner.drain().get(5, TimeUnit.SECONDS);

            var constructor = owner.getClass().getDeclaredConstructor(
                    ActorRuntime.class,
                    ActorRuntime.ActorId.class,
                    ActorRuntime.ActorKind.class,
                    long.class);
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            ActorRuntime.ActorRef<Integer> forged =
                    (ActorRuntime.ActorRef<Integer>) constructor.newInstance(
                            runtime, owner.id(), owner.kind(), owner.generation());

            Field reservationField =
                    ActorShardColumns.class.getDeclaredField("reservation");
            reservationField.setAccessible(true);
            ActorRuntime.ActorShardLease lease =
                    (ActorRuntime.ActorShardLease) reservationField.get(retained.get());

            assertTrue(lease.ownedBy(runtime, owner));
            assertFalse(lease.ownedBy(runtime, forged),
                    "same runtime/id/generation fields are not an exact ActorRef capability");

            owner.gracefulStop().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void privateShardLeaseIsRevokedBeforeDoneBecomesObservable() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch allocated = new CountDownLatch(1);
            AtomicReference<ActorDenseIndexColumns> retained = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> owner = runtime.spawnPrivate(
                    ctx -> (message, turn) -> {
                        ActorDenseIndexColumns shard = new ActorDenseIndexColumns(
                                ActorShardColumns.Layout.SOA, 8, 1, 1);
                        shard.writeIndex(0, 0, 7);
                        retained.set(shard);
                        allocated.countDown();
                    });

            owner.send(1);
            assertTrue(allocated.await(5, TimeUnit.SECONDS));
            owner.drain().get(5, TimeUnit.SECONDS);
            assertTrue(runtime.privateMemoryBytes() > 0);

            owner.gracefulStop().get(5, TimeUnit.SECONDS);
            owner.getDone().get(5, TimeUnit.SECONDS);

            Field reservationField =
                    ActorDenseIndexColumns.class.getDeclaredField("reservation");
            reservationField.setAccessible(true);
            ActorRuntime.ActorShardLease lease =
                    (ActorRuntime.ActorShardLease) reservationField.get(retained.get());
            Field revokedField =
                    ActorRuntime.ActorShardLease.class.getDeclaredField("revoked");
            revokedField.setAccessible(true);
            var revoked =
                    (java.util.concurrent.atomic.AtomicBoolean) revokedField.get(lease);

            assertTrue(revoked.get(),
                    "DONE must not become observable before private shard revocation");
            assertEquals(0, runtime.privateMemoryBytes(),
                    "private shard bytes must retire before DONE");
            assertThrows(SecurityException.class,
                    () -> retained.get().readIndex(0, 0));
        }
    }

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
}
