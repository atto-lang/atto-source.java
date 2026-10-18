package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
final class GroupPhysicalTurnPermitsTest {
    private static GroupPhysicalTurnPermits.Actor actor(long id) {
        return new GroupPhysicalTurnPermits.Actor(id, 1);
    }

    @Test
    void serialLimitYieldsAndTransfersFifoWithoutDuplicateAdmission() {
        var gate = new GroupPhysicalTurnPermits(1, 3);
        var a = gate.tryRequest(actor(1));
        assertEquals(GroupPhysicalTurnPermits.Admission.GRANTED, a.status());
        assertEquals(GroupPhysicalTurnPermits.Admission.QUEUED, gate.tryRequest(actor(2)).status());
        assertEquals(GroupPhysicalTurnPermits.Admission.QUEUED, gate.tryRequest(actor(3)).status());
        assertEquals(GroupPhysicalTurnPermits.Admission.DUPLICATE, gate.tryRequest(actor(2)).status());
        assertEquals(new GroupPhysicalTurnPermits.Snapshot(1, 2, false), gate.trySnapshot());

        var first = gate.tryRelease(a.grant());
        assertTrue(first.released());
        assertEquals(actor(2), first.successor().actor());
        assertFalse(gate.tryRelease(a.grant()).released()); // stale ticket
        assertEquals(new GroupPhysicalTurnPermits.Snapshot(1, 1, false), gate.trySnapshot());
        var second = gate.tryRelease(first.successor());
        assertEquals(actor(3), second.successor().actor());
        assertNull(gate.tryRelease(second.successor()).successor());
        assertEquals(new GroupPhysicalTurnPermits.Snapshot(0, 0, false), gate.trySnapshot());
    }

    @Test
    void cancellationAndSealingCannotPrematurelyFreeLiveTurns() {
        var gate = new GroupPhysicalTurnPermits(1, 2);
        var first = gate.tryRequest(actor(1)).grant();
        assertEquals(GroupPhysicalTurnPermits.Admission.QUEUED, gate.tryRequest(actor(2)).status());
        assertEquals(GroupPhysicalTurnPermits.Cancellation.ACTIVE, gate.tryCancelQueued(actor(1)));
        assertEquals(GroupPhysicalTurnPermits.Cancellation.CANCELLED, gate.tryCancelQueued(actor(2)));
        assertEquals(GroupPhysicalTurnPermits.Cancellation.ABSENT, gate.tryCancelQueued(actor(2)));
        assertEquals(GroupPhysicalTurnPermits.Admission.QUEUED, gate.tryRequest(actor(3)).status());
        assertEquals(GroupPhysicalTurnPermits.Seal.SEALED, gate.trySeal());
        assertEquals(GroupPhysicalTurnPermits.Seal.ALREADY_SEALED, gate.trySeal());
        assertEquals(GroupPhysicalTurnPermits.Admission.CLOSED, gate.tryRequest(actor(4)).status());
        assertEquals(new GroupPhysicalTurnPermits.Snapshot(1, 0, true), gate.trySnapshot());
        assertNull(gate.tryRelease(first).successor());
        assertEquals(new GroupPhysicalTurnPermits.Snapshot(0, 0, true), gate.trySnapshot());
    }

    @Test
    void incarnationAndCapacityAreFenced() {
        var gate = new GroupPhysicalTurnPermits(2, 1);
        var one = gate.tryRequest(actor(1)).grant();
        var two = gate.tryRequest(actor(2)).grant();
        assertEquals(GroupPhysicalTurnPermits.Admission.QUEUED, gate.tryRequest(actor(3)).status());
        assertEquals(GroupPhysicalTurnPermits.Admission.QUEUE_FULL, gate.tryRequest(actor(4)).status());
        assertFalse(gate.tryRelease(new GroupPhysicalTurnPermits.Grant(actor(1), one.ticket() + 100)).released());
        assertFalse(gate.tryRelease(new GroupPhysicalTurnPermits.Grant(actor(1), one.ticket())).released(),
                "a numerically identical but forged token must not free a credit");
        var anotherGroup = new GroupPhysicalTurnPermits(1, 0);
        var foreign = anotherGroup.tryRequest(actor(1)).grant();
        assertEquals(one.ticket(), foreign.ticket(), "control: numeric tickets overlap");
        assertFalse(gate.tryRelease(foreign).released(), "cross-group token replay");
        assertEquals(2, gate.trySnapshot().active());
        var next = gate.tryRelease(two).successor();
        assertEquals(actor(3), next.actor());
        assertEquals(GroupPhysicalTurnPermits.Admission.INCARNATION_BUSY,
                gate.tryRequest(new GroupPhysicalTurnPermits.Actor(1, 2)).status(),
                "successor incarnation cannot overlap the predecessor physical turn");
        assertNull(gate.tryRelease(one).successor());
        assertEquals(GroupPhysicalTurnPermits.Admission.GRANTED,
                gate.tryRequest(new GroupPhysicalTurnPermits.Actor(1, 2)).status());
        assertNull(gate.tryRelease(next).successor());
    }

    @Test
    void constructorsRejectUnboundedOrForgeableInputs() {
        assertThrows(IllegalArgumentException.class, () -> new GroupPhysicalTurnPermits(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new GroupPhysicalTurnPermits(2, -1));
        assertThrows(IllegalArgumentException.class, () -> new GroupPhysicalTurnPermits.Actor(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new GroupPhysicalTurnPermits.Actor(1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new GroupPhysicalTurnPermits.Grant(actor(1), 0));
    }
    @Test
    void queuedPredecessorMustCancelBeforeSuccessorIncarnationCanEnter() {
        var gate = new GroupPhysicalTurnPermits(1, 2);
        var holder = gate.tryRequest(actor(10)).grant();
        var queued = new GroupPhysicalTurnPermits.Actor(20, 1);
        var restarted = new GroupPhysicalTurnPermits.Actor(20, 2);
        assertEquals(GroupPhysicalTurnPermits.Admission.QUEUED,
                gate.tryRequest(queued).status());
        assertEquals(GroupPhysicalTurnPermits.Admission.INCARNATION_BUSY,
                gate.tryRequest(restarted).status());
        assertEquals(GroupPhysicalTurnPermits.Cancellation.CANCELLED,
                gate.tryCancelQueued(queued));
        assertEquals(GroupPhysicalTurnPermits.Admission.QUEUED,
                gate.tryRequest(restarted).status());
        var transfer = gate.tryRelease(holder);
        assertTrue(transfer.released());
        assertEquals(restarted, transfer.successor().actor());
        assertEquals(GroupPhysicalTurnPermits.Admission.INCARNATION_BUSY,
                gate.tryRequest(queued).status());
        assertNull(gate.tryRelease(transfer.successor()).successor());
        assertEquals(GroupPhysicalTurnPermits.Admission.GRANTED,
                gate.tryRequest(queued).status());
    }

}
