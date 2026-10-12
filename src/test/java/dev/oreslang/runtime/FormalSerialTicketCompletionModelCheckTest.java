package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive finite-state protocol model. Not a proof of Java implementation
 * refinement: compare its race witnesses with SerialExecutionTicketTest and
 * the source-actor cancellation/continuation suites.
 */
final class FormalSerialTicketCompletionModelCheckTest {
    private enum Phase { RUNNING, SUSPENDED, QUEUED, RESUMED, UNWIND, SETTLED }
    private enum Action {
        AWAIT, PRODUCER_DONE, DUPLICATE_DONE, DRAIN_CALLBACK,
        FINISH, CANCEL_TASK, END_RECEIVE, RESTART_ACTOR,
        RUN_CLEANUP, PUBLISH_OR_DISCARD, PROMOTE_NEXT
    }

    /** epoch/lease are the actor-incarnation and receive-generation authorities. */
    private record State(Phase phase, int epoch, int lease,
                         boolean retired, boolean cancelled,
                         boolean receiveClosed, int cleanups, int publications) {
        static State initial() {
            return new State(Phase.RUNNING, 1, 1, false, false, false, 0, 0);
        }
        boolean terminal() { return phase == Phase.SETTLED; }
    }
    private record Edge(State from, Action action, State to) {}
    private record Model(Set<State> states, List<Edge> edges) {}

    @Test void allInterleavingsFenceStaleCompletionAndDoCleanupBeforePublication() {
        Model model = explore();
        assertTrue(model.states().size() >= 20,
                "must cover queued, resumed, canceled and restarted states");
        assertTrue(model.edges().stream().anyMatch(e -> e.action() == Action.RESTART_ACTOR));
        assertTrue(model.edges().stream().anyMatch(e -> e.action() == Action.DUPLICATE_DONE));
        assertTrue(model.edges().stream().anyMatch(e -> e.action() == Action.PUBLISH_OR_DISCARD));

        for (State s : model.states()) {
            assertTrue(s.cleanups() >= 0 && s.cleanups() <= 1);
            assertTrue(s.publications() >= 0 && s.publications() <= 1);
            if (s.publications() != 0) {
                assertEquals(1, s.cleanups(), "publication must follow cleanup");
                assertFalse(s.cancelled(), "cancelled source must never publish");
            }
            if (s.lease() == 2) {
                assertTrue(s.receiveClosed());
                assertTrue(s.terminal(),
                        "new receive may start only after previous cleanup settles");
            }
            if (s.phase() == Phase.RESUMED) {
                assertFalse(s.retired());
                assertFalse(s.cancelled());
                assertFalse(s.receiveClosed());
                assertEquals(1, s.epoch());
                assertEquals(1, s.lease());
            }
            if (s.terminal()) assertEquals(1, s.cleanups());
        }
        assertEquals(model.states(), canReachTerminal(model),
                "every modeled state has a path to cleanup and final settlement");
    }

    @Test void frameAndTaskNumberAloneProvablyMissIncarnationAndLeaseRestart() {
        // The same numeric frame/task ID is allocated independently in two
        // incarnations. A checker using only those integers accepts this trace.
        int oldFrame = 1, newFrame = 1, oldTask = 1, newTask = 1;
        assertEquals(oldFrame, newFrame);
        assertEquals(oldTask, newTask);
        assertTrue(unsafeAdmit(oldTask, oldFrame, newTask, newFrame));
        assertFalse(validTicket(1, 1, false, 2, 1, true),
                "composite incarnation/receive ownership rejects stale callback");
        assertFalse(validTicket(1, 1, true, 1, 1, true),
                "terminal retirement is required even with matching identities");
        assertTrue(validTicket(1, 1, false, 1, 1, false));
    }

    private static boolean unsafeAdmit(int task, int frame, int currentTask, int currentFrame) {
        return task == currentTask && frame == currentFrame;
    }

    private static boolean validTicket(int issuedEpoch, int issuedLease, boolean retired,
                                       int currentEpoch, int currentLease, boolean closedReceive) {
        return !retired && !closedReceive
                && issuedEpoch == currentEpoch && issuedLease == currentLease;
    }

    private static Model explore() {
        Set<State> discovered = new HashSet<>();
        List<Edge> edges = new ArrayList<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        State initial = State.initial();
        discovered.add(initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            State current = queue.removeFirst();
            for (Action action : Action.values()) {
                Optional<State> next = step(current, action);
                if (next.isEmpty()) continue;
                State target = next.orElseThrow();
                edges.add(new Edge(current, action, target));
                if (discovered.add(target)) queue.addLast(target);
            }
            assertTrue(discovered.size() < 500,
                    "unexpected explosion in bounded scheduler protocol");
        }
        return new Model(Set.copyOf(discovered), List.copyOf(edges));
    }

