package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OresTestExplorationTest {
    @Test
    void finiteTwoTaskTwoGateReferenceModelFindsAndRejectsCycles() {
        var report = OresTestExploration.run(13, 0, true);
        assertEquals(21L, report.exploredStates());
        assertTrue(report.rejectedCycles() >= 2);
        assertEquals(0, report.strandedWaiters());
        assertEquals(0, report.jvmDeadlockedThreads());
    }

    @Test
    void seededConcreteGateStressHasNoStrandedTickets() {
        var first = OresTestExploration.run(0xC0FFEE, 128, false);
        var replay = OresTestExploration.run(0xC0FFEE, 128, false);
        assertEquals(first, replay);
        assertEquals(128, first.stressCases());
        assertEquals(0, first.strandedWaiters());
    }

    @Test
    void rejectsUnsafeUnboundedTestBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> OresTestExploration.run(1, -1, false));
        assertThrows(IllegalArgumentException.class,
                () -> OresTestExploration.run(1, 100001, false));
    }
}
