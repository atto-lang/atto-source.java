package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * This is an actor-native storage test, not proof of source-compiled inverted
 * classes, generated guest CIL/LLVM, or the physical-turn scheduler.
 */
@Timeout(45)
final class ActorMixedRegionTest {
    private static ActorNativeArena arena(AtomicLong charged) {
        return new ActorNativeArena(2 * 1024 * 1024L, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        });
    }
    private static final List<ActorMixedRegion.Field> FIELDS = List.of(
            new ActorMixedRegion.Field("id", ActorMixedRegion.Kind.U32),
            new ActorMixedRegion.Field("x", ActorMixedRegion.Kind.F64),
            new ActorMixedRegion.Field("flag", ActorMixedRegion.Kind.BOOL),
            new ActorMixedRegion.Field("next", ActorMixedRegion.Kind.REF32));

    private static List<List<String>> groups() {
        return List.of(List.of("id", "flag"), List.of("x", "next"));
    }
    private static int width(ActorMixedRegion.Kind type) {
        return switch (type) {
            case BOOL -> 1;
            case U32, REF32 -> 4;
            case F64 -> 8;
        };
    }

    @Test
    void fourLayoutsPreserveTypedNativeFieldsAndGoldenAddresses() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = arena(charged)) {
            for (ActorMixedRegion.Layout layout : ActorMixedRegion.Layout.values()) {
                int rows = layout == ActorMixedRegion.Layout.AOSOA ? 5 : 3;
                try (var store = new ActorMixedRegion(arena, FIELDS, rows, layout,
                        layout == ActorMixedRegion.Layout.AOSOA ? 4 : 1,
                        layout == ActorMixedRegion.Layout.SOAOS ? groups() : null)) {
                    int expectedBytes = switch (layout) {
                        case AOS, SOAOS -> 72;
                        case SOA -> 56;
                        case AOSOA -> 144;
                    };
                    long expectedPayload = layout == ActorMixedRegion.Layout.SOA
                            ? 51L : expectedBytes;
                    assertEquals(expectedBytes, store.physicalBytes());
                    assertEquals(expectedPayload, store.payloadBytes());
                    assertEquals(expectedBytes, charged.get());

                    HashSet<Integer> occupied = new HashSet<>();
                    for (int row = 0; row < rows; row++) {
                        store.writeU32(row, "id", 0xffff_ffffL - row);
                        store.writeF64(row, "x", row == 0 ? -0.0 :
                                row == 1 ? Double.NaN : row + 0.25);
                        store.writeBool(row, "flag", row % 2 == 0);
                        store.writeRef32(row, "next", row + 1);
                        for (var field : FIELDS) {
                            var address = store.address(row, field.name());
                            int width = width(field.kind());
                            assertEquals(0, address.absoluteByteOffset() % width);
                            assertTrue(address.absoluteByteOffset() <= expectedBytes - width);
                            for (int i = 0; i < width; i++) {
                                assertTrue(occupied.add(address.absoluteByteOffset() + i),
                                        "two mixed fields alias a physical native byte");
                            }
                        }
                    }
                    assertEquals(0xffff_ffffL, store.readU32(0, "id"));
                    assertEquals(Double.doubleToRawLongBits(-0.0),
                            Double.doubleToRawLongBits(store.readF64(0, "x")));
                    assertTrue(Double.isNaN(store.readF64(1, "x")));
                    assertTrue(store.readBool(2, "flag"));
                    assertEquals(rows, store.readRef32(rows - 1, "next"));
                    assertThrows(IllegalArgumentException.class,
                            () -> store.writeU32(0, "id", 0x1_0000_0000L));
                    assertThrows(IllegalArgumentException.class,
                            () -> store.writeU32(0, "next", 1));
                    assertThrows(IllegalArgumentException.class,
                            () -> store.writeF64(0, "flag", 1));
                    assertThrows(IndexOutOfBoundsException.class,
                            () -> store.address(rows, "id"));
                    assertThrows(IndexOutOfBoundsException.class,
                            () -> store.address(0, "toString"));
                    if (layout == ActorMixedRegion.Layout.SOA) {
                        assertEquals(List.of(0L, 16L, 40L, 44L),
                                store.segments().stream().map(ActorMixedRegion.Segment::base).toList());
                        assertEquals(32, store.address(2, "x").absoluteByteOffset());
                    } else if (layout == ActorMixedRegion.Layout.SOAOS) {
                        assertEquals(64, store.address(2, "next").absoluteByteOffset());
                    } else if (layout == ActorMixedRegion.Layout.AOSOA) {
                        assertEquals(72, store.address(4, "id").absoluteByteOffset());
                    }
                }
                assertEquals(0, charged.get(), "actor-owned region memory must refund");
            }
        }
        assertEquals(0, charged.get());
    }

    @Test
    void scheduledActorOwnsMixedBackingAndHostCannotReadIt() throws Exception {
        assertThrows(SecurityException.class, () ->
                ActorMixedRegion.forCurrentActor(FIELDS, 3,
                        ActorMixedRegion.Layout.SOA, 1, null));
        try (ActorRuntime runtime = new ActorRuntime()) {
            var done = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            var retained = new AtomicReference<ActorMixedRegion>();
            var actor = runtime.<Integer>spawnSharedTrusted(ctx -> (message, turn) -> {
                try (var store = ActorMixedRegion.forCurrentActor(
                        FIELDS, 3, ActorMixedRegion.Layout.SOA, 1, null)) {
                    store.writeU32(0, "id", 42);
                    store.writeBool(1, "flag", true);
                    store.writeF64(2, "x", 19.5);
                    retained.set(store);
                    assertEquals(42L, store.readU32(0, "id"));
                    assertTrue(store.readBool(1, "flag"));
                    assertEquals(19.5, store.readF64(2, "x"));
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    done.countDown();
                }
            });
            actor.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS),
                    "real actor mixed-region turn must complete");
            assertNull(failure.get());
            assertNotNull(retained.get());
            assertThrows(SecurityException.class, retained.get()::segments);
            assertThrows(SecurityException.class,
                    () -> retained.get().readU32(0, "id"));
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.actorMemoryBytes());
        }
    }

    @Test
    void invalidGeometryNeverPublishesAnActorNativeAllocation() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = arena(charged)) {
            assertThrows(IllegalArgumentException.class, () ->
                    new ActorMixedRegion(arena, FIELDS, 3, ActorMixedRegion.Layout.SOA,
                            1, null, 55));
            assertThrows(IllegalArgumentException.class, () ->
                    new ActorMixedRegion(arena, FIELDS, 5, ActorMixedRegion.Layout.AOSOA,
                            3, null));
            assertThrows(IllegalArgumentException.class, () ->
                    new ActorMixedRegion(arena, FIELDS, 3, ActorMixedRegion.Layout.SOAOS,
                            1, List.of(List.of("id"), List.of("x"))));
            assertThrows(IllegalArgumentException.class, () ->
                    new ActorMixedRegion(arena, FIELDS, 3, ActorMixedRegion.Layout.SOAOS,
                            1, List.of(List.of("id", "x"), List.of("x", "flag", "next"))));
            assertThrows(IllegalArgumentException.class, () ->
                    new ActorMixedRegion(arena, List.of(
                            new ActorMixedRegion.Field("id", ActorMixedRegion.Kind.U32),
                            new ActorMixedRegion.Field("id", ActorMixedRegion.Kind.F64)),
                            3, ActorMixedRegion.Layout.SOA, 1, null));
            assertThrows(IllegalArgumentException.class, () ->
                    new ActorMixedRegion(arena, FIELDS, Integer.MAX_VALUE,
                            ActorMixedRegion.Layout.AOS, 1, null));
            assertEquals(0, charged.get());
            try (var ok = new ActorMixedRegion(arena, FIELDS, 3,
                    ActorMixedRegion.Layout.SOA, 1, null, 56)) {
                assertEquals(56, charged.get());
                ok.writeU32(0, "id", 42);
                assertEquals(42, ok.readU32(0, "id"));
            }
            assertEquals(0, charged.get());
        }
    }

    @Test
    void mixedWidthKernelUpdatesNativeDataWithoutReplacingOtherColumns() {
        AtomicLong charged = new AtomicLong();
        var fields = List.of(
                new ActorMixedRegion.Field("id", ActorMixedRegion.Kind.U32),
                new ActorMixedRegion.Field("pos", ActorMixedRegion.Kind.F64),
                new ActorMixedRegion.Field("vel", ActorMixedRegion.Kind.F64),
                new ActorMixedRegion.Field("flag", ActorMixedRegion.Kind.BOOL));
        try (ActorNativeArena arena = arena(charged)) {
            try (var store = new ActorMixedRegion(arena, fields, 7,
                    ActorMixedRegion.Layout.SOAOS, 1,
                    List.of(List.of("id", "flag"), List.of("pos", "vel")))) {
                for (int row = 0; row < 7; row++) {
                    store.writeU32(row, "id", row + 10);
                    store.writeF64(row, "pos", row * 10.0);
                    store.writeF64(row, "vel", row + 0.5);
                    store.writeBool(row, "flag", true);
                }
                store.integrate("pos", "vel", 0.25);
                for (int row = 0; row < 7; row++) {
                    assertEquals(row * 10.0 + (row + 0.5) * 0.25,
                            store.readF64(row, "pos"));
                    assertEquals(row + 10, store.readU32(row, "id"));
                    assertTrue(store.readBool(row, "flag"));
                }
                assertThrows(IllegalArgumentException.class,
                        () -> store.integrate("id", "vel", 0.1));
            }
            assertEquals(0, charged.get());
        }
    }
}
