package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host/runtime typed primitive call-frame tests, not stackless .atto proof.
 * Every test must run in real JUnit at the exact PR head before promotion.
 */
final class GuestScalarCallFrameTest {
    @Test
    void codecPreservesEveryPrimitiveWrapperWithoutImplicitConversion() {
        List<Object> values = List.of(
                (byte) -128, (short) 32767, Integer.MIN_VALUE, Long.MAX_VALUE,
                Float.intBitsToFloat(0x7fc00055), Double.longBitsToDouble(0x7ff8000000000042L),
                true, 'あ');
        byte[] encoded = GuestScalarFrameCodec.encode(values);
        assertNotNull(encoded);
        assertEquals(values.size() * GuestScalarFrameCodec.SLOT_BYTES, encoded.length);
        List<Object> restored = GuestScalarFrameCodec.decode(encoded);
        assertEquals(values.size(), restored.size());
        for (int index = 0; index < values.size(); index++) {
            assertEquals(values.get(index).getClass(), restored.get(index).getClass());
        }
        assertEquals(Byte.valueOf((byte) -128), restored.get(0));
        assertEquals(Short.valueOf((short) 32767), restored.get(1));
        assertEquals(Integer.MIN_VALUE, restored.get(2));
        assertEquals(Long.MAX_VALUE, restored.get(3));
        assertEquals(0x7fc00055,
                Float.floatToRawIntBits((Float) restored.get(4)));
        assertEquals(0x7ff8000000000042L,
                Double.doubleToRawLongBits((Double) restored.get(5)));
        assertEquals(true, restored.get(6));
        assertEquals('あ', restored.get(7));
        encoded[0] = 0;
        assertEquals((byte) -128, restored.getFirst(),
                "decoded values must not alias the mutable input bytes");
    }

