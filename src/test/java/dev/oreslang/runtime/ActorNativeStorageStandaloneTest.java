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
