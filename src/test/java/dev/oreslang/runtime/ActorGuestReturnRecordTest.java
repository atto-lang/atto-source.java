package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host ABI-only tests. The Java guest evaluator still uses the JVM stack;
 * these cursor records do not certify compiler-backed return-PC lowering.
 */
final class ActorGuestReturnRecordTest {
    @Test
    void exactActorOwnedReturnPcRecordRoundTripsAndZeroizesOnUnwind() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                ActorFrameArena store = ActorRuntime.currentActorFrameArena();
                arena.set(store);
                var cursor = new ActorGuestReturnRecord.Cursor(4, 8, 13, 2);
                try (var record = ActorGuestReturnRecord.enter(cursor)) {
                    assertEquals(cursor, record.readCursor());
                    assertEquals(1, store.liveFrames());
                    assertEquals(ActorGuestReturnRecord.RECORD_BYTES, store.usedBytes());
                    assertEquals(ActorGuestReturnRecord.RECORD_BYTES, store.peakBytes());
                }
                assertEquals(0, store.usedBytes());
                assertEquals(0, store.liveFrames());
                turn.self().stop();
            });
            actor.send("return");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "record test failed: " + actor.failure());
            assertTrue(arena.get().isRetired());
        }
    }

    @Test
    void foreignCarrierCannotReadOrRetireAnOwnedReturnCursor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                var cursor = new ActorGuestReturnRecord.Cursor(1, 2, 3, 4);
                try (var record = ActorGuestReturnRecord.enter(cursor)) {
                    Thread intruder = new Thread(() -> {
                        try {
                            assertThrows(SecurityException.class, record::readCursor);
                            assertThrows(SecurityException.class, record::close);
                        } catch (Throwable failure) {
                            unexpected.set(failure);
                        }
                    });
                    intruder.start();
                    intruder.join(2000);
                    assertFalse(intruder.isAlive());
                    assertNull(unexpected.get());
                    assertEquals(cursor, record.readCursor());
                }
                turn.self().stop();
            });
            actor.send("foreign");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void callReturnRecordFrameQuotaFailsActorLocallyWithoutKillingSibling()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorFrameArena> brokenArena = new AtomicReference<>();
            AtomicInteger healthy = new AtomicInteger();
            var exhausted = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
                brokenArena.set(arena);
                ArrayDeque<ActorGuestReturnRecord> stack = new ArrayDeque<>();
                try {
                    assertThrows(ActorFrameArena.BudgetExceeded.class, () -> {
                        for (int i = 0; i < 65; i++) {
                            stack.addLast(ActorGuestReturnRecord.enter(
                                    new ActorGuestReturnRecord.Cursor(i, i, i + 1, i)));
                        }
                    });
                    assertTrue(arena.isPoisoned());
                    assertEquals(64, stack.size());
                } finally {
                    while (!stack.isEmpty()) stack.removeLast().close();
                }
                assertEquals(0, arena.usedBytes());
            });
            var sibling = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                healthy.incrementAndGet();
                turn.self().stop();
            });
            exhausted.send("exhaust");
            assertTrue(exhausted.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(brokenArena.get().isRetired());
            sibling.send("alive");
            assertTrue(sibling.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(sibling.failure().isEmpty());
            assertEquals(1, healthy.get());
        }
    }

    @Test
    void malformedReturnDescriptorsAreRejectedBeforeActorAllocation() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGuestReturnRecord.Cursor(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGuestReturnRecord.Cursor(1, -1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGuestReturnRecord.Cursor(1, 1, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGuestReturnRecord.Cursor(1, 1, 1, -1));
        assertThrows(SecurityException.class, () ->
                ActorGuestReturnRecord.enter(
                        new ActorGuestReturnRecord.Cursor(1, 2, 3, 0)));
    }
}
