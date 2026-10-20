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
    void regionTaskHintIsAdvisoryAndCannotUpgradeOtherKinds() {
        var preferred = ExecutionPlacement.Hint.regionTask(42, 3);
        assertEquals(ExecutionPlacement.Kind.TASK, preferred.kind());
        assertEquals(ExecutionPlacement.Affinity.SOFT, preferred.affinity());
        assertEquals(3, preferred.preferredNuma());
        assertEquals(42, preferred.affinityKey());

        var unknown = ExecutionPlacement.Hint.regionTask(42, -1);
        assertEquals(-1, unknown.preferredNuma());
        assertEquals(-1, ExecutionPlacement.Hint.soft(
                ExecutionPlacement.Kind.ACTOR_TURN, 42).preferredNuma());

        assertThrows(IllegalArgumentException.class,
                () -> ExecutionPlacement.Hint.regionTask(4, -2));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionPlacement.Hint.regionTask(4, 65536));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionPlacement.Hint(
                        ExecutionPlacement.Kind.ACTOR_TURN,
                        ExecutionPlacement.Affinity.SOFT, 4, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionPlacement.Hint(
                        ExecutionPlacement.Kind.TASK,
                        ExecutionPlacement.Affinity.THREAD_BOUND, 4, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionPlacement.Hint(
                        ExecutionPlacement.Kind.CONTROL,
                        ExecutionPlacement.Affinity.SOFT, 4, 0));
    }

    @Test
    void nullPlacementPartsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> new ExecutionPlacement.Hint(null, ExecutionPlacement.Affinity.SOFT, 0));
        assertThrows(NullPointerException.class,
                () -> new ExecutionPlacement.Hint(ExecutionPlacement.Kind.TASK, null, 0));
    }
}
