package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** SHARED actors own actor-local storage; sharing an ActorRef does not share it. */
@Timeout(20)
final class ActorSharedLocalMemoryTest {
    @Test
    void sharedActorHasPrivateOffHeapStorageChargedToSharedAggregate() throws Exception {
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
            assertTrue(runtime.sharedMemoryBytes() >= Long.BYTES,
                    "shared actor local allocation must debit the shared aggregate");
            assertEquals(0, runtime.privateMemoryBytes(),
                    "shared local storage must not be mischarged as a private-kind actor");
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
        }
    }
}
