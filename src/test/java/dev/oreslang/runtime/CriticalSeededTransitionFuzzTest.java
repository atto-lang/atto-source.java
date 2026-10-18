package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic property stress for critical admission/cancellation.
 * This is NOT a substitute for instrumented guest --race or --fuzz: no
 * randomized OS-thread interleavings or shadow-memory checks occur here.
 */
final class CriticalSeededTransitionFuzzTest {
    @Test
    void seededCancellationAndFifoHandoffNeverStrandAGate() {
        for (long seed = 0; seed < 512; seed++) {
            SplittableRandom rng = new SplittableRandom(seed);
            CriticalGate gate = new CriticalGate();
            var owner = gate.newTicket();
            assertTrue(owner.enterAsync().isDone(), "seed=" + seed);
            int count = 1 + rng.nextInt(18);
            List<CriticalGate.Ticket> waiters = new ArrayList<>();
            List<OresFuture<Void>> admissions = new ArrayList<>();
            List<Boolean> cancelled = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                var ticket = gate.newTicket();
                waiters.add(ticket);
                admissions.add(ticket.enterAsync());
                cancelled.add(false);
                assertFalse(ticket.isHeld(), "seed=" + seed + " waiter=" + i);
            }
            // Alternate cancellation positions while the first owner is
            // still active. No cancellation can affect an unrelated ticket.
            for (int i = 0; i < count; i++) {
                if (rng.nextBoolean()) {
                    cancelled.set(i, true);
                    assertTrue(admissions.get(i).cancel(false), "seed=" + seed);
                }
            }

            owner.close();
            int expectedIndex = 0;
            while (expectedIndex < count) {
                if (cancelled.get(expectedIndex)) {
                    assertFalse(waiters.get(expectedIndex).isHeld(),
                            "cancelled ticket resurrected seed=" + seed);
                    expectedIndex++;
                    continue;
                }
                var next = waiters.get(expectedIndex);
                assertTrue(next.isHeld(),
                        "FIFO violated seed=" + seed + " index=" + expectedIndex);
                assertTrue(admissions.get(expectedIndex).isDone(), "seed=" + seed);
                next.close();
                expectedIndex++;
            }
            for (var ticket : waiters) ticket.close();
            assertFalse(gate.held(), "owner leaked seed=" + seed);
            assertEquals(0, gate.queuedCount(), "waiters leaked seed=" + seed);
            assertEquals(0, CriticalGate.waitingLogicalDomains(),
                    "wait graph leaked seed=" + seed);
        }
    }
}
