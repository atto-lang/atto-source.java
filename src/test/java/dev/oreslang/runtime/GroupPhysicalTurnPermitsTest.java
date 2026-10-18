package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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
        var seal = gate.trySeal();
        assertEquals(GroupPhysicalTurnPermits.SealStatus.SEALED, seal.status());
        assertEquals(List.of(actor(3)), seal.cancelled());
        var sealedAgain = gate.trySeal();
        assertEquals(GroupPhysicalTurnPermits.SealStatus.ALREADY_SEALED, sealedAgain.status());
        assertTrue(sealedAgain.cancelled().isEmpty());
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
        assertEquals(GroupPhysicalTurnPermits.Admission.QUEUED,
                gate.tryRequest(new GroupPhysicalTurnPermits.Actor(1, 2)).status());
        var newer = gate.tryRelease(one).successor();
        assertEquals(new GroupPhysicalTurnPermits.Actor(1, 2), newer.actor());
        assertNull(gate.tryRelease(next).successor());
        assertNull(gate.tryRelease(newer).successor());
    }

    @Test
    void ticketExhaustionReturnsEveryQueuedActorToLifecycleOwner() throws Exception {
        var gate = new GroupPhysicalTurnPermits(1, 3);
        var first = gate.tryRequest(actor(1)).grant();
        assertEquals(GroupPhysicalTurnPermits.Admission.QUEUED, gate.tryRequest(actor(2)).status());
        assertEquals(GroupPhysicalTurnPermits.Admission.QUEUED, gate.tryRequest(actor(3)).status());

        Field nextTicket = GroupPhysicalTurnPermits.class.getDeclaredField("nextTicket");
        nextTicket.setAccessible(true);
        nextTicket.setLong(gate, Long.MAX_VALUE);

        var released = gate.tryRelease(first);
        assertTrue(released.released());
        assertFalse(released.retry());
        assertNull(released.successor());
        assertEquals(List.of(actor(2), actor(3)), released.cancelled(),
                "ticket exhaustion must return every dropped waiter for cancellation/reschedule");
        assertEquals(new GroupPhysicalTurnPermits.Snapshot(0, 0, true), gate.trySnapshot());
        assertEquals(GroupPhysicalTurnPermits.Admission.CLOSED, gate.tryRequest(actor(4)).status());
    }

    @Test
    void contendedTurnGateNeverParksOrDropsAnActiveCredit() throws Exception {
        var gate = new GroupPhysicalTurnPermits(1, 1);
        var activeGrant = gate.tryRequest(actor(1)).grant();
        var waitingActor = actor(2);
        assertEquals(GroupPhysicalTurnPermits.Admission.QUEUED,
                gate.tryRequest(waitingActor).status());

        Field lockField = GroupPhysicalTurnPermits.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        var gateLock = (ReentrantLock) lockField.get(gate);
        var worker = Executors.newSingleThreadExecutor();
        try {
            gateLock.lock();
            try {
                // Assertions execute on a DIFFERENT thread while the owner
                // holds the lock. A blocking lock() would time out here.
                var pending = worker.submit(() -> {
                    assertEquals(GroupPhysicalTurnPermits.Admission.RETRY,
                            gate.tryRequest(actor(3)).status());
                    var release = gate.tryRelease(activeGrant);
                    assertTrue(release.retry());
                    assertFalse(release.released());
                    assertNull(release.successor());
                    assertEquals(GroupPhysicalTurnPermits.Cancellation.RETRY,
                            gate.tryCancelQueued(waitingActor));
                    assertEquals(GroupPhysicalTurnPermits.SealStatus.RETRY,
                            gate.trySeal().status());
                    assertNull(gate.trySnapshot());
                });
                pending.get(2, TimeUnit.SECONDS);
            } finally {
                gateLock.unlock();
            }
            // A RETRY has NOT transferred or released physical ownership.
            var resumed = gate.tryRelease(activeGrant);
            assertTrue(resumed.released());
            assertEquals(waitingActor, resumed.successor().actor());
            assertEquals(new GroupPhysicalTurnPermits.Snapshot(1, 0, false),
                    gate.trySnapshot());
            assertTrue(gate.tryRelease(resumed.successor()).released());
        } finally {
            worker.shutdownNow();
        }
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
}
