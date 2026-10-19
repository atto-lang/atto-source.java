package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorPoolSizingTest {
    @Test void smallSystemsKeepIsolatedMinimums() {
        for (int cpus = 1; cpus <= 4; cpus++) {
            var plan = ActorPoolSizing.cpuBudgeted(cpus, null);
            assertEquals(1, plan.privateWorkers());
            assertEquals(1, plan.untrustedWorkers());
            assertEquals(1, plan.sharedWorkers());
            assertEquals(3, plan.total());
            assertEquals(3, plan.actorWorkerBudget());
        }
    }

    @Test void largeSystemsStayWithinActorWorkerBudget() {
        for (int cpus = 5; cpus <= 256; cpus++) {
            var plan = ActorPoolSizing.cpuBudgeted(cpus, null);
            assertTrue(plan.total() <= plan.actorWorkerBudget());
            assertEquals(cpus - 1, plan.actorWorkerBudget());
            assertEquals(plan.privateWorkers(), plan.untrustedWorkers());
            assertTrue(plan.sharedWorkers() >= 1);
        }
        var eight = ActorPoolSizing.cpuBudgeted(8, null);
        assertEquals(2, eight.privateWorkers());
        assertEquals(3, eight.sharedWorkers());
        assertEquals(2, eight.untrustedWorkers());
        assertEquals(7, eight.total());
    }

    @Test void explicitSharedParallelismRespectsLimitOrFailsClosed() {
        var configured = ActorPoolSizing.cpuBudgeted(16, 9);
        assertEquals(3, configured.privateWorkers());
        assertEquals(9, configured.sharedWorkers());
        assertEquals(3, configured.untrustedWorkers());
        assertEquals(15, configured.total());
        assertThrows(IllegalArgumentException.class, () -> ActorPoolSizing.cpuBudgeted(8, 6));
        assertThrows(IllegalArgumentException.class, () -> ActorPoolSizing.cpuBudgeted(8, 0));
        assertThrows(IllegalArgumentException.class, () -> ActorPoolSizing.cpuBudgeted(0, null));
    }

    @Test void budgetIsIndependentOfActorGroupMembership() {
        var first = ActorPoolSizing.cpuBudgeted(12, null);
        var second = ActorPoolSizing.cpuBudgeted(12, null);
        assertEquals(first, second);
        assertEquals(11, first.total());
        assertEquals(11, first.actorWorkerBudget());
    }
}
