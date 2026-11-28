package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Verify native linear memory belongs to one logical actor, never a carrier. */
@Timeout(45)
final class ActorLinearMemoryTest {
    private static ActorNativeArena arena(long maxBytes, AtomicLong charged) {
        return new ActorNativeArena(maxBytes, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        });
    }

    @Test
    void compactActorAddressesFollowCanonicalOffsetNotGuestPageNumber() {
        AtomicLong charged = new AtomicLong();
        int page = ActorLinearMemory.PAGE_BYTES;
        try (ActorNativeArena arena = arena(3L * page + 8192, charged)) {
            ActorLinearMemory linear = arena.linearMemory(1, 2);
            long first = linear.actorOffset(0);
            int firstWord = ActorCompressed32Reference.encode(first);
            assertEquals(first, ActorCompressed32Reference.decode(firstWord));
            // Allocate a separate slab in the same actor address authority
            // between two linear pages: page-number * PAGE_BYTES is not the
            // canonical actor address.
            arena.fields(java.util.List.of("value"), new Object()).put("value", 17L);
            assertEquals(1, linear.grow(1));
            long second = linear.actorOffset(page);
            int secondWord = ActorCompressed32Reference.encode(second);
            assertTrue(second > first + page,
                    "slab and linear pages need not be adjacent in actor-relative space");
            assertEquals(second, ActorCompressed32Reference.decode(secondWord));
            linear.writeLong(page, 123L);
            assertEquals(123L, linear.readLong(page));
            assertNotEquals(firstWord, secondWord);
        }
        assertEquals(0, charged.get());
    }


    @Test
    void growthIsAtomicBoundedZeroFilledAndPreservesOffsets() {
        AtomicLong charged = new AtomicLong();
        int page = ActorLinearMemory.PAGE_BYTES;
        try (ActorNativeArena arena = arena(2L * page, charged)) {
            assertThrows(IllegalArgumentException.class, () -> arena.linearMemory(1, -1));
            assertThrows(IllegalArgumentException.class,
                    () -> arena.linearMemory(0, ActorLinearMemory.MAX_PAGES + 1));
            ActorLinearMemory memory = arena.linearMemory(1, 3);
            assertSame(memory, arena.linearMemory(1, 3));
            assertThrows(IllegalArgumentException.class, () -> arena.linearMemory(-1, 3));
            assertThrows(IllegalArgumentException.class, () -> arena.linearMemory(4, 3));
            assertThrows(IllegalArgumentException.class, () -> arena.linearMemory(1, -1));
            assertThrows(IllegalArgumentException.class,
                    () -> arena.linearMemory(1, ActorLinearMemory.MAX_PAGES + 1));
            assertThrows(IllegalArgumentException.class, () -> arena.linearMemory(1, 2));
            assertEquals(1, memory.pages());
            assertEquals(3, memory.maxPages());
            assertEquals(page, memory.byteLength());
            assertEquals(0, memory.readUnsignedByte(0));
            memory.writeByte(0, (byte) 0xe7);
            memory.writeLong(page - 8L, 0x0102030405060708L);
            assertEquals(0xe7, memory.readUnsignedByte(0));
            assertEquals(0x0102030405060708L, memory.readLong(page - 8L));

            // Only one more page fits this actor budget: the second page of
            // the attempted two-page grow must roll back the first one.
            assertThrows(IllegalArgumentException.class, () -> memory.grow(2));
            assertEquals(1, memory.pages());
            assertEquals((long) page, charged.get());
            assertEquals(1, memory.grow(1));
            assertEquals(2L * page, memory.byteLength());
            assertEquals(0, memory.readUnsignedByte(page));
            assertEquals(0, memory.readUnsignedByte(2L * page - 1));
            assertEquals(0x0102030405060708L, memory.readLong(page - 8L));
            assertEquals(2, memory.grow(0));
            assertThrows(IllegalArgumentException.class, () -> memory.grow(-1));
            assertThrows(IllegalArgumentException.class, () -> memory.grow(2));
            assertThrows(IndexOutOfBoundsException.class, () -> memory.readByte(-1));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> memory.readLong(2L * page - 7));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> memory.writeLong(Long.MAX_VALUE, 1));
        }
        assertEquals(0, charged.get(), "actor arena close refunds all native page allocations");
    }

    @Test
    void spanningWordNeverHalfWritesWhenOnePageIsBorrowed() {
        AtomicLong charged = new AtomicLong();
        int page = ActorLinearMemory.PAGE_BYTES;
        try (ActorNativeArena arena = arena(2L * page, charged)) {
            ActorLinearMemory memory = arena.linearMemory(2, 2);
            memory.writeLong(page - 4L, 0x1020304050607080L);
            assertEquals(0x1020304050607080L, memory.readLong(page - 4L));
            try (var lease = memory.leaseReadOnlyPage(1, 0, 8)) {
                assertEquals(8, lease.length());
                assertEquals(0x10203040L, Integer.toUnsignedLong(
                        (int) (memory.readLong(page - 4L) >>> 32)));
                assertThrows(IllegalStateException.class,
                        () -> memory.writeLong(page - 4L, 0x1122334455667788L));
                assertEquals(0x1020304050607080L, memory.readLong(page - 4L),
                        "cross-page write must not partially mutate first page");
                assertThrows(IllegalStateException.class,
                        () -> memory.writeByte(page, (byte) 1));
                assertEquals(2, memory.grow(0));
            }
            memory.writeLong(page - 4L, 0x1122334455667788L);
            assertEquals(0x1122334455667788L, memory.readLong(page - 4L));
        }
        assertEquals(0, charged.get());
    }

    @Test
    void primitiveWordAccessMatchesLittleEndianOracleAcrossAllPageEdges() {
        AtomicLong charged = new AtomicLong();
        long base = ActorLinearMemory.PAGE_BYTES;
        try (ActorNativeArena arena = arena(3 * base, charged)) {
            ActorLinearMemory memory = arena.linearMemory(3, 3);
            long[] patterns = {
                    0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE,
                    0x0123456789abcdefL, 0xfedcba9876543210L
            };
            for (int edge = -7; edge <= 7; edge++) {
                long offset = base + edge;
                for (long pattern : patterns) {
                    memory.writeLong(offset, pattern);
                    assertEquals(pattern, memory.readLong(offset));
                    for (int byteIndex = 0; byteIndex < Long.BYTES; byteIndex++) {
                        assertEquals((int) ((pattern >>> (byteIndex * 8)) & 0xff),
                                memory.readUnsignedByte(offset + byteIndex),
                                "little-endian byte at edge " + edge + ", index " + byteIndex);
                    }
                }
            }
            assertThrows(IndexOutOfBoundsException.class,
                    () -> memory.writeLong(memory.byteLength() - 7L, 1L));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> memory.readLong(Long.MIN_VALUE));
        }
        assertEquals(0, charged.get());
    }

    @Test
    void bulkCopyAndFillUseMemmoveAndNeverPartiallyWriteBorrowedPages() {
        AtomicLong charged = new AtomicLong();
        long edge = ActorLinearMemory.PAGE_BYTES;
        try (ActorNativeArena arena = arena(2L * edge, charged)) {
            ActorLinearMemory memory = arena.linearMemory(2, 2);
            byte[] oracle = new byte[40];
            for (int i = 0; i < oracle.length; i++) {
                oracle[i] = (byte) (i + 1);
                memory.writeByte(edge - 16 + i, oracle[i]);
            }
            memory.copy(edge - 8, edge - 16, 24);
            System.arraycopy(oracle, 0, oracle, 8, 24);
            for (int i = 0; i < oracle.length; i++) {
                assertEquals(oracle[i], memory.readByte(edge - 16 + i));
            }
            memory.copy(edge - 16, edge - 8, 24);
            System.arraycopy(oracle, 8, oracle, 0, 24);
            for (int i = 0; i < oracle.length; i++) {
                assertEquals(oracle[i], memory.readByte(edge - 16 + i));
            }
            try (var borrowed = memory.leaseReadOnlyPage(1, 0, 8)) {
                byte[] before = new byte[16];
                for (int i = 0; i < before.length; i++) {
                    before[i] = memory.readByte(edge - 8 + i);
                }
                assertThrows(IllegalStateException.class,
                        () -> memory.fill(edge - 8, 16, (byte) 0x5a));
                assertThrows(IllegalStateException.class,
                        () -> memory.copy(edge - 8, edge + 8, 16));
                for (int i = 0; i < before.length; i++) {
                    assertEquals(before[i], memory.readByte(edge - 8 + i),
                            "an immutable page cannot cause partial writes on the prior page");
                }
                // Read leases guard writes, not zero-copy reads from the page.
                memory.copy(0, edge, 8);
                for (int i = 0; i < 8; i++) {
                    assertEquals(before[8 + i], memory.readByte(i));
                }
            }
            memory.fill(edge - 8, 16, (byte) 0x5a);
            for (int i = 0; i < 16; i++) {
                assertEquals((byte) 0x5a, memory.readByte(edge - 8 + i));
            }
            memory.copy(memory.byteLength(), memory.byteLength(), 0);
            memory.fill(memory.byteLength(), 0, (byte) 0);
            assertThrows(IndexOutOfBoundsException.class,
                    () -> memory.copy(edge, -1, 1));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> memory.copy(memory.byteLength() - 1, 0, 2));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> memory.fill(0, -1, (byte) 0));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> memory.fill(Long.MAX_VALUE, 1, (byte) 0));
        }
        assertEquals(0, charged.get());
    }

    @Test
    void deterministicBulkOperationsMatchArrayOracleAcrossNativePageBoundaries() {
        AtomicLong charged = new AtomicLong();
        final int size = ActorLinearMemory.PAGE_BYTES * 2;
        final byte[] expected = new byte[size];
        final Random random = new Random(0x0a72e41L);
        try (ActorNativeArena arena = arena(size, charged)) {
            ActorLinearMemory memory = arena.linearMemory(2, 2);
            for (int turn = 0; turn < 600; turn++) {
                int length = random.nextInt(320);
                int source = random.nextInt(size - length + 1);
                int target = random.nextInt(size - length + 1);
                // Periodically force both directions of overlap and page
                // crossing, rather than relying on uniform chance alone.
                if (turn % 7 == 0) {
                    source = ActorLinearMemory.PAGE_BYTES - 80;
                    target = source + 13;
                    length = 180;
                } else if (turn % 11 == 0) {
                    target = ActorLinearMemory.PAGE_BYTES - 80;
                    source = target + 13;
                    length = 180;
                }
                if (turn % 3 == 0) {
                    byte value = (byte) random.nextInt();
                    memory.fill(target, length, value);
                    java.util.Arrays.fill(expected, target, target + length, value);
                } else {
                    memory.copy(target, source, length);
                    System.arraycopy(expected, source, expected, target, length);
                }
                // Always check the exact destination and its immediate
                // boundaries, plus sampled non-destination actor memory.
                for (int at = Math.max(0, target - 1);
                        at <= Math.min(size - 1, target + length); at++) {
                    assertEquals(expected[at], memory.readByte(at),
                            "turn " + turn + " offset " + at);
                }
                for (int j = 0; j < 24; j++) {
                    int at = random.nextInt(size);
                    assertEquals(expected[at], memory.readByte(at),
                            "unmodified memory turn " + turn + " offset " + at);
                }
            }
            for (int at = 0; at < size; at += 41) {
                assertEquals(expected[at], memory.readByte(at));
            }
        }
        assertEquals(0, charged.get(), "native bytes refunded after fuzz regression");
    }

    @Test
    void canonicalArenaAddressesAreProvenanceNotLinearPointerArithmetic() {
        AtomicLong charged = new AtomicLong();
        int page = ActorLinearMemory.PAGE_BYTES;
        try (ActorNativeArena arena = arena(3L * page, charged)) {
            ActorLinearMemory memory = arena.linearMemory(1, 2);
            long firstPageBase = memory.actorOffset(0);

            // Interleave an ordinary actor-native allocation before a later
            // linear-memory grow. The guest address space remains contiguous,
            // while canonical arena identity addresses need not be.
            var interleaved = arena.region(64);
            long interleavedBase = interleaved.nativeOffset();
            assertTrue(interleavedBase >= firstPageBase + page);

            assertEquals(1, memory.grow(1));
            long secondPageBase = memory.actorOffset(page);
            assertNotEquals(firstPageBase + page, secondPageBase,
                    "canonical arena offsets must not be treated as linear pointer arithmetic");

            memory.writeLong(page - 4L, 0x0102030405060708L);
            assertEquals(0x0102030405060708L, memory.readLong(page - 4L),
                    "guest linear offsets remain contiguous across the page boundary");
        }
        assertEquals(0, charged.get());
    }

    @Test
    void failedInitialCommitDoesNotPublishOrChargeLinearMemory() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = arena(ActorLinearMemory.PAGE_BYTES - 1L, charged)) {
            assertThrows(IllegalArgumentException.class, () -> arena.linearMemory(1, 1));
            assertEquals(0, charged.get());
            ActorLinearMemory memory = arena.linearMemory(0, 1);
            assertEquals(0, memory.pages());
            assertThrows(IllegalArgumentException.class, () -> memory.grow(1));
            assertEquals(0, memory.pages());
            assertEquals(0, charged.get());
        }
    }

    @Test
    void everyActorKindGetsDistinctPersistentNativeLinearMemory() throws Exception {
        for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
            try (ActorRuntime runtime = new ActorRuntime()) {
                CountDownLatch initialized = new CountDownLatch(2);
                CountDownLatch verified = new CountDownLatch(2);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                AtomicReference<ActorLinearMemory> left = new AtomicReference<>();
                AtomicReference<ActorLinearMemory> right = new AtomicReference<>();
                var a = runtime.<Integer>spawn(kind, runtime.policyCeiling(),
                        () -> (message, turn) -> {
                            try {
                                ActorLinearMemory memory = ActorRuntime.currentActorLinearMemory(1, 2);
                                if (message == 1) {
                                    left.set(memory);
                                    memory.writeLong(0, 111L);
                                    initialized.countDown();
                                } else {
                                    assertSame(left.get(), memory);
                                    assertEquals(111L, memory.readLong(0));
                                    assertNotSame(right.get(), memory);
                                    assertThrows(SecurityException.class, () -> right.get().readLong(0));
                                    verified.countDown();
                                }
                            } catch (Throwable problem) {
                                failure.compareAndSet(null, problem);
                                initialized.countDown();
                                verified.countDown();
                            }
                        });
                var b = runtime.<Integer>spawn(kind, runtime.policyCeiling(),
                        () -> (message, turn) -> {
                            try {
                                ActorLinearMemory memory = ActorRuntime.currentActorLinearMemory(1, 2);
                                if (message == 1) {
                                    right.set(memory);
                                    memory.writeLong(0, 222L);
                                    initialized.countDown();
                                } else {
                                    assertSame(right.get(), memory);
                                    assertEquals(222L, memory.readLong(0));
                                    assertNotSame(left.get(), memory);
                                    assertThrows(SecurityException.class, () -> left.get().readLong(0));
                                    verified.countDown();
                                }
                            } catch (Throwable problem) {
                                failure.compareAndSet(null, problem);
                                initialized.countDown();
                                verified.countDown();
                            }
                        });
                a.send(1);
                b.send(1);
                assertTrue(initialized.await(5, TimeUnit.SECONDS));
                assertNull(failure.get());
                assertNotSame(left.get(), right.get());
                assertThrows(SecurityException.class, () -> left.get().readLong(0));
                assertThrows(SecurityException.class, () -> right.get().readLong(0));
                a.send(2);
                b.send(2);
                assertTrue(verified.await(5, TimeUnit.SECONDS));
                assertNull(failure.get());
                a.stop();
                b.stop();
                assertTrue(a.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(b.awaitTermination(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> left.get().readByte(0));
                assertThrows(IllegalStateException.class, () -> right.get().readByte(0));
                assertEquals(0, runtime.actorMemoryBytes());
            }
        }
        assertThrows(SecurityException.class, () -> ActorRuntime.currentActorLinearMemory(0, 1));
    }
}
