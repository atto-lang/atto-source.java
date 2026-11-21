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
    void regionOwnerIsBoundAtAllocationBeforeAnyCallerCanClaimIt() {
        var child = actor(901, 901, 42, 7);
        var supervisor = actor(902, 902, 41, 7);
        var restartedChild = actor(903, 903, 42, 8);
        try (var region = new ActorStateRwLoans(4, () -> { },
                child.allocationDomain())) {
            assertTrue(region.acquireReadAsync(supervisor).isCompletedExceptionally(),
                    "foreign first reader must not claim the region owner");
            assertTrue(region.acquireWriteAsync(supervisor).isCompletedExceptionally(),
                    "foreign first writer must not claim the region owner");
            assertTrue(region.acquireReadAsync(restartedChild).isCompletedExceptionally(),
                    "a restarted allocation cannot seize the old incarnation");
            assertEquals(0, region.activeReaderCount());
            assertEquals(0, region.activeWriterCount());
            assertEquals(0, region.pendingCount());
            var reader = region.acquireReadAsync(child).join();
            assertTrue(reader.heldBy(child));
            reader.close();
            var writer = region.acquireWriteAsync(child).join();
            assertTrue(writer.heldBy(child));
            writer.close();
            assertTrue(region.acquireWriteAsync(supervisor).isCompletedExceptionally(),
                    "successful owner admission must not grant supervisor ownership");
        }
    }

    @Test
    void explicitRegionBindingRejectsNonActorOwnerDomains() {
        assertThrows(IllegalArgumentException.class, () ->
                new ActorStateRwLoans(1, () -> { },
                        ExecutionContext.AllocationDomain.none()));
        assertThrows(IllegalArgumentException.class, () ->
                new ActorStateRwLoans(1, () -> { },
                        new ExecutionContext.AllocationDomain(
                                ExecutionContext.AllocationKind.RUNTIME, 9001, 7, 1)));
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
    void threeSevenFifteenLogicalExecutionsCannotBargeAheadOfQueuedWriter() {
        // Logical execution stress within ONE actor allocation. This is not
        // multi-heap singleton publication or cross-actor shared state.
        for (int cohortSize : new int[]{3, 7, 15}) {
            try (var region = new ActorStateRwLoans(32)) {
                var lead = actor(1000 + cohortSize, 2000 + cohortSize, 42, 7);
                var writer = actor(3000 + cohortSize, 4000 + cohortSize, 42, 7);
                var first = region.acquireReadAsync(lead).join();
                var waitingWriter = region.acquireWriteAsync(writer);
                assertFalse(waitingWriter.isDone());
                var queued = new java.util.ArrayList<
                        java.util.Map.Entry<ExecutionContext, OresFuture<ActorStateRwLoans.StateLoan>>>();
                for (int i = 0; i < cohortSize; ++i) {
                    var reader = actor(5000 + cohortSize * 100L + i,
                            6000 + cohortSize * 100L + i, 42, 7);
                    var pending = region.acquireReadAsync(reader);
                    assertFalse(pending.isDone(), "late reader barged before writer");
                    queued.add(java.util.Map.entry(reader, pending));
                }
                assertEquals(cohortSize + 1, region.pendingCount());
                first.close();
                var grantedWriter = waitingWriter.join();
                assertTrue(grantedWriter.heldBy(writer));
                assertEquals(1, region.activeWriterCount());
                assertEquals(0, region.activeReaderCount());
                for (var item : queued) assertFalse(item.getValue().isDone());
                grantedWriter.close();
                assertEquals(cohortSize, region.activeReaderCount());
                for (var item : queued) {
                    var read = item.getValue().join();
                    assertTrue(read.heldBy(item.getKey()));
                    read.close();
                }
                assertEquals(0, region.pendingCount());
                assertEquals(0, region.activeReaderCount());
                assertEquals(0, region.activeWriterCount());
            }
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

    @Test
    void sameCarrierSlotCannotAdmitTwoLiveLogicalExecutions() {
        var first = actor(31, 40, 42, 7);
        var replacement = actor(32, 40, 42, 7);
        try (var region = new ActorStateRwLoans(3)) {
            var held = region.acquireReadAsync(first).join();
            assertTrue(region.acquireReadAsync(replacement).isCompletedExceptionally(),
                    "a different logical task may not reuse an occupied physical slot");
            assertEquals(1, region.activeReaderCount());
            held.close();

            var second = region.acquireWriteAsync(replacement).join();
            assertTrue(second.heldBy(replacement));
            assertEquals(1, region.activeWriterCount());
            second.close();
        }
    }

    @Test
    void boundedQueueRejectsBargingWithoutDisplacingWaitingWriter() {
        var reader = actor(41, 51, 42, 7);
        var writer = actor(42, 52, 42, 7);
        var lateReader = actor(43, 53, 42, 7);
        try (var region = new ActorStateRwLoans(1)) {
            var held = region.acquireReadAsync(reader).join();
            var waitingWriter = region.acquireWriteAsync(writer);
            assertFalse(waitingWriter.isDone());
            assertEquals(1, region.pendingCount());

            var full = region.acquireReadAsync(lateReader);
            assertTrue(full.isCompletedExceptionally(),
                    "a full wait queue must not allow a new reader to barge");
            assertEquals(1, region.pendingCount());
            held.close();

            var exclusive = waitingWriter.join();
            assertTrue(exclusive.heldBy(writer));
            assertEquals(1, region.activeWriterCount());
            assertEquals(0, region.activeReaderCount());
            exclusive.close();

            var retry = region.acquireReadAsync(lateReader).join();
            assertTrue(retry.heldBy(lateReader),
                    "failed admission must not retain a phantom slot reservation");
            retry.close();
        }
    }

    @Test
    void priorActorIncarnationNeverAuthorizesNewGeneration() {
        var original = actor(51, 61, 42, 7);
        var restarted = actor(52, 62, 42, 8);
        try (var region = new ActorStateRwLoans(2)) {
            var first = region.acquireWriteAsync(original).join();
            first.close();
            assertTrue(region.acquireReadAsync(restarted).isCompletedExceptionally());
            assertTrue(region.acquireWriteAsync(restarted).isCompletedExceptionally(),
                    "the same actor ID with a new epoch is a different allocation authority");
            var stillOriginal = region.acquireReadAsync(original).join();
            assertTrue(stillOriginal.heldBy(original));
            stillOriginal.close();
        }
    }
}
