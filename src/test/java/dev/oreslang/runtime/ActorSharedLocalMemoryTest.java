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

            CountDownLatch rejected = new CountDownLatch(1);
            var foreign = runtime.<String>spawnShared(() -> (message, turn) -> {
                assertThrows(IllegalStateException.class, () -> block.get().readLong(0),
                        "a second SHARED actor must not access a sibling's local memory");
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
