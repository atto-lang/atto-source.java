package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Abstract finite-state exploration for resource-generation + actor-incarnation
 * fencing. This does not verify the implementation or actual JNI/OS callbacks.
 *
 * first/second share resource generation 1, successor borrows generation 2
 * after both earlier physical loans retire. Replay of an old receipt has
 * ZERO authority over the successor.
 */
final class FormalNativeResourceGenerationModelCheckTest {
    private record State(int generation, int first, int second, int successor,
            int stopFirst, int stopSecond, int stopSuccessor,
            int doneFirst, int doneSecond, int doneSuccessor, int staleReplay) { }
    private static final int NONE = 0, ACTIVE = 1, RETIRED = 2;
    private static void add(Set<State> seen, ArrayDeque<State> queue, State s) {
        if (seen.add(s)) queue.addLast(s);
    }
    private static void move(Set<State> seen, ArrayDeque<State> queue,
            State old, int component, int value) {
        int[] v = {old.generation, old.first, old.second, old.successor,
                old.stopFirst, old.stopSecond, old.stopSuccessor,
                old.doneFirst, old.doneSecond, old.doneSuccessor, old.staleReplay};
        v[component] = value;
        add(seen, queue, new State(v[0], v[1], v[2], v[3], v[4],
                v[5], v[6], v[7], v[8], v[9], v[10]));
    }

    @Test void staleOldResourceReceiptNeverFinalizesSuccessorActor() {
        var seen = new HashSet<State>();
        var queue = new ArrayDeque<State>();
        add(seen, queue, new State(1, NONE, NONE, NONE, 0, 0, 0, 0, 0, 0, 0));
        boolean sawReplayWhileSuccessorActive = false;
        boolean sawSuccessorDone = false;

        while (!queue.isEmpty()) {
            State s = queue.removeFirst();
            assertFalse(s.doneFirst == 1 && s.first == ACTIVE);
            assertFalse(s.doneSecond == 1 && s.second == ACTIVE);
            assertFalse(s.doneSuccessor == 1 && s.successor == ACTIVE);
            assertFalse(s.successor == ACTIVE && s.generation != 2);

            if (s.staleReplay == 1 && s.successor == ACTIVE) {
                sawReplayWhileSuccessorActive = true;
                assertEquals(0, s.doneSuccessor,
                        "an old loan receipt cannot retire new-generation ownership");
            }
            if (s.doneSuccessor == 1) sawSuccessorDone = true;

            if (s.generation == 1) {
                if (s.first == NONE && s.stopFirst == 0) move(seen, queue, s, 1, ACTIVE);
                if (s.second == NONE && s.stopSecond == 0) move(seen, queue, s, 2, ACTIVE);
                if (s.first == RETIRED && s.second == RETIRED) {
                    move(seen, queue, s, 0, 2);
                }
            } else {
                if (s.successor == NONE && s.stopSuccessor == 0) {
                    move(seen, queue, s, 3, ACTIVE);
                }
                // Old-generation completion delivered again; it carries no
                // authority over a new resource generation or actor identity.
                if (s.staleReplay == 0) move(seen, queue, s, 10, 1);
            }
            if (s.first == ACTIVE) move(seen, queue, s, 1, RETIRED);
            if (s.second == ACTIVE) move(seen, queue, s, 2, RETIRED);
            if (s.successor == ACTIVE) move(seen, queue, s, 3, RETIRED);
            if (s.stopFirst == 0) move(seen, queue, s, 4, 1);
            if (s.stopSecond == 0) move(seen, queue, s, 5, 1);
            if (s.generation == 2 && s.stopSuccessor == 0) move(seen, queue, s, 6, 1);
            if (s.stopFirst == 1 && s.doneFirst == 0 && s.first != ACTIVE) {
                move(seen, queue, s, 7, 1);
            }
            if (s.stopSecond == 1 && s.doneSecond == 0 && s.second != ACTIVE) {
                move(seen, queue, s, 8, 1);
            }
            if (s.stopSuccessor == 1 && s.doneSuccessor == 0
                    && s.successor != ACTIVE) {
                move(seen, queue, s, 9, 1);
            }
        }
        assertTrue(seen.size() >= 150, "model lost important interleavings");
        assertTrue(sawReplayWhileSuccessorActive,
                "must explore a stale receipt while fresh-generation loan is live");
        assertTrue(sawSuccessorDone, "physical retirement permits eventual actor DONE");
    }
}
