package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Abstract finite-state shutdown ordering contract. Native/actor physical
 * completion can occur in any order, but shutdown must seal actor admission
 * before socket wakeup and must not reclaim either graph before both joins.
 *
 * This model is not evidence that Java/LLVM native code executed correctly.
 */
final class FormalContextShutdownOrderModelCheckTest {
    private record State(boolean actorSealed, boolean ioSealed,
            boolean actorPhysicallyRetired, boolean ioPhysicallyRetired,
            boolean actorJoined, boolean ioJoined, boolean reclaimed,
            boolean retrying) { }

    private static void offer(Set<State> seen, ArrayDeque<State> queue, State s) {
        if (seen.add(s)) queue.addLast(s);
    }

    @Test void physicalRetirementRequiresBothJoinsRegardlessOfShutdownRace() {
        var seen = new HashSet<State>();
        var queue = new ArrayDeque<State>();
        offer(seen, queue, new State(false, false, false, false,
                false, false, false, false));
        boolean sawFailedRetry = false;
        boolean sawIoBeforeActorJoin = false;
        boolean sawActorBeforeIoJoin = false;
        boolean sawSuccessfulReclaim = false;

        while (!queue.isEmpty()) {
            State s = queue.removeFirst();
            assertFalse(s.ioSealed && !s.actorSealed,
                    "transport wakeup must follow actor admission sealing");
            if (s.actorJoined) {
                assertTrue(s.actorSealed && s.actorPhysicallyRetired
                        && s.ioPhysicallyRetired,
                        "actor arena cannot join while a physical native loan remains");
            }
            if (s.ioJoined) {
                assertTrue(s.ioSealed && s.ioPhysicallyRetired,
                        "I/O join must require worker and callback receipts");
            }
            if (s.reclaimed) {
                assertTrue(s.actorJoined && s.ioJoined,
                        "never free context runtime until both joins succeeded");
                sawSuccessfulReclaim = true;
                continue;
            }
            if (s.retrying) sawFailedRetry = true;
            if (s.ioJoined && !s.actorJoined) sawIoBeforeActorJoin = true;
            if (s.actorJoined && !s.ioJoined) sawActorBeforeIoJoin = true;

            if (!s.actorSealed) {
                offer(seen, queue, new State(true, false,
                        s.actorPhysicallyRetired, s.ioPhysicallyRetired,
                        false, false, false, s.retrying));
                continue;
            }
            if (!s.ioSealed) {
                offer(seen, queue, new State(true, true,
                        s.actorPhysicallyRetired, s.ioPhysicallyRetired,
                        false, false, false, s.retrying));
                continue;
            }
            if (!s.actorPhysicallyRetired) {
                offer(seen, queue, new State(true, true, true,
                        s.ioPhysicallyRetired, false, s.ioJoined,
                        false, s.retrying));
            }
            if (!s.ioPhysicallyRetired) {
                offer(seen, queue, new State(true, true,
                        s.actorPhysicallyRetired, true, false, false,
                        false, s.retrying));
            }
            if (!s.actorJoined && s.actorPhysicallyRetired
                    && s.ioPhysicallyRetired) {
                offer(seen, queue, new State(true, true, true, true,
                        true, s.ioJoined, false, s.retrying));
            }
            if (!s.ioJoined && s.ioPhysicallyRetired) {
                offer(seen, queue, new State(true, true,
                        s.actorPhysicallyRetired, true,
                        s.actorJoined, true, false, s.retrying));
            }
            if (!s.retrying && (!s.actorJoined || !s.ioJoined)) {
                // A bounded close attempt failed before all physical receipts,
                // but the same objects remain available for an explicit retry.
                offer(seen, queue, new State(true, true,
                        s.actorPhysicallyRetired, s.ioPhysicallyRetired,
                        s.actorJoined, s.ioJoined, false, true));
            }
            if (s.actorJoined && s.ioJoined) {
                offer(seen, queue, new State(true, true, true, true,
                        true, true, true, s.retrying));
            }
        }
        assertTrue(seen.size() >= 12, "shutdown model did not explore enough races");
        assertTrue(sawFailedRetry);
        assertTrue(sawIoBeforeActorJoin);
        assertTrue(sawActorBeforeIoJoin);
        assertTrue(sawSuccessfulReclaim);
    }
}
