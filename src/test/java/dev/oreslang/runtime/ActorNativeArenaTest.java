package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class ActorNativeArenaTest {
    @Test
    void nativeSlotsPreserveScalarTypesAndRejectManagedPayloadFallback() {
        AtomicLong charged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(1 << 20, charged)) {
            Map<String, Object> fields = arena.fields(List.of("value"), marker);
            for (Object value : List.of(true, (byte) 1, (short) 2, 3, Long.MAX_VALUE,
                    -0.0f, Double.NaN, '\ud800', "\ud800\u0000\udc00", "", new BigInteger("12345678901234567890"),
                    new BigDecimal("1.2300"), OresSymbol.process("ready"), marker)) {
                fields.put("value", value);
                assertEquals(value, fields.get("value"));
                assertEquals(value.getClass(), fields.get("value").getClass());
            }
            fields.put("value", 42L);
            long allocated = arena.allocatedBytes();
            for (int i = 0; i < 10_000; i++) fields.put("value", (long) i);
            assertEquals(allocated, arena.allocatedBytes(), "scalar mutation must not allocate native payloads");
            assertThrows(IllegalArgumentException.class, () -> fields.put("value", new Object()));
            assertEquals(9999L, fields.get("value"), "failed stores cannot corrupt existing data");
            assertThrows(IllegalArgumentException.class, () -> fields.put("unknown", 1));
            fields.put("value", null);
            assertTrue(fields.containsKey("value"));
            assertNull(fields.get("value"));
            assertTrue(charged.get() > 0);
        }
        assertEquals(0, charged.get(), "native bytes must be refunded by explicit close");
    }

    @Test
    void referencesStayInTheirArenaAndCyclesDoNotPreventReclamation() {
        AtomicLong charged = new AtomicLong();
        ActorNativeArena first = arena(8192, charged);
        ActorNativeArena second = arena(8192, charged);
        Map<String, Object> left = first.fields(List.of("next"), new Object());
        Map<String, Object> right = first.fields(List.of("next"), new Object());
        left.put("next", right);
        right.put("next", left);
        assertSame(right, left.get("next"));
        assertSame(left, right.get("next"));
        Map<String, Object> foreign = second.fields(List.of("next"), new Object());
        assertThrows(SecurityException.class, () -> left.put("next", foreign));
        first.close();
        first.close();
        assertThrows(IllegalStateException.class, () -> left.get("next"));
        second.close();
        assertEquals(0, charged.get());
    }

    @Test
    void primitiveRegionsHaveExplicitReleaseBoundsAndBudget() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = arena(128, charged)) {
            var region = arena.region(128);
            region.writeLong(0, Long.MIN_VALUE);
            region.writeDouble(120, 3.5);
            assertEquals(Long.MIN_VALUE, region.readLong(0));
            assertEquals(3.5, region.readDouble(120));
            assertThrows(IndexOutOfBoundsException.class, () -> region.readLong(121));
            assertThrows(IndexOutOfBoundsException.class, () -> region.writeLong(-1, 3));
            assertThrows(IllegalArgumentException.class, () -> arena.region(8));
            region.close();
            region.close();
            assertTrue(region.closed());
            assertEquals(0, charged.get());
            assertThrows(IllegalStateException.class, () -> region.readLong(0));
            // Releasing a dense store makes the same budget reusable.
            arena.region(128).writeLong(0, 7);
        }
        assertEquals(0, charged.get());
    }

    @Test
    void everyActorKindHasDistinctStorageAndFreesItAfterTheLastTurn() throws Exception {
        for (var kind : ActorRuntime.ActorKind.values()) {
            try (ActorRuntime runtime = new ActorRuntime()) {
                var arena = new AtomicReference<ActorNativeArena>();
                var fields = new AtomicReference<Map<String, Object>>();
                var failure = new AtomicReference<Throwable>();
                var finished = new CountDownLatch(1);
                var actor = runtime.<Integer>spawn(kind, runtime.policyCeiling(), () -> (message, turn) -> {
                    try {
                        var current = ActorRuntime.currentNativeArena();
                        if (message == 1) {
                            arena.set(current);
                            fields.set(current.fields(List.of("count"), new Object()));
                            fields.get().put("count", 41L);
                        } else {
                            assertSame(arena.get(), current);
                            fields.get().put("count", (long) fields.get().get("count") + 1);
                            assertEquals(42L, fields.get().get("count"));
                            finished.countDown();
                        }
                    } catch (Throwable problem) {
                        failure.set(problem);
                        finished.countDown();
                    }
                });
                actor.send(1);
                actor.send(2);
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                assertNull(failure.get());
                assertThrows(SecurityException.class, () -> fields.get().get("count"));
                actor.stop();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(arena.get().closed());
                assertEquals(0, arena.get().committedBytes());
                assertEquals(0, runtime.actorMemoryBytes());
            }
        }
    }

    private static ActorNativeArena arena(long limit, AtomicLong charged) {
        return new ActorNativeArena(limit, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        });
    }
}
