package dev.oreslang;

import dev.oreslang.runtime.FixedBufferPoolV1;
import dev.oreslang.runtime.FixedBufferPoolV1.Owner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** HOST-ONLY: neither .atto compiler/guest nor native-image performance receipt. */
@Timeout(20)
final class FixedBufferPoolV1Test {
    private static final Owner SENDER = new Owner(1, 10, 1);
    private static final Owner RECEIVER = new Owner(1, 11, 2);
    private static final Owner FOREIGN = new Owner(2, 12, 1);

    @Test
    void uniqueBorrowExhaustionAndGenerationFencing() {
        try (var pool = new FixedBufferPoolV1(1, 16)) {
            var lease = pool.try_acquire(SENDER);
            assertNotNull(lease);
            assertNull(pool.try_acquire(SENDER), "exhaustion must not grow the pool");
            try (var writer = pool.borrow_write(lease, SENDER)) {
                pool.write_byte(writer, 0, (byte) 0x5a);
                assertEquals((byte) 0x5a, pool.read_byte(writer, 0));
                assertThrows(IllegalStateException.class,
                        () -> pool.borrow_read(lease, SENDER));
                assertThrows(IllegalStateException.class,
                        () -> pool.release(lease, SENDER));
                assertThrows(IllegalStateException.class,
                        () -> pool.begin_transfer(lease, SENDER));
            }
            try (var reader = pool.borrow_read(lease, SENDER)) {
                assertEquals((byte) 0x5a, pool.read_byte(reader, 0));
                assertThrows(IllegalStateException.class,
                        () -> pool.borrow_write(lease, SENDER));
            }
            assertThrows(IllegalStateException.class,
                    () -> pool.borrow_read(lease, new Owner(1, 10, 2)));
            pool.release(lease, SENDER);
            assertThrows(IllegalStateException.class, () -> pool.release(lease, SENDER));
            var reused = pool.try_acquire(FOREIGN);
            assertNotNull(reused);
            try (var reader = pool.borrow_read(reused, FOREIGN)) {
                assertEquals((byte) 0, pool.read_byte(reader, 0),
                        "scrub must precede cross-tenant reuse");
            }
            assertThrows(IllegalStateException.class,
                    () -> pool.borrow_read(lease, SENDER),
                    "old generation/lease must never access reused slot");
            pool.release(reused, FOREIGN);
        }
    }

    @Test
    void escrowAbortReturnsSameLeaseCommitKillsOriginalAndDeniesCrossTenant() {
        try (var pool = new FixedBufferPoolV1(1, 8)) {
            var lease = pool.try_acquire(SENDER);
            try (var writer = pool.borrow_write(lease, SENDER)) {
                pool.write_byte(writer, 0, (byte) 19);
            }
            var aborted = pool.begin_transfer(lease, SENDER);
            assertThrows(IllegalStateException.class,
                    () -> pool.borrow_read(lease, SENDER));
            assertThrows(IllegalStateException.class,
                    () -> pool.begin_transfer(lease, SENDER));
            assertThrows(IllegalStateException.class,
                    () -> pool.commit_transfer(aborted, FOREIGN));
            assertSame(lease, pool.abort_transfer(aborted));
            assertThrows(IllegalStateException.class, () -> pool.abort_transfer(aborted));
            var pending = pool.begin_transfer(lease, SENDER);
            var moved = pool.commit_transfer(pending, RECEIVER);
            assertNotSame(lease, moved, "committed transfer must mint a new capability");
            assertThrows(IllegalStateException.class,
                    () -> pool.borrow_read(lease, SENDER));
            assertThrows(IllegalStateException.class,
                    () -> pool.commit_transfer(pending, RECEIVER));
            try (var reader = pool.borrow_read(moved, RECEIVER)) {
                assertEquals((byte) 19, pool.read_byte(reader, 0));
            }
            pool.release(moved, RECEIVER);
            assertEquals(0, pool.in_use());
        }
    }

    @Test
    void retirementDoesNotRecycleOutstandingLease() {
        try (var pool = new FixedBufferPoolV1(1, 8)) {
            var held = pool.try_acquire(SENDER);
            try (var loan = pool.borrow_read(held, SENDER)) {
                pool.retire();
                assertNull(pool.try_acquire(SENDER));
                assertFalse(pool.is_quiescent());
                assertThrows(IllegalStateException.class,
                        () -> pool.borrow_write(held, SENDER));
            }
            pool.release(held, SENDER);
            assertTrue(pool.is_quiescent());
        }
    }

    @Test
    void hundredThousandHostMovesPreservePayloadAndBoundedOccupancy() {
        try (var pool = new FixedBufferPoolV1(2, 16)) {
            for (int i = 0; i < 100_000; ++i) {
                var lease = pool.try_acquire(SENDER);
                assertNotNull(lease);
                try (var writer = pool.borrow_write(lease, SENDER)) {
                    pool.write_byte(writer, 0, (byte) i);
                }
                var pending = pool.begin_transfer(lease, SENDER);
                var moved = pool.commit_transfer(pending, RECEIVER);
                try (var reader = pool.borrow_read(moved, RECEIVER)) {
                    assertEquals((byte) i, pool.read_byte(reader, 0));
                }
                pool.release(moved, RECEIVER);
            }
            assertEquals(0, pool.in_use());
        }
    }
}
