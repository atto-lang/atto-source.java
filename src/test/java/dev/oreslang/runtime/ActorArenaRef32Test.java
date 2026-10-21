package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorArenaRef32Test {
    @Test
    void offsetCodecMatchesLlvmIncludingUnsignedHighBitAndLastWord() {
        assertEquals(34359738360L, ManagedReferencePolicy.MAX_ARENA_SPAN32);
        assertEquals(34359738352L, ManagedReferencePolicy.MAX_ARENA_OFFSET32);
        assertEquals(1, ManagedReferencePolicy.encodeArenaOffset32(0, 64));
        assertEquals(3, ManagedReferencePolicy.encodeArenaOffset32(16, 64));
        assertEquals(16, ManagedReferencePolicy.decodeArenaOffset32(3, 64).orElseThrow());
        assertTrue(ManagedReferencePolicy.decodeArenaOffset32(0, 0).isEmpty());

        long span = ManagedReferencePolicy.MAX_ARENA_SPAN32;
        assertEquals(-1, ManagedReferencePolicy.encodeArenaOffset32(
                ManagedReferencePolicy.MAX_ARENA_OFFSET32, span));
        assertEquals(ManagedReferencePolicy.MAX_ARENA_OFFSET32,
                ManagedReferencePolicy.decodeArenaOffset32(-1, span).orElseThrow());
        assertTrue(ManagedReferencePolicy.encodeArenaOffset32(1L << 34, span) < 0);
        assertThrows(IllegalArgumentException.class,
                () -> ManagedReferencePolicy.encodeArenaOffset32(1, 64));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedReferencePolicy.encodeArenaOffset32(64, 64));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedReferencePolicy.encodeArenaOffset32(0, span + 8));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedReferencePolicy.decodeArenaOffset32(-1, 8));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedReferencePolicy.decodeArenaOffset32(1, span + 8));
    }

    @Test
    void graalDirectRegionResolves32BitOffsetsOnlyUnderActorOwnerAndBeforeRetirement()
            throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ActorRuntime.PrivateMemoryBlock> retained = new AtomicReference<>();
        CountDownLatch verified = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnPrivate(context -> {
                var block = context.privateMemory().orElseThrow().allocatePrivateBytes(24);
                retained.set(block);
                return (message, turn) -> {
                    if (message == 0) {
                        turn.self().stop();
                        return;
                    }
                    try {
                        int ref0 = ActorArenaRef32.at(block, 0);
                        int ref16 = ActorArenaRef32.at(block, 16);
                        assertEquals(1, ref0);
                        assertEquals(3, ref16);
                        ActorArenaRef32.writeInt(block, ref0, message);
                        assertEquals(message.intValue(), ActorArenaRef32.readInt(block, ref0));
                        ActorArenaRef32.writeLong(block, ref16, 0x0123456789abcdefL);
                        assertEquals(0x0123456789abcdefL,
                                ActorArenaRef32.readLong(block, ref16));
                        assertThrows(IllegalArgumentException.class,
                                () -> ActorArenaRef32.readInt(block, 0));
                        assertThrows(IllegalArgumentException.class,
                                () -> ActorArenaRef32.at(block, 1));
                        assertThrows(IllegalArgumentException.class,
                                () -> ActorArenaRef32.at(block, 24));
                        var shortBlock = context.privateMemory().orElseThrow()
                                .allocatePrivateBytes(20);
                        int shortRef16 = ActorArenaRef32.at(shortBlock, 16);
                        assertThrows(IndexOutOfBoundsException.class,
                                () -> ActorArenaRef32.readLong(shortBlock, shortRef16));
                    } catch (Throwable ex) {
                        failure.set(ex);
                    } finally {
                        verified.countDown();
                    }
                };
            });
            actor.send(41);
            assertTrue(verified.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), "owner-checked compressed-region roundtrip");
            // The region is still live; host code outside the actor may not
            // use either a valid encoded word or issue a new local reference.
            assertThrows(IllegalStateException.class,
                    () -> ActorArenaRef32.readInt(retained.get(), 1));
            assertThrows(IllegalStateException.class,
                    () -> ActorArenaRef32.at(retained.get(), 0));
            actor.send(0);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, runtime.privateMemoryBytes());
            // A stale block remains revoked even after actor finalization.
            assertThrows(IllegalStateException.class,
                    () -> ActorArenaRef32.readInt(retained.get(), 1));
        }
    }
}
