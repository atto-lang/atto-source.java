package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorLinearArenaTest {
    @Test
    void staleHandleNeverRevalidatesAcrossSlotReuse() {
        try (var arena = new ActorLinearArena(64, 1)) {
            long first = arena.allocate(8);
            assertTrue(arena.remove(first));
            long previous = first;
            for (int i = 0; i < 100_000; i++) {
                long next = arena.allocate(8);
                assertNotEquals(ActorLinearArena.INVALID_HANDLE, next);
                assertNotEquals(previous, next, "a reused slot must issue a new generation");
                assertFalse(arena.putLong(first, 0, 1L), "a stale handle must stay dead");
                assertTrue(arena.remove(next));
                previous = next;
            }
        }
    }

    @Test
    void primitiveHandlesStayActorLocalAndStaleHandlesNeverRevive() {
        try (var alice = new ActorLinearArena(256, 8);
             var bob = new ActorLinearArena(256, 8)) {
            long first = alice.allocate(24, 8);
            long foreign = bob.allocate(24, 8);
            assertNotEquals(ActorLinearArena.INVALID_HANDLE, first);
            assertNotEquals(ActorLinearArena.INVALID_HANDLE, foreign);
            assertTrue(alice.putLong(first, 0, 42));
            assertEquals(42, alice.getLong(first, 0));
            assertFalse(alice.putLong(foreign, 0, 7),
                    "foreign arena token must not authorize this arena");
            assertThrows(IllegalStateException.class, () -> alice.getLong(foreign, 0));

            assertTrue(alice.remove(first));
            assertFalse(alice.remove(first), "double drop must fail closed");
            long replacement = alice.allocate(24, 8);
            assertNotEquals(ActorLinearArena.INVALID_HANDLE, replacement);
            assertNotEquals(first, replacement, "slot reuse must mint a fresh token");
            assertThrows(IllegalStateException.class, () -> alice.getLong(first, 0));
            assertEquals(0, alice.getLong(replacement, 0),
                    "recycled guest memory must be zeroed");
        }
    }

    @Test
    void deterministicDropsRecycleLongLivedActorCapacity() {
        try (var arena = new ActorLinearArena(64, 2)) {
            for (int i = 0; i < 10_000; i++) {
                long handle = arena.allocate(16, 8);
                assertNotEquals(ActorLinearArena.INVALID_HANDLE, handle);
                assertTrue(arena.putLong(handle, 0, i));
                assertEquals(i, arena.getLong(handle, 0));
                assertTrue(arena.remove(handle));
                assertEquals(0, arena.liveAllocations());
                assertEquals(0, arena.usedBytes(),
                        "last owner drop should recycle the bump cursor");
            }
        }
    }

    @Test
    void capacityAlignmentAndCloseFailClosed() {
        var arena = new ActorLinearArena(64, 2);
        assertEquals(ActorLinearArena.INVALID_HANDLE, arena.allocate(0));
        assertEquals(ActorLinearArena.INVALID_HANDLE, arena.allocate(8, 3));
        assertEquals(ActorLinearArena.INVALID_HANDLE, arena.allocate(128, 8));
        long first = arena.allocate(16, 8);
        long second = arena.allocate(16, 8);
        assertNotEquals(0, first);
        assertNotEquals(0, second);
        assertEquals(0, arena.allocate(1, 1), "slot quota must be hard bounded");
        assertFalse(arena.putLong(first, 12, 9), "out of bounds write rejected");
        arena.close();
        arena.close();
        assertTrue(arena.closed());
        assertEquals(0, arena.liveAllocations());
        assertEquals(0, arena.usedBytes());
        assertFalse(arena.putLong(first, 0, 1));
        assertEquals(0, arena.allocate(8));
    }

    @Test
    void ownershipMarksAutoDropWithoutPerScopeJavaObjects() {
        try (var arena = new ActorLinearArena(256, 8)) {
            long outer = arena.cleanupMark();
            long first = arena.allocateOwned(16, 8);
            assertNotEquals(ActorLinearArena.INVALID_HANDLE, first);

            long inner = arena.cleanupMark();
            long transferred = arena.allocateOwned(16, 8);
            long temporary = arena.allocateOwned(16, 8);
            assertNotEquals(0, transferred);
            assertNotEquals(0, temporary);
            assertEquals(3, arena.liveAllocations());

            assertTrue(arena.releaseOwned(transferred),
                    "move out of inner scope disarms its cleanup");
            assertTrue(arena.cleanupTo(inner),
                    "inner scope auto-drops remaining lexical owners");
            assertEquals(2, arena.liveAllocations());
            assertThrows(IllegalStateException.class, () -> arena.getLong(temporary, 0));

            assertTrue(arena.adoptOwned(transferred),
                    "parent scope can adopt the transferred owner after inner cleanup");
            assertTrue(arena.dropOwned(first), "early last-owner drop is deterministic");
            assertEquals(1, arena.liveAllocations());
            assertTrue(arena.cleanupTo(outer),
                    "outer scope drops the adopted owner");
            assertEquals(0, arena.liveAllocations());
            assertEquals(0, arena.usedBytes());
        }
    }

    @Test
    void staleTrackedOwnerDoesNotSuppressLaterCleanup() {
        try (var arena = new ActorLinearArena(128, 4)) {
            long mark = arena.cleanupMark();
            long stale = arena.allocateOwned(16);
            long survivor = arena.allocateOwned(16);
            assertTrue(arena.remove(stale), "out-of-band removal fixture");
            assertFalse(arena.cleanupTo(mark),
                    "cleanup reports violated ownership tracking");
            assertEquals(0, arena.liveAllocations(),
                    "stale owner cannot suppress cleanup of surviving owners");
            assertThrows(IllegalStateException.class, () -> arena.getLong(survivor, 0));
        }
    }

    @Test
    void lexicalMarkSurvivesMoveOfAnOlderOwner() {
        try (var arena = new ActorLinearArena(256, 8)) {
            long outer = arena.cleanupMark();
            long persistent = arena.allocateOwned(16);
            long inner = arena.cleanupMark();
            long temporaryA = arena.allocateOwned(16);
            long temporaryB = arena.allocateOwned(16);

            assertTrue(arena.releaseOwned(persistent));
            assertTrue(arena.cleanupTo(inner));
            assertEquals(1, arena.liveAllocations());
            assertThrows(IllegalStateException.class, () -> arena.getLong(temporaryA, 0));
            assertThrows(IllegalStateException.class, () -> arena.getLong(temporaryB, 0));
            assertTrue(arena.adoptOwned(persistent));
            assertTrue(arena.cleanupTo(outer));
            assertEquals(0, arena.liveAllocations());
        }
    }

    @Test
    void lexicalMarkSurvivesRemovalOfAllEarlierOwners() {
        try (var arena = new ActorLinearArena(256, 8)) {
            long first = arena.allocateOwned(16);
            long second = arena.allocateOwned(16);
            long inner = arena.cleanupMark();
            assertTrue(arena.dropOwned(first));
            assertTrue(arena.releaseOwned(second));
            long child = arena.allocateOwned(16);
            assertTrue(arena.cleanupTo(inner));
            assertThrows(IllegalStateException.class, () -> arena.getLong(child, 0));
            assertEquals(1, arena.liveAllocations());
            assertTrue(arena.remove(second));
            assertEquals(0, arena.liveAllocations());
        }
    }

    @Test
    void movedOwnershipDoesNotEvadeOuterCleanup() {
        try (var arena = new ActorLinearArena(256, 8)) {
            long outer = arena.cleanupMark();
            long moved = arena.allocateOwned(16);
            long inner = arena.cleanupMark();
            assertTrue(arena.releaseOwned(moved));
            assertTrue(arena.adoptOwned(moved));
            assertTrue(arena.cleanupTo(inner),
                    "adopting after an inner mark belongs to that inner scope");
            assertThrows(IllegalStateException.class, () -> arena.getLong(moved, 0));
            assertTrue(arena.cleanupTo(outer));
            assertEquals(0, arena.liveAllocations());
        }
    }

    @Test
    void ownershipTrackingCanReleaseOutOfOrderWithoutMovingOtherCheckpoints() {
        try (var arena = new ActorLinearArena(2048, 64)) {
            long mark = arena.cleanupMark();
            long[] owners = new long[63];
            for (int i = 0; i < owners.length; i++) {
                owners[i] = arena.allocateOwned(16);
                assertNotEquals(0L, owners[i]);
            }
            long nested = arena.cleanupMark();
            for (int i = 0; i < owners.length; i += 2)
                assertTrue(arena.releaseOwned(owners[i]));
            long child = arena.allocateOwned(16);
            assertNotEquals(0L, child);
            assertTrue(arena.cleanupTo(nested));
            assertThrows(IllegalStateException.class, () -> arena.getLong(child, 0));
            assertEquals(63, arena.liveAllocations());
            assertTrue(arena.cleanupTo(mark));
            assertEquals(32, arena.liveAllocations());
            for (int i = 0; i < owners.length; i += 2)
                assertTrue(arena.remove(owners[i]));
            assertEquals(0, arena.liveAllocations());
        }
    }

    @Test
    void staleTrackedSlotReuseCannotHideAnOutOfBandRemoval() {
        try (var arena = new ActorLinearArena(128, 3)) {
            long mark = arena.cleanupMark();
            long stale = arena.allocateOwned(16);
            assertTrue(arena.remove(stale));
            long replacement = arena.allocateOwned(16);
            assertNotEquals(0L, replacement);
            assertNotEquals(stale, replacement);
            assertFalse(arena.releaseOwned(stale));
            assertFalse(arena.cleanupTo(mark),
                    "invalidated older owner must be reported even after slot reuse");
            assertEquals(0, arena.liveAllocations());
            assertThrows(IllegalStateException.class, () -> arena.getLong(replacement, 0));
        }
    }

    @Test
    void releasedOwnerNodesAreReusedWithoutGrowingMetadata() {
        try (var arena = new ActorLinearArena(64, 2)) {
            for (int round = 0; round < 20_000; round++) {
                long mark = arena.cleanupMark();
                long value = arena.allocateOwned(16);
                assertNotEquals(0L, value);
                if ((round & 1) == 0) assertTrue(arena.dropOwned(value));
                else assertTrue(arena.cleanupTo(mark));
                assertEquals(0, arena.liveAllocations());
                assertEquals(0, arena.usedBytes());
            }
        }
    }

    @Test
    void persistentActorFieldDoesNotExhaustArenaDuringNestedTurnChurn() {
        try (var arena = new ActorLinearArena(64, 4)) {
            long persistent = arena.allocateOwned(16);
            assertNotEquals(0L, persistent);
            assertTrue(arena.putLong(persistent, 0, 99));
            int baseline = arena.usedBytes();

            for (int i = 0; i < 20_000; i++) {
                long mark = arena.cleanupMark();
                long scratch = arena.allocateOwned(16, 8);
                assertNotEquals(0L, scratch, "turn scratch must recycle despite a live actor field");
                assertTrue(arena.putLong(scratch, 0, i));
                assertEquals(i, arena.getLong(scratch, 0));
                assertTrue(arena.cleanupTo(mark));
                assertEquals(baseline, arena.usedBytes(),
                        "last-in-first-out scratch reclamation returns to stable cursor");
                assertEquals(99, arena.getLong(persistent, 0));
            }
            assertTrue(arena.dropOwned(persistent));
            assertEquals(0, arena.liveAllocations());
            assertEquals(0, arena.usedBytes());
        }
    }

    @Test
    void nonTopmostDropNeverReusesStorageOfLiveSuccessor() {
        try (var arena = new ActorLinearArena(64, 4)) {
            long persistent = arena.allocate(16);
            long early = arena.allocate(16);
            long successor = arena.allocate(16);
            assertTrue(arena.putLong(successor, 0, 1234L));
            assertTrue(arena.remove(early));
            assertEquals(48, arena.usedBytes(),
                    "dropping a non-top block must not rewind cursor across live data");
            assertEquals(1234L, arena.getLong(successor, 0));
            assertTrue(arena.remove(successor));
            assertEquals(32, arena.usedBytes(),
                    "only the topmost reservation may be reclaimed");
            assertEquals(0L, arena.getLong(persistent, 0));
            assertTrue(arena.remove(persistent));
            assertEquals(0, arena.usedBytes());
        }
    }

    @Test
    void checkpointCounterCrossesIntegerBoundaryWithoutAliasing() throws Exception {
        try (var arena = new ActorLinearArena(128, 4)) {
            var serial = ActorLinearArena.class.getDeclaredField("lastCleanupSerial");
            serial.setAccessible(true);
            serial.setLong(arena, (long) Integer.MAX_VALUE - 1L);
            long parent = arena.cleanupMark();
            long first = arena.allocateOwned(16);
            long child = arena.cleanupMark();
            long second = arena.allocateOwned(16);
            assertEquals((long) Integer.MAX_VALUE, child);
            assertNotEquals(0L, second);
            assertEquals((long) Integer.MAX_VALUE + 1L, arena.cleanupMark());
            assertTrue(arena.cleanupTo(child));
            assertThrows(IllegalStateException.class, () -> arena.getLong(second, 0));
            assertEquals(1, arena.liveAllocations());
            assertTrue(arena.cleanupTo(parent));
            assertThrows(IllegalStateException.class, () -> arena.getLong(first, 0));
        }
    }

    @Test
    void ownershipSequenceExhaustionFailsClosed() throws Exception {
        try (var arena = new ActorLinearArena(64, 2)) {
            var serial = ActorLinearArena.class.getDeclaredField("lastCleanupSerial");
            serial.setAccessible(true);
            serial.setLong(arena, Long.MAX_VALUE);
            assertEquals(ActorLinearArena.INVALID_HANDLE, arena.allocateOwned(16));
            long raw = arena.allocate(16);
            assertNotEquals(0L, raw);
            assertFalse(arena.adoptOwned(raw));
            assertTrue(arena.remove(raw));
            assertEquals(0, arena.liveAllocations());
        }
    }

}
