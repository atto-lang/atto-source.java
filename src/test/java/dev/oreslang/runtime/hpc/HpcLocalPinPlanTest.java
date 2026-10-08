package dev.oreslang.runtime.hpc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class HpcLocalPinPlanTest {

    private static HpcLocalPinPlan.Request request(HpcLocalPinPlan.FrameLocation location,
                                                   HpcLocalPinPlan.Operation operation) {
        return new HpcLocalPinPlan.Request(location, operation,
                true, true, true, true, true, false, false,
                false, false, false, true);
    }

    private static boolean contains(List<String> reasons, String part) {
        return reasons.stream().anyMatch(reason -> reason.contains(part));
    }

    @Test
    void localStackCanBePinnedWithoutIndependentBoxButCannotSuspendAfterReturn() {
        var poll = HpcLocalPinPlan.blockers(request(
                HpcLocalPinPlan.FrameLocation.SYNCHRONOUS_STACK,
                HpcLocalPinPlan.Operation.POLL_LOCALLY));
        assertEquals(1, poll.size());
        assertTrue(contains(poll, "not implemented"));

        var suspend = HpcLocalPinPlan.blockers(request(
                HpcLocalPinPlan.FrameLocation.SYNCHRONOUS_STACK,
                HpcLocalPinPlan.Operation.SUSPEND_PARENT));
        assertTrue(contains(suspend, "stack slot cannot survive"));
    }

    @Test
    void childStoredInParentAsyncFrameNeedsPinnedParentForAddressSensitiveBorrows() {
        var stableParent = HpcLocalPinPlan.blockers(request(
                HpcLocalPinPlan.FrameLocation.ENCLOSING_ASYNC_FRAME,
                HpcLocalPinPlan.Operation.SUSPEND_PARENT));
        assertEquals(1, stableParent.size(), "only missing backend blocks valid shape");

        var parentNotPinned = new HpcLocalPinPlan.Request(
                HpcLocalPinPlan.FrameLocation.ENCLOSING_ASYNC_FRAME,
                HpcLocalPinPlan.Operation.SUSPEND_PARENT,
                true, true, false, true, true, false, false,
                true, true, false, true);
        assertTrue(contains(HpcLocalPinPlan.blockers(parentNotPinned),
                "stable pinned parent async frame"));
    }

    @Test
    void pinningNeedNotImplyHeapAllocationOrCpuThreadAffinity() {
        for (var location : new HpcLocalPinPlan.FrameLocation[]{
                HpcLocalPinPlan.FrameLocation.SYNCHRONOUS_STACK,
                HpcLocalPinPlan.FrameLocation.ENCLOSING_ASYNC_FRAME,
                HpcLocalPinPlan.FrameLocation.ACTOR_ARENA}) {
            var blockers = HpcLocalPinPlan.blockers(request(location,
                    HpcLocalPinPlan.Operation.POLL_LOCALLY));
            assertEquals(1, blockers.size());
        }
    }

    @Test
    void movingPinnedPointeeIsForbiddenEvenIfHandleMoves() {
        var moving = HpcLocalPinPlan.blockers(request(
                HpcLocalPinPlan.FrameLocation.ACTOR_ARENA,
                HpcLocalPinPlan.Operation.MOVE_OWNER));
        assertTrue(contains(moving, "pointee violates location stability"));

        var relocatable = new HpcLocalPinPlan.Request(
                HpcLocalPinPlan.FrameLocation.ACTOR_ARENA,
                HpcLocalPinPlan.Operation.MOVE_OWNER,
                false, false, false, true, true, false, false,
                false, false, false, true);
        assertEquals(1, HpcLocalPinPlan.blockers(relocatable).size());
    }

    @Test
    void localPinnedBorrowCannotBeReturnedOrEscapeThroughAnActor() {
        for (var location : new HpcLocalPinPlan.FrameLocation[]{
                HpcLocalPinPlan.FrameLocation.SYNCHRONOUS_STACK,
                HpcLocalPinPlan.FrameLocation.ENCLOSING_ASYNC_FRAME}) {
            assertTrue(contains(HpcLocalPinPlan.blockers(request(location,
                    HpcLocalPinPlan.Operation.RETURN_BORROWED_PIN)),
                    "cannot outlive its stack or parent"));

            var escaping = new HpcLocalPinPlan.Request(location,
                    HpcLocalPinPlan.Operation.POLL_LOCALLY,
                    true, true, true, true, true, true, true,
                    false, false, false, true);
            var blockers = HpcLocalPinPlan.blockers(escaping);
            assertTrue(contains(blockers, "may not escape"));
            assertTrue(contains(blockers, "actor/isolate"));
        }
    }

    @Test
    void pinningMustBeEstablishedBeforePollingAndDroppedInPlace() {
        var invalid = new HpcLocalPinPlan.Request(
                HpcLocalPinPlan.FrameLocation.INDEPENDENT_FRAME,
                HpcLocalPinPlan.Operation.POLL_LOCALLY,
                true, false, true, false, false, false, false,
                false, false, false, true);
        var blockers = HpcLocalPinPlan.blockers(invalid);
        assertTrue(contains(blockers, "requires pin before native polling"));
        assertTrue(contains(blockers, "in-place destructor/drop"));
        assertTrue(contains(blockers, "complete lifetime"));
    }

    @Test
    void groupRetirementCannotFreePinnedFrameWithPendingWakeOrNativeLease() {
        for (int i = 0; i < 3; i++) {
            var request = new HpcLocalPinPlan.Request(
                    HpcLocalPinPlan.FrameLocation.ACTOR_ARENA,
                    HpcLocalPinPlan.Operation.RETIRE_OWNER,
                    true, true, false, true, true, false, false,
                    i == 0, i == 1, i == 2, true);
            assertTrue(contains(HpcLocalPinPlan.blockers(request),
                    "settled child Futures and detached wakers/leases"));
        }
        var noFence = new HpcLocalPinPlan.Request(
                HpcLocalPinPlan.FrameLocation.ACTOR_ARENA,
                HpcLocalPinPlan.Operation.RETIRE_OWNER,
                true, true, false, true, true, false, false,
                false, false, false, false);
        assertTrue(contains(HpcLocalPinPlan.blockers(noFence),
                "generation-fenced wake IDs"));
    }

    @Test
    void blockersAreImmutableAndInputNullIsRejected() {
        assertThrows(NullPointerException.class,
                () -> HpcLocalPinPlan.blockers(null));
        assertThrows(NullPointerException.class,
                () -> request(null, HpcLocalPinPlan.Operation.POLL_LOCALLY));
        var blockers = HpcLocalPinPlan.blockers(request(
                HpcLocalPinPlan.FrameLocation.ENCLOSING_ASYNC_FRAME,
                HpcLocalPinPlan.Operation.POLL_LOCALLY));
        assertThrows(UnsupportedOperationException.class, () -> blockers.add("fake"));
    }
}
