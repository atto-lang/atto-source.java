package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ActorExecutionAuthorityTest {
    @Test
    void singleCarrierWinsConcurrentAdmissionUntilPhysicalExit() throws Exception {
        ActorExecutionAuthority gate = new ActorExecutionAuthority();
        try (var pool = Executors.newFixedThreadPool(12)) {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            CountDownLatch rejected = new CountDownLatch(11);
            List<Future<ActorExecutionAuthority.Claim>> futures = new ArrayList<>();
            for (int i = 1; i <= 12; i++) {
                final long carrier = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    var ticket = gate.enter(1L, carrier);
                    if (ticket.isPresent()) finish.await();
                    else rejected.countDown();
                    return ticket.orElse(null);
                }));
            }
            start.countDown();
            ActorExecutionAuthority.Claim winner = null;
            // Rejections signal without sleeping or polling: the winning
            // carrier cannot leave until the main test releases the latch.
            assertTrue(rejected.await(4, TimeUnit.SECONDS),
                    "eleven competing carriers must fail immediate admission");
            assertEquals(ActorExecutionAuthority.Phase.RUNNING, gate.phase());
            assertTrue(gate.ownerActive());
            assertTrue(gate.enter(1, 99).isEmpty());
            finish.countDown();
            for (Future<ActorExecutionAuthority.Claim> future : futures) {
                var claim = future.get(4, TimeUnit.SECONDS);
                if (claim != null) {
                    assertNull(winner, "at most one winning carrier");
                    winner = claim;
                }
            }
            assertNotNull(winner);
            assertTrue(gate.leave(winner, false));
            assertEquals(ActorExecutionAuthority.Phase.READY, gate.phase());
        }
    }

    @Test
    void stopCannotTransferOwnershipUntilTurnExitsAndRetirementConfirmed() {
        var gate = new ActorExecutionAuthority();
        var live = gate.enter(1, 11).orElseThrow();
        assertTrue(gate.requestStop(1));
        assertEquals(ActorExecutionAuthority.Phase.STOPPING, gate.phase());
        assertFalse(gate.acknowledgeRetired(1));
        assertTrue(gate.enter(1, 12).isEmpty());
        assertFalse(gate.leave(new ActorExecutionAuthority.Claim(1, live.sequence(), 12), false));
        assertTrue(gate.leave(live, false));
        assertFalse(gate.leave(live, false));
        assertTrue(gate.acknowledgeRetired(1));
        assertEquals(ActorExecutionAuthority.Phase.TERMINATED, gate.phase());
        long next = gate.restart().orElseThrow();
        assertEquals(2, next);
        assertTrue(gate.enter(1, 11).isEmpty());
        var successor = gate.enter(next, 12).orElseThrow();
        assertFalse(gate.owns(live));
        assertFalse(gate.leave(live, false));
        assertTrue(gate.leave(successor, false));
    }

    @Test
    void suspensionAllowsNewCarrierOnlyAfterOriginalUnwindsAndStaleResumeIsDenied() {
        var gate = new ActorExecutionAuthority();
        var first = gate.enter(1, 10).orElseThrow();
        assertTrue(gate.enter(1, 20).isEmpty(), "no simultaneous suspension handoff");
        assertTrue(gate.leave(first, true));
        assertEquals(ActorExecutionAuthority.Phase.SUSPENDED, gate.phase());
        var resumed = gate.enter(1, 20).orElseThrow();
        assertFalse(gate.owns(first));
        assertFalse(gate.leave(first, true));
        assertTrue(gate.leave(resumed, false));
        assertEquals(ActorExecutionAuthority.Phase.READY, gate.phase());
    }

    @Test
    void boundedStateExplorationRefinesTheRealAuthority() {
        ActorExecutionAuthorityModelProof.main(new String[0]);
    }

    @Test
    void stopBeforeFirstTurnAndDuplicateRetirementStayTerminal() {
        var gate = new ActorExecutionAuthority();
        assertTrue(gate.requestStop(1));
        assertTrue(gate.acknowledgeRetired(1));
        assertFalse(gate.acknowledgeRetired(1));
        assertFalse(gate.requestStop(1));
        assertTrue(gate.enter(1, 1).isEmpty());
    }
}
