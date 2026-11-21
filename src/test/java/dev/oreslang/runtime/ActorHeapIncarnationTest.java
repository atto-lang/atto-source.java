package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Spec chapter 27: every actor incarnation, of every kind, owns one private
 * 32-bit heap, usable only during its own turns and retired after quiescence.
 */
final class ActorHeapIncarnationTest {

    /** Turn body that allocates one region, writes a value, and reports what it saw. */
    private record Observation(ActorNativeArena heap, long offset, long value) { }

    private static ActorRuntime.Behavior<Long> recorder(CompletableFuture<Observation> seen) {
        return (value, context) -> {
            ActorNativeArena heap = context.heap();
            ActorNativeArena.Region region = heap.region(64);
            region.writeLong(0, value);
            seen.complete(new Observation(heap, region.nativeOffset(), region.readLong(0)));
        };
    }

    @Test
    void privateAndSharedActorsEachOwnAPrivate32BitHeap() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CompletableFuture<Observation> privateSeen = new CompletableFuture<>();
            CompletableFuture<Observation> sharedSeen = new CompletableFuture<>();
            var privateActor = runtime.<Long>spawnPrivate(() -> recorder(privateSeen));
            var sharedActor = runtime.<Long>spawnShared(() -> recorder(sharedSeen));
            privateActor.send(11L);
            sharedActor.send(22L);
            Observation p = privateSeen.get(5, TimeUnit.SECONDS);
            Observation s = sharedSeen.get(5, TimeUnit.SECONDS);
            assertNotSame(p.heap(), s.heap(), "every actor owns its own heap, SHARED included");
            assertEquals(11L, p.value());
            assertEquals(22L, s.value());
            // Offsets are actor-relative 32-bit logical addresses, not host pointers:
            // two independent heaps may reuse the same logical offset.
            assertTrue(p.offset() >= 0 && p.offset() < (1L << 32));
            assertTrue(s.offset() >= 0 && s.offset() < (1L << 32));
            assertEquals(p.offset(), s.offset(), "independent heaps start their own offset spaces");
        }
    }

    @Test
    void aHeapIsUnusableOutsideItsOwnActorTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CompletableFuture<Observation> seen = new CompletableFuture<>();
            var owner = runtime.<Long>spawnShared(() -> recorder(seen));
            owner.send(1L);
            ActorNativeArena captured = seen.get(5, TimeUnit.SECONDS).heap();
            // Host thread, off-turn.
            assertThrows(SecurityException.class, () -> captured.region(8));
            // Another actor's turn.
            CompletableFuture<Throwable> foreign = new CompletableFuture<>();
            var other = runtime.<Long>spawnShared(() -> (v, context) -> {
                try {
                    captured.region(8);
                    foreign.complete(null);
                } catch (Throwable failure) {
                    foreign.complete(failure);
                }
            });
            other.send(0L);
            assertTrue(foreign.get(5, TimeUnit.SECONDS) instanceof SecurityException,
                    "a foreign actor turn cannot use another actor's heap");
        }
    }

    @Test
    void theHeapPersistsAcrossTurnsOfOneIncarnation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            List<Long> reads = new CopyOnWriteArrayList<>();
            AtomicReference<ActorNativeArena.Region> region = new AtomicReference<>();
            CompletableFuture<Void> done = new CompletableFuture<>();
            var actor = runtime.<Long>spawnShared(() -> (value, context) -> {
                if (region.get() == null) region.set(context.heap().region(8));
                ActorNativeArena.Region r = region.get();
                r.writeLong(0, r.readLong(0) + value);
                reads.add(r.readLong(0));
                if (reads.size() == 3) done.complete(null);
            });
            actor.send(1L);
            actor.send(2L);
            actor.send(3L);
            done.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1L, 3L, 6L), reads, "state in the heap survives between turns");
        }
    }

    @Test
    void theHeapIsBoundedByThePolicyAndReleasedWhenTheIncarnationRetires() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IsolatePolicy base = IsolatePolicy.developer();
            IsolatePolicy small = new IsolatePolicy(base.capabilities(), 16L * 1024 * 1024,
                    base.maxMailboxMessages(), base.maxWallTime(), base.adversarial());
            long sharedBefore = runtime.runtimeDiagnostics().sharedMemoryBytes();
            CompletableFuture<Throwable> overLimit = new CompletableFuture<>();
            AtomicReference<ActorNativeArena> heapRef = new AtomicReference<>();
            CompletableFuture<Void> allocated = new CompletableFuture<>();
            var actor = runtime.<Long>spawnShared(small, () -> (value, context) -> {
                ActorNativeArena heap = context.heap();
                heapRef.set(heap);
                if (value == 0L) {
                    heap.region(4096);
                    allocated.complete(null);
                } else {
                    try {
                        heap.region(17 * 1024 * 1024);
                        overLimit.complete(null);
                    } catch (Throwable failure) {
                        overLimit.complete(failure);
                    }
                }
            });
            actor.send(0L);
            allocated.get(5, TimeUnit.SECONDS);
            assertTrue(runtime.runtimeDiagnostics().sharedMemoryBytes() > sharedBefore,
                    "heap slabs are charged to the runtime budget");
            actor.send(1L);
            assertNotNull(overLimit.get(5, TimeUnit.SECONDS),
                    "an allocation past the policy's maxHeapBytes is refused");

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(sharedBefore, runtime.runtimeDiagnostics().sharedMemoryBytes(),
                    "retiring the incarnation refunds its heap");
            assertThrows(IllegalStateException.class, () -> heapRef.get().region(8),
                    "a retired incarnation's heap is closed");
        }
    }
}
