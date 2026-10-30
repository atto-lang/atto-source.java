package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ExecutionContextStateRegionTest {
    @Test
    void ordinaryExecutionContextsRemainUnboundByDefault() {
        var task = ExecutionContext.task(7, 1001);
        assertFalse(task.executionHandle().isBound());
        assertEquals(0, task.executionHandle().slot());
        assertEquals(0, task.executionHandle().generation());

        var actor = ExecutionContext.actor(7, 1002, 9, 3, 11, 0, 0);
        assertFalse(actor.executionHandle().isBound());
        assertEquals(11, actor.allocationDomain().generation());
    }

    @Test
    void regionEnabledActorCanCarryGenerationFencedExecutionHandle() {
        var actor = ExecutionContext.actorBound(
                7, 1002,
                41, 6,
                9, 3, 11,
                0, 0);
        assertEquals(new ExecutionContext.ExecutionId(7, 1002), actor.id());
        assertEquals(new ExecutionContext.ExecutionHandle(41, 6),
                actor.executionHandle());
        assertTrue(actor.executionHandle().isBound());
        assertEquals(11, actor.allocationDomain().generation(),
                "actor arena generation is distinct from execution-slot generation");
    }

    @Test
    void staleExecutionGenerationCannotCompareAsCurrentHandle() {
        var current = new ExecutionContext.ExecutionHandle(41, 6);
        var stale = new ExecutionContext.ExecutionHandle(41, 5);
        var differentSlot = new ExecutionContext.ExecutionHandle(42, 6);
        assertNotEquals(current, stale);
        assertNotEquals(current, differentSlot);
    }

    @Test
    void partialOrNegativeExecutionHandlesFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionContext.ExecutionHandle(1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionContext.ExecutionHandle(0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionContext.ExecutionHandle(-1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionContext.ExecutionHandle(1, -1));
    }
}