    @Test
    void codecRejectsReferencesAndMalformedTypedValues() {
        assertNull(GuestScalarFrameCodec.encode(List.of(new Object())));
        assertNull(GuestScalarFrameCodec.encode(List.of("literal")));
        assertNull(GuestScalarFrameCodec.encode(Arrays.asList((Object) null)));
        assertNull(GuestScalarFrameCodec.encode(
                java.util.Collections.nCopies(GuestScalarFrameCodec.MAX_SCALAR_ARGUMENTS + 1, 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> GuestScalarFrameCodec.decode(new byte[8]));
        byte[] unknownTag = new byte[9];
        unknownTag[0] = 99;
        assertThrows(IllegalArgumentException.class,
                () -> GuestScalarFrameCodec.decode(unknownTag));
        byte[] invalidBoolean = ByteBuffer.allocate(9)
                .order(ByteOrder.BIG_ENDIAN).put((byte) 7).putLong(2).array();
        assertThrows(IllegalArgumentException.class,
                () -> GuestScalarFrameCodec.decode(invalidBoolean));
        byte[] invalidByte = ByteBuffer.allocate(9)
                .order(ByteOrder.BIG_ENDIAN).put((byte) 1).putLong(256).array();
        assertThrows(IllegalArgumentException.class,
                () -> GuestScalarFrameCodec.decode(invalidByte));
        byte[] invalidFloat = ByteBuffer.allocate(9)
                .order(ByteOrder.BIG_ENDIAN).put((byte) 5).putLong(0x1_0000_0000L).array();
        assertThrows(IllegalArgumentException.class,
                () -> GuestScalarFrameCodec.decode(invalidFloat));
    }

    @Test
    void compilerCallArgumentsRoundTripThroughOneActorOwnedFrame() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<ActorFrameArena> observed = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                ActorFrameArena frames = ActorRuntime.currentActorFrameArena();
                observed.set(frames);
                List<Object> scalarArguments = List.of(123L, false, 3.5d, (byte) -7);
                int expectedFrameBytes = 16 + scalarArguments.size() * 9;
                try (GuestInvocationFrame frame =
                        GuestInvocationFrame.enterWithArguments(0, scalarArguments)) {
                    assertEquals(1, frames.liveFrames());
                    assertEquals(expectedFrameBytes, frames.usedBytes(),
                            "exact typed frame size is reserved once, not double-charged");
                    List<Object> decoded = frame.restoreScalarArguments(scalarArguments);
                    assertNotSame(scalarArguments, decoded);
                    assertEquals(scalarArguments, decoded);
                    frame.tailTransferWithArguments(2, List.of(-9L, true), 1);
                    assertEquals(List.of(-9L, true),
                            frame.restoreScalarArguments(List.of(-9L, true)));
                    assertEquals(expectedFrameBytes, frames.usedBytes());
                    assertEquals(1, frames.liveFrames(), "proper tail transfer stays in one frame");
                }
                assertEquals(0, frames.usedBytes());
                turn.self().stop();
            });
            actor.send("roundtrip");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());
            assertEquals(16 + 4 * 9, observed.get().peakBytes());
            assertTrue(observed.get().isRetired());
        }
    }

    @Test
    void tailTransferToLargerArityFallsBackWithoutGrowingStack() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
                try (GuestInvocationFrame frame =
                        GuestInvocationFrame.enterWithArguments(0, List.of(1L))) {
                    assertEquals(32, arena.usedBytes());
                    List<Object> expanded = List.of(1L, 2L, 3L, 4L, 5L, 6L);
                    frame.tailTransferWithArguments(1, expanded, 1);
                    assertSame(expanded, frame.restoreScalarArguments(expanded),
                            "a tail call may grow its arity without inventing another frame");
                    assertEquals(32, arena.usedBytes());
                    Object reference = new Object();
                    List<Object> pointerArgs = List.of(reference);
                    frame.tailTransferWithArguments(1, pointerArgs, 2);
                    assertSame(pointerArgs, frame.restoreScalarArguments(pointerArgs),
                            "unsupported object references are never stored in actor bytes");
                    assertEquals(32, arena.usedBytes());
                }
                turn.self().stop();
            });
            actor.send("tail");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());
        }
    }

    @Test
    void foreignCarrierCannotRehydrateOrTransferScalarFrames() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<Throwable> error = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                try (GuestInvocationFrame frame =
                        GuestInvocationFrame.enterWithArguments(1, List.of(42L))) {
                    Thread intruder = new Thread(() -> {
                        try {
                            assertThrows(SecurityException.class,
                                    () -> frame.restoreScalarArguments(List.of(42L)));
                            assertThrows(SecurityException.class,
                                    () -> frame.tailTransferWithArguments(0, List.of(0L), 1));
                            assertThrows(SecurityException.class, frame::close);
                        } catch (Throwable fault) {
                            error.set(fault);
                        }
                    });
                    intruder.start();
                    intruder.join(2000);
                    assertFalse(intruder.isAlive());
                    assertNull(error.get());
                    assertEquals(List.of(42L), frame.restoreScalarArguments(List.of(42L)));
                }
                turn.self().stop();
            });
            actor.send("foreign");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());
        }
    }

    @Test
    void actorByteQuotaFailsBeforeNativeDepthAndSiblingSurvives() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<ActorFrameArena> failed = new AtomicReference<>();
            AtomicInteger siblingRuns = new AtomicInteger();
            List<Long> heavyArguments = java.util.Collections.nCopies(256, 1L);
            var exhausted = runtime.<String>spawnShared(factory -> (message, turn) -> {
                ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
                failed.set(arena);
                ArrayDeque<GuestInvocationFrame> frames = new ArrayDeque<>();
                try {
                    assertThrows(ActorFrameArena.BudgetExceeded.class, () -> {
                        for (int i = 0; i < 64; i++) {
                            frames.addLast(GuestInvocationFrame.enterWithArguments(0, heavyArguments));
                        }
                    });
                    assertTrue(arena.isPoisoned());
                    assertTrue(frames.size() < GuestCallDepthGuard.MAX_SYNCHRONOUS_GUEST_CALLS,
                            "byte quota must trip before the 64-frame count guard");
                } finally {
                    while (!frames.isEmpty()) frames.removeLast().close();
                }
                assertEquals(0, arena.usedBytes());
                // The host handler swallowed the error, but the actor must
                // terminate at the mailbox fail-stop boundary.
            });
            var healthy = runtime.<String>spawnShared(factory -> (message, turn) -> {
                try (GuestInvocationFrame frame =
                        GuestInvocationFrame.enterWithArguments(1, List.of(42L))) {
                    assertEquals(List.of(42L), frame.restoreScalarArguments(List.of(42L)));
                    siblingRuns.incrementAndGet();
                }
                turn.self().stop();
            });
            exhausted.send("overflow");
            assertTrue(exhausted.awaitTermination(3, TimeUnit.SECONDS));
            assertInstanceOf(ActorFrameArena.BudgetExceeded.class,
                    exhausted.failure().orElseThrow());
            assertTrue(failed.get().isRetired());
            assertEquals(0, failed.get().usedBytes());
            healthy.send("alive");
            assertTrue(healthy.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(healthy.failure().isEmpty());
            assertEquals(1, siblingRuns.get());
        }
    }
    @Test
    void malformedTypedFrameCannotBeSwallowedToContinueActor() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<ActorFrameArena> poisoned = new AtomicReference<>();
            AtomicInteger unprocessed = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
                poisoned.set(arena);
                try (GuestInvocationFrame frame =
                        GuestInvocationFrame.enterWithArguments(0, List.of(1L))) {
                    assertThrows(IllegalStateException.class,
                            () -> frame.restoreScalarArguments(List.of(1L, 2L)));
                    assertTrue(arena.isPoisoned());
                }
                unprocessed.incrementAndGet();
                // Even though the host caught the invalid compiler-frame
                // exception, the next mailbox message cannot be admitted.
            });
            actor.send("invalid");
            actor.send("must-not-run");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertInstanceOf(ActorFrameArena.BudgetExceeded.class,
                    actor.failure().orElseThrow());
            assertEquals(1, unprocessed.get());
            assertTrue(poisoned.get().isRetired());
        }
    }

}