    private static Optional<State> step(State s, Action action) {
        if (action == Action.DUPLICATE_DONE) {
            return s.phase() == Phase.QUEUED || s.terminal()
                    ? Optional.of(s) : Optional.empty();
        }
        if (action == Action.AWAIT && s.phase() == Phase.RUNNING) {
            return Optional.of(update(s, Phase.SUSPENDED, s.retired(), s.cancelled(),
                    s.receiveClosed(), s.cleanups(), s.publications()));
        }
        if (action == Action.PRODUCER_DONE && s.phase() == Phase.SUSPENDED) {
            return Optional.of(update(s, Phase.QUEUED, s.retired(), s.cancelled(),
                    s.receiveClosed(), s.cleanups(), s.publications()));
        }
        if (action == Action.DRAIN_CALLBACK && s.phase() == Phase.QUEUED) {
            boolean admitted = validTicket(1, 1, s.retired(),
                    s.epoch(), s.lease(), s.receiveClosed());
            return Optional.of(update(s, admitted ? Phase.RESUMED : Phase.UNWIND,
                    s.retired() || !admitted, s.cancelled() || !admitted,
                    s.receiveClosed(), s.cleanups(), s.publications()));
        }
        if (action == Action.FINISH && s.phase() == Phase.RESUMED) {
            return Optional.of(update(s, Phase.UNWIND, s.retired(), s.cancelled(),
                    s.receiveClosed(), s.cleanups(), s.publications()));
        }
        if (action == Action.CANCEL_TASK && !s.terminal() && !s.cancelled()) {
            return Optional.of(update(s, Phase.UNWIND, true, true,
                    s.receiveClosed(), s.cleanups(), s.publications()));
        }
        if (action == Action.END_RECEIVE && !s.receiveClosed()) {
            Phase phase = s.terminal() ? Phase.SETTLED : Phase.UNWIND;
            return Optional.of(update(s, phase, true, true,
                    true, s.cleanups(), s.publications()));
        }
        if (action == Action.RESTART_ACTOR && s.epoch() == 1) {
            return Optional.of(new State(
                    s.terminal() ? Phase.SETTLED : Phase.UNWIND,
                    2, s.lease(), true, true, true,
                    s.cleanups(), s.publications()));
        }
        if (action == Action.RUN_CLEANUP
                && s.phase() == Phase.UNWIND && s.cleanups() == 0) {
            return Optional.of(update(s, Phase.UNWIND, s.retired(), s.cancelled(),
                    s.receiveClosed(), 1, s.publications()));
        }
        if (action == Action.PUBLISH_OR_DISCARD
                && s.phase() == Phase.UNWIND && s.cleanups() == 1) {
            int count = !s.retired() && !s.cancelled()
                    && !s.receiveClosed() && s.epoch() == 1 && s.lease() == 1 ? 1 : 0;
            return Optional.of(update(s, Phase.SETTLED, true, s.cancelled(),
                    s.receiveClosed(), s.cleanups(), count));
        }
        if (action == Action.PROMOTE_NEXT && s.phase() == Phase.SETTLED
                && s.lease() == 1 && s.receiveClosed()) {
            return Optional.of(new State(s.phase(), s.epoch(), 2, true, s.cancelled(),
                    true, s.cleanups(), s.publications()));
        }
        return Optional.empty();
    }

    private static State update(State s, Phase phase, boolean retired,
                                boolean cancelled, boolean receiveClosed,
                                int cleanups, int publications) {
        return new State(phase, s.epoch(), s.lease(),
                retired, cancelled, receiveClosed, cleanups, publications);
    }

    private static Set<State> canReachTerminal(Model model) {
        Map<State, List<State>> reverse = new HashMap<>();
        for (Edge e : model.edges()) {
            reverse.computeIfAbsent(e.to(), ignored -> new ArrayList<>()).add(e.from());
        }
        Set<State> reachable = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        for (State s : model.states()) {
            if (s.terminal() && reachable.add(s)) queue.addLast(s);
        }
        while (!queue.isEmpty()) {
            for (State prior : reverse.getOrDefault(queue.removeFirst(), List.of())) {
                if (reachable.add(prior)) queue.addLast(prior);
            }
        }
        return reachable;
    }
}
