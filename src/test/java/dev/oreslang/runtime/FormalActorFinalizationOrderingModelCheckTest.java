package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounded abstract exploration of physical actor termination ordering.
 * The model is deliberately not a machine-checked refinement of JVM
 * synchronization: live actor monitor tests establish implementation cases.
 */
final class FormalActorFinalizationOrderingModelCheckTest {
    private enum Event {
        STOP, CARRIER_EXIT, CURSOR_WAITERS_DRAIN, OWNED_WAITERS_DRAIN,
        CHILD_DONE, CLAIM, EXIT_HOOK, READY_NOTIFY, DONE_NOTIFY, PARENT_NOTIFY
    }

    private record State(
            boolean stopped, boolean exited, boolean cursors, boolean owned,
            boolean childDone, int claims, boolean hook, boolean ready,
            boolean done, boolean parent) {
        static State initial() {
            return new State(false, false, false, false, false,
                    0, false, false, false, false);
        }
    }

    private static State step(State s, Event action) {
        return switch (action) {
            case STOP -> s.stopped ? null
                    : new State(true, s.exited, s.cursors, s.owned,
                        s.childDone, s.claims, s.hook, s.ready, s.done, s.parent);
            case CARRIER_EXIT -> s.exited ? null
                    : new State(s.stopped, true, s.cursors, s.owned,
                        s.childDone, s.claims, s.hook, s.ready, s.done, s.parent);
            case CURSOR_WAITERS_DRAIN -> !s.stopped || s.cursors ? null
                    : new State(s.stopped, s.exited, true, s.owned,
                        s.childDone, s.claims, s.hook, s.ready, s.done, s.parent);
            case OWNED_WAITERS_DRAIN -> !s.stopped || s.owned ? null
                    : new State(s.stopped, s.exited, s.cursors, true,
                        s.childDone, s.claims, s.hook, s.ready, s.done, s.parent);
            case CHILD_DONE -> s.childDone ? null
                    : new State(s.stopped, s.exited, s.cursors, s.owned,
                        true, s.claims, s.hook, s.ready, s.done, s.parent);
            case CLAIM -> (!s.stopped || !s.exited || !s.cursors || !s.owned
                        || !s.childDone || s.claims != 0) ? null
                    : new State(s.stopped, s.exited, s.cursors, s.owned,
                        s.childDone, 1, s.hook, s.ready, s.done, s.parent);
            case EXIT_HOOK -> s.claims != 1 || s.hook ? null
                    : new State(s.stopped, s.exited, s.cursors, s.owned,
                        s.childDone, s.claims, true, s.ready, s.done, s.parent);
            case READY_NOTIFY -> !s.hook || s.ready ? null
                    : new State(s.stopped, s.exited, s.cursors, s.owned,
                        s.childDone, s.claims, s.hook, true, s.done, s.parent);
            case DONE_NOTIFY -> !s.ready || s.done ? null
                    : new State(s.stopped, s.exited, s.cursors, s.owned,
                        s.childDone, s.claims, s.hook, s.ready, true, s.parent);
            case PARENT_NOTIFY -> !s.done || s.parent ? null
                    : new State(s.stopped, s.exited, s.cursors, s.owned,
                        s.childDone, s.claims, s.hook, s.ready, s.done, true);
        };
    }

    private static void assertInvariant(State s) {
        assertTrue(s.claims == 0 || s.claims == 1,
                "physical finalization claim must be exactly once");
        if (s.claims == 1) {
            assertTrue(s.stopped && s.exited && s.cursors && s.owned && s.childDone,
                    "finalization cannot begin before all physical barriers");
        }
        if (s.hook) assertEquals(1, s.claims,
                "exit hook cannot run before exclusive finalization claim");
        if (s.ready) assertTrue(s.hook, "READY notification follows exit hook");
        if (s.done) assertTrue(s.ready, "DONE notification follows READY");
        if (s.parent) assertTrue(s.done,
                "parent notification cannot overtake child physical DONE");
    }

    @Test
    void exhaustiveStopCarrierWaitersChildAndCompletionOrderings() {
        Set<State> seen = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        State initial = State.initial();
        seen.add(initial);
        queue.add(initial);
        boolean reachedTerminal = false;
        boolean parentAfterChild = false;
        while (!queue.isEmpty()) {
            State current = queue.removeFirst();
            assertInvariant(current);
            reachedTerminal |= current.done && current.parent;
            parentAfterChild |= current.done && current.childDone && !current.parent;
            for (Event action : Event.values()) {
                State next = step(current, action);
                if (next != null && seen.add(next)) queue.addLast(next);
            }
            assertTrue(seen.size() < 2048, "bounded model unexpectedly grew");
        }
        assertTrue(reachedTerminal, "model did not reach a physical DONE state");
        assertTrue(parentAfterChild, "model never witnessed child DONE before parent");
        assertTrue(seen.size() >= 100, "must explore meaningful ordering cases");
    }

    @Test
    void unsafeDuplicateFinalizerClaimHasExpectedCounterexample() {
        State unsafe = new State(true, true, true, true, true,
                2, false, false, false, false);
        assertThrows(AssertionError.class, () -> assertInvariant(unsafe));
    }

    @Test
    void unsafeEarlyDoneHasExpectedCounterexample() {
        State unsafe = new State(true, false, false, false, false,
                0, false, false, true, false);
        assertThrows(AssertionError.class, () -> assertInvariant(unsafe));
    }

    @Test
    void unsafeParentBeforeChildReceiptHasExpectedCounterexample() {
        State unsafe = new State(true, true, true, true, false,
                0, false, false, false, true);
        assertThrows(AssertionError.class, () -> assertInvariant(unsafe));
    }
}
