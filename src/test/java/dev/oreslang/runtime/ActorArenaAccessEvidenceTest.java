package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-only proof of observed arena accesses, not complete guest race coverage.
 */
final class ActorArenaAccessEvidenceTest {
    @Test
    void recordsSuccessfulNativeAccessesWithActorRelativeOffsetsAndStickyOverflow() {
        AtomicLong charged = new AtomicLong();
        ActorNativeArena arena = new ActorNativeArena(4096, () -> { }, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        });
        try (arena) {
            assertThrows(IllegalStateException.class, arena::accessEvidenceSnapshot);
            assertThrows(IllegalArgumentException.class, () -> arena.enableAccessEvidence(0));
            arena.enableAccessEvidence(6);
            assertThrows(IllegalStateException.class, () -> arena.enableAccessEvidence(6));
            var region = arena.region(64);
            long base = region.nativeOffset();
            region.writeLong(8, 9);
            assertEquals(9L, region.readLong(8));
            var lease = region.leaseReadOnly(8, 8);
            assertEquals(9L, lease.readLong(0));
            assertThrows(IllegalStateException.class, () -> region.writeLong(8, 17));
            lease.close();
            region.writeByte(0, (byte) 7);
            assertEquals((byte) 7, region.readByte(0));
            assertThrows(IndexOutOfBoundsException.class, () -> region.readLong(61));

            var snapshot = arena.accessEvidenceSnapshot();
            assertTrue(snapshot.completeBuffer());
            assertEquals(6, snapshot.capacity());
            assertEquals(5, snapshot.accesses().size());
            assertEquals(0, snapshot.dropped());
            var accesses = snapshot.accesses();
            assertEquals(new ActorArenaAccessEvidence.Access(base + 8, 8, true), accesses.get(0));
            assertEquals(new ActorArenaAccessEvidence.Access(base + 8, 8, false), accesses.get(1));
            assertEquals(new ActorArenaAccessEvidence.Access(base + 8, 8, false), accesses.get(2));
            assertEquals(new ActorArenaAccessEvidence.Access(base, 1, true), accesses.get(3));
            assertEquals(new ActorArenaAccessEvidence.Access(base, 1, false), accesses.get(4));
            assertThrows(UnsupportedOperationException.class, () ->
                    accesses.add(new ActorArenaAccessEvidence.Access(base, 8, false)));

            region.writeDouble(16, 2.5);
            assertEquals(2.5, region.readDouble(16));
            var overflow = arena.accessEvidenceSnapshot();
            assertFalse(overflow.completeBuffer());
            assertEquals(6, overflow.accesses().size());
            assertEquals(1, overflow.dropped());
            assertThrows(IllegalStateException.class, () -> arena.enableAccessEvidence(1));
        }
        assertEquals(0, charged.get());
        assertThrows(IllegalStateException.class, arena::accessEvidenceSnapshot);
    }

    @Test
    void multipleArenaIdentitiesDoNotOverlapAndInvalidAccessesAreNotLogged() {
        var first = new ActorArenaAccessEvidence(2);
        var second = new ActorArenaAccessEvidence(2);
        first.record(0, 1, true);
        first.record(8, 8, false);
        second.record(0, 1, false);
        first.record(16, 8, true);
        assertEquals(2, first.snapshot().accesses().size());
        assertEquals(1, first.snapshot().dropped());
        assertEquals(1, second.snapshot().accesses().size());
        assertEquals(0, second.snapshot().dropped());
        assertThrows(IllegalArgumentException.class, () -> first.record(-1, 8, true));
        assertThrows(IllegalArgumentException.class, () -> second.record(0, 0, true));
        first.clear();
        assertEquals(0, first.snapshot().accesses().size());
        assertEquals(0, first.snapshot().dropped());
    }
}
