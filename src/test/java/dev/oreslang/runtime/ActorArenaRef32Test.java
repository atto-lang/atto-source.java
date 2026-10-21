package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

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
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnPrivate(context -> {
                var block = context.privateMemory().orElseThrow().allocatePrivateBytes(24);
                retained.set(block);
                return (message, turn) -> {
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
                        assertThrows(IndexOutOfBoundsException.class,
                                () -> ActorArenaRef32.readLong(block,
                                        ManagedReferencePolicy.encodeArenaOffset32(24 - 8, 24 + 1)));
                    } catch (Throwable ex) {
                        failure.set(ex);
                    } finally {
                        turn.self().stop();
                    }
                };
            });
            actor.send(41);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(failure.get(), "owner-checked compressed-region roundtrip");
            assertEquals(0L, runtime.privateMemoryBytes());
            assertThrows(IllegalStateException.class,
                    () -> ActorArenaRef32.readInt(retained.get(), 1));
        }
    }
}
