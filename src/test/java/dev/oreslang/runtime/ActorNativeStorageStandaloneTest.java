package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Host-storage-only coverage. No ActorRuntime scheduling, guest code, or zero-GC
 * claim: those require separate runtime-integration and JDK25/Native Image gates.
 */
@Timeout(30)
final class ActorNativeStorageStandaloneTest {
    @Test
    void arenaCleanupRetriesRefundFailureWithoutRestoringAccessOrDoubleFree() {
        AtomicLong charged = new AtomicLong();
        AtomicBoolean fail = new AtomicBoolean(true);
        ActorNativeArena a = new ActorNativeArena(256 * 1024, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> {
                if (fail.getAndSet(false)) throw new IllegalStateException("refund fault");
                charged.addAndGet(-bytes);
            };
        });
        var region = a.region(32);
        a.region(64);
        var fields = a.fields(List.of("count"), new Object());
        fields.put("count", 4L);
        assertThrows(IllegalStateException.class, a::close);
        assertTrue(a.closed(), "failed cleanup must keep guest authority revoked");
        assertThrows(IllegalStateException.class, () -> region.readByte(0));
        assertThrows(IllegalStateException.class, () -> fields.get("count"));
        assertThrows(IllegalStateException.class, () -> a.region(8));
        a.close();
        assertEquals(0, charged.get());
        assertEquals(0, a.committedBytes());
        a.close();
        assertEquals(0, charged.get(), "successful refunds must not repeat");
    }

    @Test
    void explicitlyClosedRegionRetainsPendingRefundForArenaCleanup() {
        AtomicLong charged = new AtomicLong();
        AtomicBoolean fail = new AtomicBoolean(true);
        ActorNativeArena a = new ActorNativeArena(1024, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> {
                if (fail.getAndSet(false)) throw new IllegalStateException("refund fault");
                charged.addAndGet(-bytes);
            };
        });
        var region = a.region(32);
        assertThrows(IllegalStateException.class, region::close);
        assertTrue(region.closed());
        assertEquals(32, charged.get());
        a.close();
        assertEquals(0, charged.get());
        a.close();
        assertEquals(0, charged.get());
    }

    @Test
    void slabRefundFailureRetriesAfterNativeBytesWereAlreadyReleased() {
        AtomicLong charged = new AtomicLong();
        AtomicBoolean fail = new AtomicBoolean(true);
        ActorNativeArena a = new ActorNativeArena(8192, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> {
                if (fail.getAndSet(false)) throw new IllegalStateException("refund fault");
                charged.addAndGet(-bytes);
            };
        });
        a.fields(List.of("count"), new Object());
        assertThrows(IllegalStateException.class, a::close);
        assertTrue(a.closed());
        assertEquals(0, a.committedBytes(), "native slab is freed before refund");
        a.close();
        assertEquals(0, charged.get());
        a.close();
        assertEquals(0, charged.get());
    }

    @Test
    void failedPrepublicationAllocationRetainsRefundForRetry() throws Exception {
        AtomicLong regionCharge = new AtomicLong();
        AtomicBoolean regionRefundFails = new AtomicBoolean(true);
        ActorNativeArena regionArena = new ActorNativeArena(1024, () -> {}, bytes -> {
            regionCharge.addAndGet(bytes);
            return () -> {
                if (regionRefundFails.get()) throw new IllegalStateException("refund fault");
                regionCharge.addAndGet(-bytes);
            };
        });
        var nextOffset = ActorNativeArena.class.getDeclaredField("nextActorOffset");
        nextOffset.setAccessible(true);
        nextOffset.setLong(regionArena, Long.MAX_VALUE - 3);
        ArithmeticException regionFailure = assertThrows(
                ArithmeticException.class, () -> regionArena.region(8));
        assertEquals(1, regionFailure.getSuppressed().length);
        assertEquals(8, regionCharge.get(), "failed refund must stay charged until retry");
        assertEquals(0, regionArena.committedBytes(), "failed region was never published");
        regionRefundFails.set(false);
        regionArena.close();
        assertEquals(0, regionCharge.get());

        AtomicLong slabCharge = new AtomicLong();
        AtomicBoolean slabRefundFails = new AtomicBoolean(true);
        ActorNativeArena slabArena = new ActorNativeArena(8192, () -> {}, bytes -> {
            slabCharge.addAndGet(bytes);
            return () -> {
                if (slabRefundFails.get()) throw new IllegalStateException("refund fault");
                slabCharge.addAndGet(-bytes);
            };
        });
        nextOffset.setLong(slabArena, Long.MAX_VALUE - 3);
        ArithmeticException slabFailure = assertThrows(
                ArithmeticException.class, () -> slabArena.fields(List.of("count"), new Object()));
        assertEquals(1, slabFailure.getSuppressed().length);
        assertTrue(slabCharge.get() > 0, "failed slab refund must remain retained");
        assertEquals(0, slabArena.committedBytes(), "failed slab was never published");
        slabRefundFails.set(false);
        slabArena.close();
        assertEquals(0, slabCharge.get());
    }

    private static ActorNativeArena arena(long maximum, AtomicLong charged, AtomicBoolean owner) {
        return new ActorNativeArena(maximum, () -> {
            if (!owner.get()) throw new SecurityException("native storage off actor turn");
        }, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        });
    }

    @Test
    void persistentFieldsAreOwnedAndRevokedOnClose() {
        AtomicLong charged = new AtomicLong();
        AtomicBoolean owner = new AtomicBoolean(true);
        Object uninitialized = new Object();
        ActorNativeArena a = arena(256 * 1024, charged, owner);
        Map<String, Object> fields = a.fields(List.of("count"), uninitialized);
        assertSame(uninitialized, fields.get("count"));
        fields.put("count", 17L);
        assertEquals(17L, fields.get("count"));
        owner.set(false);
        assertThrows(SecurityException.class, () -> fields.get("count"));
        assertThrows(SecurityException.class, () -> fields.put("count", 18L));
        owner.set(true);
        assertEquals(17L, fields.get("count"));
        assertTrue(charged.get() > 0);
        a.close();
        assertEquals(0, charged.get());
        assertThrows(IllegalStateException.class, () -> fields.get("count"));
    }

    @Test
    void independentRegionsRejectWritesWhileReadLeaseExists() {
        AtomicLong charged = new AtomicLong();
        AtomicBoolean owner = new AtomicBoolean(true);
        try (ActorNativeArena a = arena(256 * 1024, charged, owner)) {
            var first = a.region(32);
            var second = a.region(32);
            first.writeLong(0, 111L);
            second.writeLong(0, 222L);
            try (var lease = first.leaseReadOnly(0, Long.BYTES)) {
                assertEquals(111L, lease.readLong(0));
                assertThrows(IllegalStateException.class, () -> first.writeLong(0, 9L));
                second.writeLong(0, 223L);
                assertEquals(223L, second.readLong(0));
            }
            first.writeLong(0, 112L);
            assertEquals(112L, first.readLong(0));
        }
        assertEquals(0, charged.get());
    }

    @Test
    void logicalLinearMemoryPreservesOffsetsAcrossGrowthAndBoundaries() {
        AtomicLong charged = new AtomicLong();
        AtomicBoolean owner = new AtomicBoolean(true);
        try (ActorNativeArena a = arena(256 * 1024, charged, owner)) {
            ActorLinearMemory mem = a.linearMemory(1, 2);
            assertEquals(1, mem.pages());
            assertEquals(ActorLinearMemory.PAGE_BYTES, mem.byteLength());
            mem.writeLong(8, 0x0102030405060708L);
            assertEquals(1, mem.grow(1));
            assertEquals(2, mem.pages());
            assertEquals(0x0102030405060708L, mem.readLong(8));
            long spanning = ActorLinearMemory.PAGE_BYTES - 4L;
            mem.writeLong(spanning, 0x1122334455667788L);
            assertEquals(0x1122334455667788L, mem.readLong(spanning));
            assertThrows(IndexOutOfBoundsException.class, () -> mem.readByte(mem.byteLength()));
            assertThrows(IllegalArgumentException.class, () -> mem.grow(1));
        }
        assertEquals(0, charged.get());
    }

    @Test
    void crossActorRegionAuthorityCannotBeStoredInAnotherArena() {
        AtomicLong leftCharge = new AtomicLong();
        AtomicLong rightCharge = new AtomicLong();
        AtomicBoolean leftOwner = new AtomicBoolean(true);
        AtomicBoolean rightOwner = new AtomicBoolean(true);
        Object uninitialized = new Object();
        ActorNativeArena left = arena(256 * 1024, leftCharge, leftOwner);
        ActorNativeArena right = arena(256 * 1024, rightCharge, rightOwner);
        try {
            ActorNativeArena.Region leftRegion = left.region(32);
            Map<String, Object> rightFields = right.fields(List.of("foreign"), uninitialized);
            assertThrows(SecurityException.class, () -> rightFields.put("foreign", leftRegion));
            assertSame(uninitialized, rightFields.get("foreign"));
            leftRegion.writeLong(0, 314L);
            assertEquals(314L, leftRegion.readLong(0));

            // Both actors have guest-linear offset zero, but their physical pages,
            // owner checks, field authority, and read-only leases must remain private.
            ActorLinearMemory leftMemory = left.linearMemory(1, 1);
            ActorLinearMemory rightMemory = right.linearMemory(1, 1);
            leftMemory.writeLong(0, 111L);
            rightMemory.writeLong(0, 222L);
            assertEquals(111L, leftMemory.readLong(0));
            assertEquals(222L, rightMemory.readLong(0));

            leftOwner.set(false);
            assertThrows(SecurityException.class, () -> leftMemory.readLong(0));
            assertThrows(SecurityException.class, () -> leftMemory.writeLong(0, 333L));
            rightMemory.writeLong(0, 223L);
            assertEquals(223L, rightMemory.readLong(0),
                    "peer actor must remain live when the other actor is off-turn");
            leftOwner.set(true);
            assertEquals(111L, leftMemory.readLong(0));

            try (var leftLoan = leftMemory.leaseReadOnlyPage(0, 0, Long.BYTES)) {
                assertThrows(IllegalStateException.class,
                        () -> leftMemory.writeLong(0, 333L));
                rightOwner.set(false);
                assertThrows(SecurityException.class, () -> rightMemory.readLong(0));
                rightOwner.set(true);
                rightMemory.writeLong(0, 224L);
                assertEquals(111L, leftMemory.readLong(0));
                assertEquals(224L, rightMemory.readLong(0));
            }
            leftMemory.writeLong(0, 112L);
            assertEquals(112L, leftMemory.readLong(0));
            assertEquals(224L, rightMemory.readLong(0));

            left.close();
            assertTrue(left.closed());
            assertEquals(0, leftCharge.get());
            assertThrows(IllegalStateException.class, () -> leftMemory.readLong(0));
            assertFalse(right.closed());
            assertTrue(rightCharge.get() > 0,
                    "retiring one actor must not refund the peer actor's storage");
            assertEquals(224L, rightMemory.readLong(0));
            rightMemory.writeLong(0, 225L);
            assertEquals(225L, rightMemory.readLong(0),
                    "peer actor storage must survive another actor's retirement");
        } finally {
            leftOwner.set(true);
            rightOwner.set(true);
            if (!left.closed()) left.close();
            if (!right.closed()) right.close();
        }
        assertEquals(0, leftCharge.get());
        assertEquals(0, rightCharge.get());
    }

    @Test
    void physicalRetirementWaitsForOutstandingReadOnlyLease() {
        AtomicLong charged = new AtomicLong();
        AtomicBoolean owner = new AtomicBoolean(true);
        ActorNativeArena a = arena(256 * 1024, charged, owner);
        var region = a.region(32);
        region.writeLong(0, 94L);
        var loan = region.leaseReadOnly(0, Long.BYTES);
        assertThrows(IllegalStateException.class, a::close);
        assertFalse(a.closed());
        assertEquals(94L, loan.readLong(0));
        loan.close();
        a.close();
        assertTrue(a.closed());
        assertEquals(0, charged.get());
        assertThrows(IllegalStateException.class, () -> region.readLong(0));
    }

    @Test
    void crossPageWordWriteFailsAtomicallyWhileSecondPageIsLeased() {
        AtomicLong charged = new AtomicLong();
        AtomicBoolean owner = new AtomicBoolean(true);
        try (ActorNativeArena a = arena(256 * 1024, charged, owner)) {
            ActorLinearMemory mem = a.linearMemory(2, 2);
            long boundary = ActorLinearMemory.PAGE_BYTES - 4L;
            mem.writeLong(boundary, 0x0102030405060708L);
            try (var lease = mem.leaseReadOnlyPage(1, 0, 4)) {
                assertThrows(IllegalStateException.class,
                        () -> mem.writeLong(boundary, 0x1020304050607080L));
                assertEquals(0x0102030405060708L, mem.readLong(boundary),
                        "preflight must reject before writing any bytes");
            }
            mem.writeLong(boundary, 0x1020304050607080L);
            assertEquals(0x1020304050607080L, mem.readLong(boundary));
        }
        assertEquals(0, charged.get());
    }

    @Test
    void bulkCopyPreflightsEveryDestinationPageBeforeMutation() {
        AtomicLong charged = new AtomicLong();
        AtomicBoolean owner = new AtomicBoolean(true);
        try (ActorNativeArena a = arena(256 * 1024, charged, owner)) {
            ActorLinearMemory mem = a.linearMemory(2, 2);
            long destination = ActorLinearMemory.PAGE_BYTES - 8L;
            mem.fill(0, 16, (byte) 91);
            mem.fill(destination, 16, (byte) 13);
            try (var lease = mem.leaseReadOnlyPage(1, 0, 8)) {
                assertThrows(IllegalStateException.class,
                        () -> mem.copy(destination, 0, 16));
                for (int index = 0; index < 16; index++) {
                    assertEquals(13, mem.readUnsignedByte(destination + index),
                            "leased second page must prevent partial first-page writes");
                }
            }
            mem.copy(destination, 0, 16);
            for (int index = 0; index < 16; index++) {
                assertEquals(91, mem.readUnsignedByte(destination + index));
            }
        }
        assertEquals(0, charged.get());
    }

    @Test
    void failedGrowLeavesOriginalPagesAndBudgetUnchanged() {
        AtomicLong charged = new AtomicLong();
        AtomicBoolean owner = new AtomicBoolean(true);
        try (ActorNativeArena a = arena(ActorLinearMemory.PAGE_BYTES + 1024L, charged, owner)) {
            ActorLinearMemory mem = a.linearMemory(1, 2);
            long before = charged.get();
            assertThrows(IllegalArgumentException.class, () -> mem.grow(1));
            assertEquals(1, mem.pages());
            assertEquals(before, charged.get());
            mem.writeByte(0, (byte) 7);
            assertEquals(7, mem.readUnsignedByte(0));
        }
        assertEquals(0, charged.get());
    }
}

