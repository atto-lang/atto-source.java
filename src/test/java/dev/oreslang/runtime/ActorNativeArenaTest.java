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
            assertTrue(fields instanceof ActorNativeArena.NativeValue);
            assertTrue(((ActorNativeArena.NativeValue) fields).nativeOffset() >= 0);
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
        long leftOffset = ((ActorNativeArena.NativeValue) left).nativeOffset();
        long rightOffset = ((ActorNativeArena.NativeValue) right).nativeOffset();
        assertNotEquals(leftOffset, rightOffset, "distinct actor objects need distinct logical addresses");
        assertTrue(leftOffset >= 0 && rightOffset >= 0);
        Map<String, Object> foreign = second.fields(List.of("next"), new Object());
        assertThrows(SecurityException.class, () -> left.put("next", foreign));
        first.close();
        first.close();
        assertThrows(IllegalStateException.class, () -> left.get("next"));
        second.close();
        assertEquals(0, charged.get());
    }

    @Test
    void nativeReferencesRequireArenaIssuedProvenanceNotSelfAssertedOffsets() {
        AtomicLong charged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(8192, charged)) {
            Map<String, Object> holder = arena.fields(List.of("value"), marker);
            Map<String, Object> backing = arena.fields(List.of("data"), marker);
            ActorNativeArena.NativeValue fields = (ActorNativeArena.NativeValue) backing;
            long issuedAddress = fields.nativeOffset();
            ActorNativeArena.NativeValue forged = new ActorNativeArena.NativeValue() {
                @Override public ActorNativeArena nativeArena() { return arena; }
                @Override public long nativeOffset() { return issuedAddress; }
            };
            assertThrows(SecurityException.class, () -> holder.put("value", forged),
                    "a Java object claiming a legitimate address is not an arena-issued capability");
            assertSame(marker, holder.get("value"),
                    "failed admission must not publish a guest reference");
            holder.put("value", backing);
            assertSame(backing, holder.get("value"));

            // A facade may be registered to a real native allocation, but it
            // must not silently replace an existing object identity at that offset.
            arena.registerNativeFacade(forged, fields);
            assertThrows(SecurityException.class, () -> holder.put("value", forged),
                    "two facades must not alias the same offset with different identities");
            assertSame(backing, holder.get("value"),
                    "address collisions must leave the previously stored reference intact");

            ActorNativeArena.NativeValue unissued = new ActorNativeArena.NativeValue() {
                @Override public ActorNativeArena nativeArena() { return arena; }
                @Override public long nativeOffset() { return issuedAddress + 8; }
            };
            assertThrows(SecurityException.class, () -> arena.registerNativeFacade(unissued, fields),
                    "facade registration must verify its exact issued backing address");
            assertSame(backing, holder.get("value"));
        }
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
    void sameLengthFieldTextUpdatesReuseNativeAllocation() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = arena(40, charged)) {
            Map<String, Object> fields = arena.fields(List.of("value"), new Object());
            fields.put("value", "alfa");
            long allocated = arena.allocatedBytes();
            for (int i = 0; i < 1000; i++) {
                fields.put("value", (i & 1) == 0 ? "beta" : "zeta");
                assertEquals((i & 1) == 0 ? "beta" : "zeta", fields.get("value"));
            }
            assertEquals(allocated, arena.allocatedBytes(),
                    "same-size text mutation must not consume arena lifetime budget");
            fields.put("value", new BigInteger("1234"));
            assertEquals(new BigInteger("1234"), fields.get("value"));
            assertEquals(allocated, arena.allocatedBytes(),
                    "same-size native text should be reusable across supported tagged types");
            assertThrows(IllegalStateException.class, () -> fields.put("value", "larger"));
            assertEquals(new BigInteger("1234"), fields.get("value"),
                    "failed growth must preserve the old field");
        }
        assertEquals(0, charged.get());
    }

    @Test
    void readOnlyNativeLeasesFreezeWritesUntilReleaseAndRevokeAtActorExit() throws Exception {
        AtomicLong charged = new AtomicLong();
        Thread owner = Thread.currentThread();
        ActorNativeArena arena = new ActorNativeArena(64, () -> {
            if (Thread.currentThread() != owner) {
                throw new SecurityException("foreign native region access");
            }
        }, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        });
        var region = arena.region(64);
        region.writeLong(8, 77L);
        var first = region.leaseReadOnly(8, 16);
        var second = region.leaseReadOnly(8, 8);
        assertEquals(77L, first.readLong(0));
        assertEquals(77L, second.readLong(0));
        assertThrows(IndexOutOfBoundsException.class, () -> first.readLong(9));
        assertThrows(IndexOutOfBoundsException.class, () -> region.leaseReadOnly(60, 8));
        assertThrows(IllegalStateException.class, () -> region.writeLong(8, 100L));
        assertThrows(IllegalStateException.class, () -> region.writeDouble(8, 1.0));
        assertThrows(IllegalStateException.class, region::close);
        AtomicReference<Throwable> foreign = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try { first.readLong(0); }
            catch (Throwable failure) { foreign.set(failure); }
        });
        other.start();
        other.join();
        assertInstanceOf(SecurityException.class, foreign.get());
        first.close();
        first.close();
        assertThrows(IllegalStateException.class, () -> first.readLong(0));
        assertThrows(IllegalStateException.class, () -> region.writeLong(8, 100L));
        second.close();
        region.writeLong(8, 100L);
        assertEquals(100L, region.readLong(8));
        var outstanding = region.leaseReadOnly(8, 8);
        arena.close();
        assertThrows(IllegalStateException.class, () -> outstanding.readLong(0));
        outstanding.close();
        assertEquals(0, charged.get());
    }

    @Test
    void actorTaskFramesPersistResumeAuthorityInNativeMemory() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = arena(4096, charged)) {
            ActorNativeArena.TaskFrame frame = arena.taskFrame();
            long address = frame.nativeOffset();
            assertTrue(address >= 0);
            assertEquals(ActorNativeArena.TaskPhase.NEW, frame.phase());
            assertEquals(0, frame.resumeCount());

            frame.beginInitialResume();
            assertEquals(ActorNativeArena.TaskPhase.RUNNING, frame.phase());
            assertEquals(1, frame.resumeCount());
            long awaitTicket = frame.awaiting();
            assertTrue(awaitTicket > 0);
            assertEquals(ActorNativeArena.TaskPhase.AWAITING, frame.phase());
            assertThrows(IllegalStateException.class,
                    () -> frame.beginResume(ActorNativeArena.SuspensionKind.COOPERATE, awaitTicket));
            assertThrows(IllegalStateException.class,
                    () -> frame.beginResume(ActorNativeArena.SuspensionKind.AWAIT, awaitTicket + 1));

            frame.beginResume(ActorNativeArena.SuspensionKind.AWAIT, awaitTicket);
            assertEquals(2, frame.resumeCount());
            long cooperateTicket = frame.cooperating();
            assertTrue(cooperateTicket > awaitTicket);
            assertEquals(ActorNativeArena.TaskPhase.COOPERATING, frame.phase());
            assertThrows(IllegalStateException.class,
                    () -> frame.beginResume(ActorNativeArena.SuspensionKind.COOPERATE, awaitTicket));

            frame.beginResume(ActorNativeArena.SuspensionKind.COOPERATE, cooperateTicket);
            assertEquals(3, frame.resumeCount());
            frame.done();
            assertEquals(ActorNativeArena.TaskPhase.DONE, frame.phase());
            assertThrows(IllegalStateException.class,
                    () -> frame.beginResume(ActorNativeArena.SuspensionKind.COOPERATE, cooperateTicket));

            ActorNativeArena.TaskFrame failed = arena.taskFrame();
            assertNotEquals(address, failed.nativeOffset());
            failed.beginInitialResume();
            long failedTicket = failed.awaiting();
            failed.failed();
            assertThrows(IllegalStateException.class,
                    () -> failed.beginResume(ActorNativeArena.SuspensionKind.AWAIT, failedTicket));
            assertEquals(ActorNativeArena.TaskPhase.FAILED, failed.phase());
        }
        assertEquals(0, charged.get());
    }

    @Test
    void nativeObjectsRegionsAndLinearPagesShareOneNonRecycledActorOffsetSpace() {
        AtomicLong charged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(128L * 1024, charged)) {
            Map<String, Object> fields = arena.fields(List.of("left", "right"), marker);
            long fieldsBase = ((ActorNativeArena.NativeValue) fields).nativeOffset();

            var region = arena.region(32);
            long regionBase = region.nativeOffset();

            ActorLinearMemory memory = arena.linearMemory(1, 1);
            long pageBase = memory.actorOffset(0);

            assertTrue(fieldsBase >= 0);
            assertTrue(regionBase >= 0);
            assertTrue(pageBase >= 0);
            assertNotEquals(fieldsBase, regionBase);
            assertNotEquals(fieldsBase, pageBase);
            assertNotEquals(regionBase, pageBase);

            // Native object references now carry the canonical actor-relative
            // offset instead of a Java-side reference-table ordinal.
            Map<String, Object> child = arena.fields(List.of("value"), marker);
            fields.put("left", child);
            assertSame(child, fields.get("left"));

            long retired = region.nativeOffset();
            region.close();
            var replacement = arena.region(32);
            assertNotEquals(retired, replacement.nativeOffset(),
                    "actor-relative addresses must not ABA-recycle within one actor lifetime");
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
