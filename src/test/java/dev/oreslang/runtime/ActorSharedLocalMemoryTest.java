package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** SHARED actors own actor-local storage; sharing an ActorRef does not share it. */
@Timeout(20)
final class ActorSharedLocalMemoryTest {
    @Test
    void sharedActorHasPrivateOffHeapStorageWithSeparateLocalAccounting() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorRuntime.PrivateMemoryBlock> block = new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorMemorySlice> localSlice = new AtomicReference<>();
            CountDownLatch written = new CountDownLatch(1);

            var owner = runtime.<String>spawnShared(context -> {
                assertTrue(context.privateMemory().isEmpty(),
                        "legacy privateMemory() must not expose SHARED actor internals");
                localSlice.set(context.localMemory().orElseThrow());
                return (message, turn) -> {
                    assertSame(localSlice.get(), turn.localMemory().orElseThrow());
                    assertTrue(turn.privateMemory().isEmpty());
                    var local = turn.localMemory().orElseThrow();
                    var owned = local.allocateActorLocalBytes(Long.BYTES);
                    owned.writeLong(0, 0x1234_5678_9abc_def0L);
                    assertEquals(0x1234_5678_9abc_def0L, owned.readLong(0));
                    block.set(owned);
                    written.countDown();
                };
            });
            owner.ready().get(5, TimeUnit.SECONDS);
            owner.send("allocate");
            assertTrue(written.await(5, TimeUnit.SECONDS));
            assertNotNull(block.get());
            assertEquals(Long.BYTES, localSlice.get().usedBytes());
            assertEquals(Long.BYTES, runtime.sharedActorLocalMemoryBytes(),
                    "shared actor local allocation must use the dedicated local-arena counter");
            assertEquals(0, runtime.privateMemoryBytes(),
                    "shared local storage must not be mischarged as a private-kind actor");
            long sharedDrainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runtime.sharedMemoryBytes() != 0 && System.nanoTime() < sharedDrainDeadline) {
                Thread.sleep(1);
            }
            assertEquals(0, runtime.sharedMemoryBytes(),
                    "actor-local arena bytes must not be classified as explicit shared memory");
            assertThrows(IllegalStateException.class, () -> block.get().readLong(0),
                    "off-carrier host access must not gain private arena authority");
            assertThrows(IllegalStateException.class, localSlice.get()::close,
                    "an off-carrier slice reference must not grant retirement authority");
            assertFalse(localSlice.get().closed());

            CountDownLatch rejected = new CountDownLatch(1);
            var foreign = runtime.<String>spawnShared(() -> (message, turn) -> {
                assertThrows(IllegalStateException.class, () -> block.get().readLong(0),
                        "a second SHARED actor must not access a sibling's local memory");
                assertThrows(IllegalStateException.class, localSlice.get()::close,
                        "a sibling actor must not revoke another actor's local heap");
                assertFalse(localSlice.get().closed());
                rejected.countDown();
                turn.self().stop();
            });
            foreign.ready().get(5, TimeUnit.SECONDS);
            foreign.send("try");
            assertTrue(rejected.await(5, TimeUnit.SECONDS));
            assertTrue(foreign.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(foreign.failure().isEmpty());

            owner.stop();
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(owner.failure().isEmpty(), () -> owner.failure().toString());
            assertTrue(block.get().closed());
            assertTrue(localSlice.get().closed());
            assertEquals(0, runtime.privateMemoryBytes());
            assertEquals(0, runtime.sharedMemoryBytes());
            assertEquals(0, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void sharedActorLiveTurnDoesNotAuthorizeConcurrentOffCarrierReads() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch inTurn = new CountDownLatch(1);
            CountDownLatch releaseTurn = new CountDownLatch(1);
            AtomicReference<ActorRuntime.PrivateMemoryBlock> block = new AtomicReference<>();

            var owner = runtime.<String>spawnShared(() -> (message, turn) -> {
                var local = turn.localMemory().orElseThrow();
                var owned = local.allocateActorLocalBytes(Long.BYTES);
                owned.writeLong(0, 0x1020_3040_5060_7080L);
                block.set(owned);
                inTurn.countDown();
                try {
                    assertTrue(releaseTurn.await(5, TimeUnit.SECONDS),
                            "the parent must release the live actor turn");
                    assertEquals(0x1020_3040_5060_7080L, owned.readLong(0),
                            "failed foreign reads cannot revoke or mutate owned storage");
                } finally {
                    turn.self().stop();
                }
            });
            owner.ready().get(5, TimeUnit.SECONDS);
            owner.send("live-turn");
            assertTrue(inTurn.await(5, TimeUnit.SECONDS));
            try {
                assertThrows(IllegalStateException.class, () -> block.get().readLong(0),
                        "an active carrier does not confer authority on another host thread");
                assertThrows(IllegalStateException.class, block.get()::close,
                        "a foreign carrier cannot retire live actor-owned bytes");
                assertEquals(Long.BYTES, runtime.sharedActorLocalMemoryBytes());
            } finally {
                releaseTurn.countDown();
            }
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(owner.failure().isEmpty(), () -> owner.failure().toString());
            assertTrue(block.get().closed());
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void sharedActorHeapReservationReleaseRejectsOffCarrierAndSiblingActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch reserved = new CountDownLatch(1);
            CountDownLatch refunded = new CountDownLatch(1);
            CountDownLatch siblingDenied = new CountDownLatch(1);
            AtomicReference<ActorRuntime.MemoryReservation> held = new AtomicReference<>();

            // Do not monopolize a shared carrier while waiting for a sibling.
            // Distinct mailbox turns prove that reservation ownership survives
            // turn boundaries without granting the next carrier authority.
            var owner = runtime.<String>spawnShared(() -> (message, turn) -> {
                if ("reserve".equals(message)) {
                    var memory = turn.localMemory().orElseThrow();
                    held.set(memory.reserveHeap(64));
                    reserved.countDown();
                } else if ("release".equals(message)) {
                    var memory = turn.localMemory().orElseThrow();
                    assertEquals(64L, memory.usedBytes());
                    held.get().close();
                    assertEquals(0L, memory.usedBytes());
                    assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
                    refunded.countDown();
                    turn.self().stop();
                }
            });
            owner.ready().get(5, TimeUnit.SECONDS);
            owner.send("reserve");
            assertTrue(reserved.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, held.get()::close,
                    "an off-carrier host cannot refund an actor-owned heap reservation");
            assertEquals(64L, runtime.sharedActorLocalMemoryBytes());

            var sibling = runtime.<String>spawnShared(() -> (message, turn) -> {
                assertThrows(IllegalStateException.class, held.get()::close,
                        "a sibling SHARED actor cannot refund the owner's heap");
                siblingDenied.countDown();
                turn.self().stop();
            });
            sibling.ready().get(5, TimeUnit.SECONDS);
            sibling.send("foreign-refund");
            assertTrue(siblingDenied.await(5, TimeUnit.SECONDS));
            assertTrue(sibling.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(sibling.failure().isEmpty(), () -> sibling.failure().toString());
            assertEquals(64L, runtime.sharedActorLocalMemoryBytes(),
                    "rejected foreign refunds must leave accounting unchanged");

            owner.send("release");
            assertTrue(refunded.await(5, TimeUnit.SECONDS));
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(owner.failure().isEmpty(), () -> owner.failure().toString());
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void sharedActorZeroByteDirectBlocksStillConsumeQuotaAndHaveBoundedHandles() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<ActorRuntime.PrivateMemoryBlock> stale = new AtomicReference<>();
            var owner = runtime.<String>spawnShared(() -> (message, turn) -> {
                var local = turn.localMemory().orElseThrow();
                for (int i = 0; i < 1024; ++i) {
                    var block = local.allocateActorLocalBytes(0);
                    assertEquals(0, block.capacity());
                    if (i == 0) stale.set(block);
                }
                assertEquals(1024L, local.usedBytes(),
                        "zero-byte native blocks must not bypass actor accounting");
                assertEquals(1024L, runtime.sharedActorLocalMemoryBytes());
                var error = assertThrows(IllegalStateException.class,
                        () -> local.allocateActorLocalBytes(0));
                assertTrue(error.getMessage().contains("block limit exceeded"));
                assertEquals(1024L, local.usedBytes(),
                        "rejected handle allocation must not leak quota");
                // Closing one block must release exactly one charged handle and
                // permit a fresh allocation without reviving the old handle.
                var oldHandle = stale.get();
                oldHandle.close();
                assertEquals(1023L, local.usedBytes());
                var replacement = local.allocateActorLocalBytes(0);
                assertEquals(0, replacement.capacity());
                assertTrue(oldHandle.closed());
                assertEquals(1024L, local.usedBytes());
                stale.set(replacement);
                checked.countDown();
                turn.self().stop();
            });
            owner.ready().get(5, TimeUnit.SECONDS);
            owner.send("allocate");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(owner.failure().isEmpty(), () -> owner.failure().toString());
            assertTrue(stale.get().closed(), "actor retirement must invalidate zero-byte handles");
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void sharedBlockCloseRetriesFailedAggregateRefundWithoutReopeningBytes() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var aggregateField = ActorRuntime.class.getDeclaredField("sharedActorLocalMemoryBytes");
            aggregateField.setAccessible(true);
            var aggregate = (java.util.concurrent.atomic.AtomicLong) aggregateField.get(runtime);
            CountDownLatch checked = new CountDownLatch(1);
            var owner = runtime.<String>spawnShared(() -> (message, turn) -> {
                var slice = turn.localMemory().orElseThrow();
                var block = slice.allocateActorLocalBytes(8);
                block.writeLong(0, 42);
                assertEquals(8, aggregate.get());
                // A native/global accounting failure must not permanently
                // consume the right to refund this already-scrubbed block.
                aggregate.set(0);
                assertThrows(IllegalStateException.class, block::close);
                assertTrue(block.closed());
                assertEquals(8, slice.usedBytes());
                assertThrows(IllegalStateException.class, () -> block.readLong(0));
                aggregate.set(8);
                block.close();
                block.close(); // retry remains idempotent
                assertEquals(0, aggregate.get());
                assertEquals(0, slice.usedBytes());

                var reservation = slice.reserveHeap(8);
                aggregate.set(0);
                assertThrows(IllegalStateException.class, reservation::close);
                assertEquals(8, slice.usedBytes());
                aggregate.set(8);
                reservation.close();
                reservation.close();
                assertEquals(0, aggregate.get());
                assertEquals(0, slice.usedBytes());
                checked.countDown();
                turn.self().stop();
            });
            owner.ready().get(5, TimeUnit.SECONDS);
            owner.send("retry-refund");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(owner.failure().isEmpty(), () -> owner.failure().toString());
        }
    }

    @Test
    void sharedSliceRetirementRetriesFailedAggregateRefundAfterRevocation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var aggregateField = ActorRuntime.class.getDeclaredField("sharedActorLocalMemoryBytes");
            aggregateField.setAccessible(true);
            var aggregate = (java.util.concurrent.atomic.AtomicLong) aggregateField.get(runtime);
            CountDownLatch checked = new CountDownLatch(1);
            var owner = runtime.<String>spawnShared(() -> (message, turn) -> {
                var slice = turn.localMemory().orElseThrow();
                var block = slice.allocateActorLocalBytes(8);
                block.writeLong(0, 123);
                aggregate.set(0);
                assertThrows(IllegalStateException.class, slice::close);
                assertTrue(slice.closed());
                assertTrue(block.closed());
                assertThrows(IllegalStateException.class, () -> block.readLong(0));
                assertEquals(8, slice.usedBytes(),
                        "failed retirement must retain the charge for retry");
                aggregate.set(8);
                slice.close();
                slice.close();
                assertEquals(0, slice.usedBytes());
                assertEquals(0, aggregate.get());
                checked.countDown();
                turn.self().stop();
            });
            owner.ready().get(5, TimeUnit.SECONDS);
            owner.send("retry-retirement");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(owner.failure().isEmpty(), () -> owner.failure().toString());
        }
    }

    @Test
    void alreadyClosingRuntimeStillRefundsActorLocalReservations() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            // Model the shutdown window after runtime.closed is published but
            // before the last admitted carrier returns its outstanding loan.
            var closedField = ActorRuntime.class.getDeclaredField("closed");
            closedField.setAccessible(true);
            var closing = (java.util.concurrent.atomic.AtomicBoolean) closedField.get(runtime);
            CountDownLatch checked = new CountDownLatch(1);
            var owner = runtime.<String>spawnShared(() -> (message, turn) -> {
                var local = turn.localMemory().orElseThrow();
                var reservation = local.reserveHeap(8);
                assertEquals(8L, runtime.sharedActorLocalMemoryBytes());
                closing.set(true);
                try {
                    reservation.close();
                    assertEquals(0L, local.usedBytes());
                    assertEquals(0L, runtime.sharedActorLocalMemoryBytes(),
                            "closing must not suppress aggregate refunds");
                } finally {
                    // Restore runtime state before requesting normal termination.
                    closing.set(false);
                }
                checked.countDown();
                turn.self().stop();
            });
            owner.ready().get(5, TimeUnit.SECONDS);
            owner.send("closing-refund");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(owner.failure().isEmpty(), () -> owner.failure().toString());
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void shutdownAccountingFailureCompletesDoneAsFailureInsteadOfStrandingActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var aggregateField = ActorRuntime.class.getDeclaredField("sharedActorLocalMemoryBytes");
            aggregateField.setAccessible(true);
            var aggregate = (java.util.concurrent.atomic.AtomicLong) aggregateField.get(runtime);
            CountDownLatch allocated = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorMemorySlice> slice = new AtomicReference<>();

            var owner = runtime.<String>spawnShared(() -> (message, turn) -> {
                var local = turn.localMemory().orElseThrow();
                local.allocateActorLocalBytes(8).writeLong(0, 42);
                slice.set(local);
                // Model a corrupt external refund counter at finalization.
                aggregate.set(0);
                allocated.countDown();
                turn.self().stop();
            });
            owner.ready().get(5, TimeUnit.SECONDS);
            owner.send("corrupt-refund");
            assertTrue(allocated.await(5, TimeUnit.SECONDS));
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS),
                    "CONTROL must complete termination even after a failed arena refund");
            assertTrue(owner.failure().isPresent(),
                    "accounting failure must not masquerade as graceful termination");
            assertTrue(owner.failure().orElseThrow().getMessage().contains("underflow"));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> owner.getDone().get(5, TimeUnit.SECONDS));
            assertTrue(slice.get().closed(), "failed refund must still revoke memory");
            assertEquals(8L, slice.get().usedBytes(),
                    "failed aggregate refund remains charged for diagnosis/retry");
        }
    }

    @Test
    void sharedActorLocalQuotaCanRejectWithoutAggregateAccountingLeak() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch checked = new CountDownLatch(1);
            var owner = runtime.<String>spawnShared(() -> (message, turn) -> {
                var memory = turn.localMemory().orElseThrow();
                long before = memory.usedBytes();
                assertThrows(IllegalStateException.class,
                        () -> memory.reserveHeap(Long.MAX_VALUE));
                assertEquals(before, memory.usedBytes());
                checked.countDown();
                turn.self().stop();
            });
            owner.ready().get(5, TimeUnit.SECONDS);
            owner.send("quota");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(owner.failure().isEmpty());
            assertEquals(0, runtime.sharedMemoryBytes());
            assertEquals(0, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void sharedActorLocalArenaAndConcurrentMailboxAdmissionShareOneActorQuota() throws Exception {
        long actorLimit = 16L * 1024 * 1024;
        IsolatePolicy actorPolicy = new IsolatePolicy(
                Set.of(IsolatePolicy.Capability.SHARED_MEMORY),
                actorLimit,
                32,
                Duration.ofSeconds(30));

        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch localReserved = new CountDownLatch(1);
            CountDownLatch releaseTurn = new CountDownLatch(1);
            AtomicReference<ActorRuntime.MemoryReservation> held = new AtomicReference<>();

            ActorRuntime.ActorRef<Object> owner = runtime.spawnShared(actorPolicy, context ->
                    (message, turn) -> {
                        if ("hold".equals(message)) {
                            held.set(turn.localMemory().orElseThrow().reserveHeap(12L * 1024 * 1024));
                            localReserved.countDown();
                            assertTrue(releaseTurn.await(5, TimeUnit.SECONDS));
                            turn.self().stop();
                        }
                    });

            owner.ready().get(5, TimeUnit.SECONDS);
            owner.send("hold");
            assertTrue(localReserved.await(5, TimeUnit.SECONDS));
            assertEquals(12L * 1024 * 1024, runtime.sharedActorLocalMemoryBytes());

            byte[] oversizedWhileLocalArenaIsLive = new byte[8 * 1024 * 1024];
            IllegalStateException rejected = assertThrows(
                    IllegalStateException.class,
                    () -> owner.send(oversizedWhileLocalArenaIsLive));
            assertTrue(rejected.getMessage().contains("shared actor mailbox memory limit exceeded"),
                    () -> "unexpected rejection: " + rejected.getMessage());

            assertEquals(12L * 1024 * 1024, runtime.sharedActorLocalMemoryBytes(),
                    "failed mailbox admission must not perturb local arena accounting");
            releaseTurn.countDown();
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(owner.failure().isEmpty(), () -> owner.failure().toString());
            assertNotNull(held.get());
            assertEquals(0, runtime.sharedActorLocalMemoryBytes());
            assertEquals(0, runtime.sharedMemoryBytes());
        }
    }
}
