package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class SupervisorCodeSlotTest {
    @Test void stableLeaseAndNoPrematureRetirementOnUpgrade() {
        AtomicInteger freed = new AtomicInteger();
        try (var slot = new SupervisorCodeSlot<>("v1", "Daddy-v1", freed::incrementAndGet, 3)) {
            var old = slot.acquire();
            assertEquals("Daddy-v1", old.code());
            var activated = slot.activate(1, "v1", "Daddy-v2", freed::incrementAndGet);
            assertEquals(2L, activated.revision());
            assertEquals(0, freed.get(), "old active policy turn still pins code");
            try (var fresh = slot.acquire()) {
                assertEquals("Daddy-v2", fresh.code());
                assertEquals(2L, fresh.descriptor().revision());
            }
            assertEquals("Daddy-v1", old.code(), "upgrade rewrote a pinned frame");
            old.close();
            old.close();
            assertEquals(1, freed.get(), "retired revision must clean exactly once");
            assertEquals(1, slot.liveRevisions());
        }
        assertEquals(2, freed.get());
    }

    @Test void failClosedOnAbiMismatchAndStaleUpgrade() {
        AtomicInteger freed = new AtomicInteger();
        try (var slot = new SupervisorCodeSlot<>("abi-v1", "old", freed::incrementAndGet, 2)) {
            assertThrows(IllegalArgumentException.class,
                    () -> slot.activate(1, "abi-v2", "bad", freed::incrementAndGet));
            assertThrows(IllegalStateException.class,
                    () -> slot.activate(2, "abi-v1", "stale", freed::incrementAndGet));
            assertEquals(1, slot.current().revision());
            assertEquals(0, freed.get());
        }
    }

    @Test void boundedPinnedOldPolicyPreventsUnboundedHotReloadRetention() {
        AtomicInteger freed = new AtomicInteger();
        try (var slot = new SupervisorCodeSlot<>("a", "one", freed::incrementAndGet, 2)) {
            var old = slot.acquire();
            slot.activate(1, "a", "two", freed::incrementAndGet);
            try (var current = slot.acquire()) {
                assertThrows(IllegalStateException.class,
                        () -> slot.activate(2, "a", "three", freed::incrementAndGet));
                assertEquals(2, slot.liveRevisions());
            }
            old.close();
            assertEquals(1, freed.get());
            slot.activate(2, "a", "three", freed::incrementAndGet);
            assertEquals(2, freed.get());
        }
        assertEquals(3, freed.get());
    }

    @Test void closeFencesNewAcquisitionsButWaitsForPinnedCleanup() {
        AtomicInteger freed = new AtomicInteger();
        var slot = new SupervisorCodeSlot<>("v1", "running", freed::incrementAndGet, 2);
        var pinned = slot.acquire();
        slot.close();
        assertEquals(0, freed.get());
        assertThrows(IllegalStateException.class, slot::acquire);
        pinned.close();
        assertEquals(1, freed.get());
        slot.close();
        assertEquals(1, freed.get());
    }
}
