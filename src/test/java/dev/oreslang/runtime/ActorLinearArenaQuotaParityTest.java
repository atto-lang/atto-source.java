package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Host-level lifecycle regression; not compiled AttoLang or zero-GC evidence. */
final class ActorLinearArenaQuotaParityTest {
    @Test
    void rejectedAdmissionPreservesCapacityAndSiblingAcrossRetirement() {
        try (var first = new ActorLinearArena(64, 2);
             var sibling = new ActorLinearArena(64, 2)) {
            long root = first.allocate(56, 8);
            long peer = sibling.allocate(8);
            assertNotEquals(0L, root);
            assertNotEquals(0L, peer);
            assertTrue(first.putLong(root, 0, 17));
            assertTrue(sibling.putLong(peer, 0, 9));
            for (int i = 0; i < 8; i++) {
                assertEquals(0L, first.allocate(9, 8));
                assertEquals(56, first.usedBytes());
                assertEquals(1, first.liveAllocations());
            }
            long tail = first.allocate(8, 8);
            assertNotEquals(0L, tail);
            assertEquals(0L, first.getLong(tail, 0));
            assertEquals(0L, first.allocate(1, 1));
            assertEquals(64, first.usedBytes());
            assertEquals(17L, first.getLong(root, 0));
            first.close();
            assertEquals(9L, sibling.getLong(peer, 0));
            assertEquals(8, sibling.usedBytes());
            try (var restarted = new ActorLinearArena(64, 2)) {
                long fresh = restarted.allocate(64, 8);
                assertNotEquals(0L, fresh);
                assertEquals(0L, restarted.getLong(fresh, 0));
                assertThrows(IllegalStateException.class, () -> restarted.getLong(root, 0));
            }
        }
    }
}
