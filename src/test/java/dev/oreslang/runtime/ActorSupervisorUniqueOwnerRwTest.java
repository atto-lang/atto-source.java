package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A supervisor is not a second owner of its child's sealed actor heap.
 * This tests runtime loan isolation, not a new shared-memory guest API.
 */
@Timeout(30)
final class ActorSupervisorUniqueOwnerRwTest {
    private static ExecutionContext actor(
            long logicalExecution, long slot, long actorOwner, long incarnation) {
        return ExecutionContext.actorBound(
                7001, logicalExecution, slot, incarnation,
                9001, actorOwner, incarnation, 0, 0);
    }

    @Test
    void supervisorCannotReadOrWriteChildRegionEvenWithoutContention() {
        var child = actor(1, 11, 42, 7);
        var supervisor = actor(2, 12, 41, 7);
        try (var childRegion = new ActorStateRwLoans(4)) {
            var childWriter = childRegion.acquireWriteAsync(child).join();
            assertEquals(1, childRegion.activeWriterCount());
            assertTrue(childRegion.acquireReadAsync(supervisor).isCompletedExceptionally());
            assertTrue(childRegion.acquireWriteAsync(supervisor).isCompletedExceptionally());
            childWriter.close();

            // An idle child region does NOT become supervisor-owned.
            assertTrue(childRegion.acquireReadAsync(supervisor).isCompletedExceptionally());
            assertTrue(childRegion.acquireWriteAsync(supervisor).isCompletedExceptionally());
            var childReader = childRegion.acquireReadAsync(child).join();
            assertTrue(childReader.heldBy(child));
            childReader.close();
        }
    }

    @Test
    void childCannotReadOrWriteSupervisorRegionEvenAfterItsWriterReleases() {
        var child = actor(3, 13, 42, 7);
        var supervisor = actor(4, 14, 41, 7);
        try (var supervisorRegion = new ActorStateRwLoans(4)) {
            var supervisorWriter = supervisorRegion.acquireWriteAsync(supervisor).join();
            assertTrue(supervisorRegion.acquireReadAsync(child).isCompletedExceptionally());
            supervisorWriter.close();
            assertTrue(supervisorRegion.acquireReadAsync(child).isCompletedExceptionally());
            assertTrue(supervisorRegion.acquireWriteAsync(child).isCompletedExceptionally());
        }
    }

    @Test
    void oneActorAllocationAdmitsReaderCohortThenWriterBeforeLateReader() {
        var first = actor(11, 21, 42, 7);
        var second = actor(12, 22, 42, 7);
        var writer = actor(13, 23, 42, 7);
        var late = actor(14, 24, 42, 7);
        var supervisor = actor(15, 25, 41, 7);
        try (var region = new ActorStateRwLoans(4)) {
            var r1 = region.acquireReadAsync(first).join();
            var r2 = region.acquireReadAsync(second).join();
            assertEquals(2, region.activeReaderCount());
            var waitingWriter = region.acquireWriteAsync(writer);
            var lateReader = region.acquireReadAsync(late);
            assertFalse(waitingWriter.isDone());
            assertFalse(lateReader.isDone());
            assertTrue(region.acquireReadAsync(supervisor).isCompletedExceptionally(),
                    "foreign owner must not queue behind the child");
            assertEquals(2, region.pendingCount());
            r1.close();
            assertFalse(waitingWriter.isDone());
            r2.close();
            var w = waitingWriter.join();
            assertTrue(w.heldBy(writer));
            assertEquals(1, region.activeWriterCount());
            assertEquals(0, region.activeReaderCount());
            assertFalse(lateReader.isDone());
            w.close();
            var r3 = lateReader.join();
            assertTrue(r3.heldBy(late));
            r3.close();
        }
    }

    @Test
    void closedChildRegionNeverBecomesAReusableSupervisorRegion() {
        var child = actor(21, 31, 42, 7);
        var supervisor = actor(22, 32, 41, 7);
        var region = new ActorStateRwLoans(2);
        var read = region.acquireReadAsync(child).join();
        region.close();
        assertFalse(region.physicallyRetired());
        assertFalse(read.heldBy(child), "logical revocation must happen at STOP");
        assertTrue(region.acquireReadAsync(supervisor).isCompletedExceptionally());
        read.close();
        assertTrue(region.physicallyRetired());
        assertTrue(region.acquireWriteAsync(supervisor).isCompletedExceptionally(),
                "retired child backing cannot be re-owned by supervisor");
    }
}
