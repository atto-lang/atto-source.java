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
    void retirementRejectsNewNativeFacadesAndCapabilitiesWithoutRevokingExistingReads() {
        AtomicLong charged = new AtomicLong();
        AtomicBoolean owner = new AtomicBoolean(true);
        ActorNativeArena arena = arena(256 * 1024, charged, owner);
        Object uninitialized = new Object();
        Map<String, Object> fields = arena.fields(List.of("value"), uninitialized);
        fields.put("value", 37L);
        ActorNativeArena.NativeValue backing = (ActorNativeArena.NativeValue) fields;
        ActorNativeArena.NativeValue facade = new ActorNativeArena.NativeValue() {
            @Override public ActorNativeArena nativeArena() { return arena; }
            @Override public long nativeOffset() { return backing.nativeOffset(); }
        };
        ActorNativeArena.NativeCapability preexisting = () -> arena;
        arena.registerNativeFacade(facade, backing);
        arena.issueNativeCapability(preexisting);

        long before = charged.get();
        arena.retire();
        // Existing admitted payloads may be inspected during CONTROL cleanup.
        assertEquals(37L, fields.get("value"));
        assertEquals(before, charged.get());

        ActorNativeArena.NativeValue lateFacade = new ActorNativeArena.NativeValue() {
            @Override public ActorNativeArena nativeArena() { return arena; }
            @Override public long nativeOffset() { return backing.nativeOffset(); }
        };
        ActorNativeArena.NativeCapability lateCapability = () -> arena;
        assertThrows(IllegalStateException.class,
                () -> arena.registerNativeFacade(lateFacade, backing));
        assertThrows(IllegalStateException.class,
                () -> arena.issueNativeCapability(lateCapability));
        assertThrows(IllegalStateException.class, () -> arena.nativeList());
        assertThrows(IllegalStateException.class, () -> arena.valueCell(4L, uninitialized));
        assertThrows(IllegalStateException.class, () -> arena.taskFrame());
        assertEquals(37L, fields.get("value"), "denied issuance cannot alter live state");
        assertEquals(before, charged.get(), "denied issuance cannot charge new bytes");

        arena.close();
        assertEquals(0L, charged.get());
        assertThrows(IllegalStateException.class, () -> fields.get("value"));
    }

    @Test
    void liveArenaCannotBeClosedBeforeRuntimeRetirement() {
        AtomicLong charged = new AtomicLong();
        AtomicBoolean owner = new AtomicBoolean(true);
        ActorNativeArena a = arena(256 * 1024, charged, owner);
        var region = a.region(32);
        region.writeLong(0, 731L);
        long before = charged.get();

        assertThrows(IllegalStateException.class, a::close);
        assertFalse(a.closed(), "a live actor must keep its native bytes");
        assertEquals(before, charged.get());
        assertEquals(731L, region.readLong(0));
        owner.set(false);
        assertThrows(IllegalStateException.class, a::close);
        owner.set(true);

        a.retire();
        assertThrows(IllegalStateException.class, () -> a.region(8));
        assertThrows(IllegalStateException.class, () -> a.linearMemory(0, 1));
        assertThrows(IllegalStateException.class, () -> a.fields(List.of("late"), new Object()));
        a.close();
        assertEquals(0, charged.get());
        assertThrows(IllegalStateException.class, () -> region.readLong(0));
    }

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
        a.retire();
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
        a.retire();
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
        a.retire();
        assertThrows(IllegalStateException.class, a::close);
        assertTrue(a.closed());
        assertEquals(0, a.committedBytes(), "native slab is freed before refund");
        a.close();
        assertEquals(0, charged.get());
        a.close();
        assertEquals(0, charged.get());
    }


    @Test
    void retiredActorCannotReclaimOrCorruptSurvivingActorsPhysicalHeap() {
        AtomicLong aCharge = new AtomicLong(), bCharge = new AtomicLong(), nextCharge = new AtomicLong();
        AtomicBoolean owner = new AtomicBoolean(true);
        ActorNativeArena a = arena(256 * 1024, aCharge, owner);
        ActorNativeArena b = arena(256 * 1024, bCharge, owner);
        ActorLinearMemory am = a.linearMemory(1, 1);
        ActorLinearMemory bm = b.linearMemory(1, 1);
        am.writeLong(0, 111L);
        bm.writeLong(0, 222L);
        var borrowedA = am.leaseReadOnlyPage(0, 0, Long.BYTES);
        a.retire();
        assertThrows(IllegalStateException.class, a::close,
                "CONTROL must not reclaim an actor heap with an outstanding read loan");
        assertEquals(111L, borrowedA.readLong(0));
        bm.writeLong(0, 223L);
        assertEquals(223L, bm.readLong(0),
                "B must make progress while A is retired but pinned");
        borrowedA.close();
        a.close();
        assertEquals(0L, aCharge.get());
        assertThrows(IllegalStateException.class, () -> am.readLong(0),
                "stale A heap access must be revoked");
        assertEquals(223L, bm.readLong(0), "A reclamation must not affect B");
        ActorNativeArena replacement = arena(256 * 1024, nextCharge, owner);
        ActorLinearMemory newMemory = replacement.linearMemory(1, 1);
        assertEquals(0L, newMemory.readLong(0),
                "new actor's offset zero must have fresh physical zero-filled storage");
        newMemory.writeLong(0, 999L);
        assertEquals(223L, bm.readLong(0),
                "fresh actor offset zero must not alias survivor B");
        assertEquals(999L, newMemory.readLong(0));
        b.retire();
        replacement.retire();
        b.close();
        replacement.close();
        assertEquals(0L, bCharge.get());
        assertEquals(0L, nextCharge.get());
    }

    @Test
    void deniedActorHeapGrowthCannotMutateNeighborsQuotaOrStorage() {
        AtomicLong aCharge = new AtomicLong(), bCharge = new AtomicLong();
        AtomicBoolean owner = new AtomicBoolean(true);
        ActorNativeArena a = arena(256 * 1024, aCharge, owner);
        ActorNativeArena b = arena(256 * 1024, bCharge, owner);
        ActorLinearMemory am = a.linearMemory(1, 1);
        ActorLinearMemory bm = b.linearMemory(1, 2);
        am.writeLong(0, 71L);
        bm.writeLong(0, 83L);
        long beforeA = aCharge.get(), beforeB = bCharge.get();
        assertThrows(IllegalArgumentException.class, () -> am.grow(1));
        assertEquals(beforeA, aCharge.get());
        assertEquals(beforeB, bCharge.get());
        assertEquals(1, bm.grow(1));
        assertEquals(2, bm.pages());
        assertEquals(71L, am.readLong(0));
        assertEquals(83L, bm.readLong(0));
        a.retire();
        b.retire();
        a.close();
        assertEquals(83L, bm.readLong(0));
        b.close();
        assertEquals(0L, aCharge.get());
        assertEquals(0L, bCharge.get());
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
        a.retire();
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
            a.retire();
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
            a.retire();
        }
        assertEquals(0, charged.get());
    }

    @Test
    void crossActorRegionAuthorityCannotBeStoredInAnotherArena() {
        AtomicLong leftCharge = new AtomicLong();
        AtomicLong rightCharge = new AtomicLong();
        AtomicBoolean owner = new AtomicBoolean(true);
        Object uninitialized = new Object();
        try (ActorNativeArena left = arena(256 * 1024, leftCharge, owner);
             ActorNativeArena right = arena(256 * 1024, rightCharge, owner)) {
            ActorNativeArena.Region leftRegion = left.region(32);
            Map<String, Object> rightFields = right.fields(List.of("foreign"), uninitialized);
            assertThrows(SecurityException.class, () -> rightFields.put("foreign", leftRegion));
            assertSame(uninitialized, rightFields.get("foreign"));
            leftRegion.writeLong(0, 314L);
            assertEquals(314L, leftRegion.readLong(0));

            // Both actors have guest-linear offset zero, but their physical pages,
            // field authority, and read-only leases must remain actor-private.
            ActorLinearMemory leftMemory = left.linearMemory(1, 1);
            ActorLinearMemory rightMemory = right.linearMemory(1, 1);
            leftMemory.writeLong(0, 111L);
            rightMemory.writeLong(0, 222L);
            assertEquals(111L, leftMemory.readLong(0));
            assertEquals(222L, rightMemory.readLong(0));
            try (var leftLoan = leftMemory.leaseReadOnlyPage(0, 0, Long.BYTES)) {
                assertThrows(IllegalStateException.class,
                        () -> leftMemory.writeLong(0, 333L));
                rightMemory.writeLong(0, 223L);
                assertEquals(111L, leftMemory.readLong(0));
                assertEquals(223L, rightMemory.readLong(0));
            }
            leftMemory.writeLong(0, 112L);
            assertEquals(112L, leftMemory.readLong(0));
            assertEquals(223L, rightMemory.readLong(0));
            left.retire();
            right.retire();
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
        a.retire();
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
            a.retire();
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
            a.retire();
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
            a.retire();
        }
        assertEquals(0, charged.get());
    }
}

