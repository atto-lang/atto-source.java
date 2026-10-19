package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ExecutionPlacementTest {
    @Test
    void factoriesPreserveLogicalKindAndAffinityWithoutGrantingAuthority() {
        var actor = ExecutionPlacement.Hint.soft(
                ExecutionPlacement.Kind.ACTOR_TURN, 17);
        assertEquals(ExecutionPlacement.Kind.ACTOR_TURN, actor.kind());
        assertEquals(ExecutionPlacement.Affinity.SOFT, actor.affinity());
        assertEquals(17, actor.affinityKey());

        var task = ExecutionPlacement.Hint.unbound(ExecutionPlacement.Kind.TASK);
        assertEquals(ExecutionPlacement.Affinity.NONE, task.affinity());

        var continuation = ExecutionPlacement.Hint.threadBound(
                ExecutionPlacement.Kind.CONTINUATION, 5);
        assertEquals(ExecutionPlacement.Affinity.THREAD_BOUND, continuation.affinity());
    }

    @Test
    void nullPlacementPartsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> new ExecutionPlacement.Hint(null, ExecutionPlacement.Affinity.SOFT, 0));
        assertThrows(NullPointerException.class,
                () -> new ExecutionPlacement.Hint(ExecutionPlacement.Kind.TASK, null, 0));
    }
}
