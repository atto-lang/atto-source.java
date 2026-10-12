package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * All actor kinds must be able to own mutable direct memory, while the
 * shared actor's SyncCell/Shared<T> capabilities remain a different domain.
 * These tests prove ownership checks, NOT independent JVM tracing GC.
 */
final class ActorOwnedHeapRegionTest {
    @Test
    void separateSharedActorsGetDistinctActorOwnedRegions() throws Exception {
        AtomicReference<ActorRuntime.PrivateMemoryBlock> first = new AtomicReference<>();
        AtomicReference<ActorRuntime.PrivateMemoryBlock> second = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch initialized = new CountDownLatch(2);
        CountDownLatch crossProbed = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var alice = runtime.<String>spawnShared(() -> (message, ctx) -> {
                try {
                    if (message.equals("allocate")) {
                        var owned = ctx.actorOwnedMemory().orElseThrow();
                        assertTrue(ctx.privateMemory().isEmpty(),
                                "shared memory access must remain explicit");
                        var block = owned.allocatePrivateBytes(32);
                        block.writeInt(0, 11);
                        first.set(block);
                        initialized.countDown();
                    } else if (message.equals("cross")) {
                        assertEquals(11, first.get().readInt(0));
                        assertThrows(IllegalStateException.class,
                                () -> second.get().readInt(0),
                                "shared actor must not read another actor's owned heap");
                        crossProbed.countDown();
                    }
                } catch (Throwable problem) {
                    error.compareAndSet(null, problem);
                    initialized.countDown();
                    crossProbed.countDown();
                }
            });
            var bob = runtime.<String>spawnShared(() -> (message, ctx) -> {
                try {
                    var block = ctx.actorOwnedMemory().orElseThrow().allocatePrivateBytes(32);
                    block.writeInt(0, 22);
                    second.set(block);
                    initialized.countDown();
                } catch (Throwable problem) {
                    error.compareAndSet(null, problem);
                    initialized.countDown();
                }
            });
            alice.send("allocate");
            bob.send("allocate");
            assertTrue(initialized.await(10, TimeUnit.SECONDS));
            if (error.get() != null) fail(error.get());
            assertNotNull(first.get());
            assertNotNull(second.get());
            assertNotEquals(first.get().owner(), second.get().owner());
            assertThrows(IllegalStateException.class, () -> first.get().readInt(0),
                    "the supervisor may hold only an inert wrapper, not dereference guest memory");
            alice.send("cross");
            assertTrue(crossProbed.await(10, TimeUnit.SECONDS));
            if (error.get() != null) fail(error.get());
            assertTrue(runtime.heapStatusFor(alice).actorOwnedMemorySlice());
            assertFalse(runtime.heapStatusFor(alice).independentlyCollectable());
        }
        assertTrue(first.get().closed(), "actor termination must invalidate direct blocks");
        assertTrue(second.get().closed(), "actor termination must invalidate direct blocks");
    }
}
