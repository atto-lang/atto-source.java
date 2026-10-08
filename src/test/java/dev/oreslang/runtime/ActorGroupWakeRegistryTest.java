package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class ActorGroupWakeRegistryTest {

    @Test
    void aWakeMayBeClaimedOnlyOnceAndRetainsItsHeapRootUntilReleased() {
        ActorGroupWakeRegistry registry = new ActorGroupWakeRegistry();
        Object owner = new Object();
        var wake = registry.register(owner);
        assertEquals(1, registry.outstanding());
        assertTrue(registry.claim(wake));
        assertFalse(registry.claim(wake));
        assertTrue(registry.current(wake));
        assertEquals(1, registry.outstanding(),
                "queued continuation is still a frame-retirement root");
        assertTrue(registry.release(wake));
        assertFalse(registry.release(wake));
        assertFalse(registry.current(wake));
        assertEquals(0, registry.outstanding());
    }

    @Test
    void groupSealRevokesPendingAndAlreadyClaimedTickets() {
        ActorGroupWakeRegistry registry = new ActorGroupWakeRegistry();
        Object owner = new Object();
        var claimed = registry.register(owner);
        var pending = registry.register(owner);
        assertTrue(registry.claim(claimed));
        assertEquals(2, registry.outstanding());
        registry.seal();
        registry.seal(); // idempotent
        assertEquals(0, registry.outstanding());
        assertTrue(registry.sealed());
        assertFalse(registry.current(claimed));
        assertFalse(registry.claim(pending));
        assertFalse(registry.current(pending));
        assertThrows(IllegalStateException.class, () -> registry.register(owner));
    }

    @Test
    void actorLeaveAndRejoinCannotResurrectPriorWakeEpoch() {
        ActorGroupWakeRegistry registry = new ActorGroupWakeRegistry();
        Object owner = new Object();
        var previous = registry.register(owner);
        assertTrue(registry.claim(previous));
        registry.revokeOwner(owner);
        assertFalse(registry.current(previous));
        assertEquals(0, registry.outstanding());

        var later = registry.register(owner);
        assertNotEquals(previous.sequence(), later.sequence());
        assertFalse(registry.claim(previous));
        assertTrue(registry.claim(later));
        assertTrue(registry.current(later));
    }

    @Test
    void ticketsCannotBeReplayedIntoAnotherActorGroupRegistry() {
        ActorGroupWakeRegistry first = new ActorGroupWakeRegistry();
        ActorGroupWakeRegistry other = new ActorGroupWakeRegistry();
        var ticket = first.register(new Object());
        assertFalse(other.current(ticket));
        assertFalse(other.claim(ticket));
        assertFalse(other.release(ticket));
        assertTrue(first.current(ticket));
        assertTrue(first.release(ticket));
    }

    @Test
    void concurrentClaimAndGroupCloseNeverLeavesAnyReusableTicket() throws Exception {
        ActorGroupWakeRegistry registry = new ActorGroupWakeRegistry();
        var ticket = registry.register(new Object());
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicBoolean claimResult = new AtomicBoolean();
        Thread claimant = Thread.ofPlatform().start(() -> {
            ready.countDown();
            try {
                if (!go.await(5, TimeUnit.SECONDS)) return;
                claimResult.set(registry.claim(ticket));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        registry.seal();
        claimant.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(claimant.isAlive());
        assertEquals(0, registry.outstanding());
        assertFalse(registry.current(ticket));
        assertFalse(registry.claim(ticket));
        // Both races are safe: claim may have won, but seal always revokes it.
        if (claimResult.get()) assertFalse(registry.current(ticket));
    }

    @Test
    void boundedWakeBudgetCannotBeExhaustedByUnboundedPendingFutures() {
        ActorGroupWakeRegistry registry = new ActorGroupWakeRegistry(2);
        Object owner = new Object();
        var first = registry.register(owner);
        var second = registry.register(owner);
        assertEquals(2, registry.outstanding());
        assertThrows(IllegalStateException.class, () -> registry.register(owner));
        assertTrue(registry.claim(first));
        assertThrows(IllegalStateException.class, () -> registry.register(owner),
                "claimed wake is still a queued/retained frame root");
        assertTrue(registry.release(first));
        var third = registry.register(owner);
        assertTrue(registry.current(third));
        registry.revokeOwner(owner);
        assertEquals(0, registry.outstanding());
        assertFalse(registry.current(second));
        assertFalse(registry.current(third));
        assertThrows(IllegalArgumentException.class, () -> new ActorGroupWakeRegistry(0));
        assertThrows(IllegalArgumentException.class, () -> new ActorGroupWakeRegistry(65_537));
    }

    @Test
    void rejectsNullOwnerAndKeepsGroupUnavailableOnceSealed() {
        ActorGroupWakeRegistry registry = new ActorGroupWakeRegistry();
        assertThrows(NullPointerException.class, () -> registry.register(null));
        assertThrows(NullPointerException.class, () -> registry.revokeOwner(null));
        registry.seal();
        assertTrue(registry.sealed());
        assertThrows(IllegalStateException.class, () -> registry.register("owner"));
    }
}
