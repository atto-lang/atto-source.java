package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dependency-free bounded state exploration/refinement check.
 *
 * Enumerates interleavings for two carriers, three actor incarnations and
 * six completed/claimed turns. Every edge is REPLAYED against a fresh real
 * ActorExecutionAuthority and checked against the independent transition
 * model. This is a bounded state proof, not a proof of an entire runtime.
 */
public final class ActorExecutionAuthorityModelProof {
    private enum Action {
        ENTER_A, ENTER_B, LEAVE_A, LEAVE_B, SUSPEND_A, SUSPEND_B,
        REQUEST_STOP, ACK_RETIRE, RESTART, STALE_LEAVE, STALE_ENTER
    }
    private record State(ActorExecutionAuthority.Phase phase, long generation,
                         long sequence, long owner) {
        static State initial() {
            return new State(ActorExecutionAuthority.Phase.READY, 1, 0, 0);
        }
    }
    private record Node(State state, List<Action> path) {}

    public static void main(String[] args) {
        var seen = new HashSet<State>();
        var pending = new ArrayDeque<Node>();
        seen.add(State.initial());
        pending.add(new Node(State.initial(), List.of()));
        long edges = 0;
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            for (Action action : Action.values()) {
                var actual = new ActorExecutionAuthority();
                State before = State.initial();
                ActorExecutionAuthority.Claim[] issued = new ActorExecutionAuthority.Claim[1];
                for (Action earlier : node.path())
                    before = transition(actual, before, earlier, issued);
                ensure(before.equals(node.state()), "replay diverged from state model");
                State after = transition(actual, before, action, issued);
                edges++;
                ensure(after.owner() >= 0 && after.owner() <= 2, "two carrier exclusive entry");
                ensure(after.phase() != ActorExecutionAuthority.Phase.TERMINATED
                        || after.owner() == 0, "retired with a physical owner");
                ensure(after.phase() != ActorExecutionAuthority.Phase.SUSPENDED
                        || after.owner() == 0, "suspended carrier still running");
                if (after.generation() <= 3 && after.sequence() <= 6 && seen.add(after)) {
                    List<Action> path = new ArrayList<>(node.path());
                    path.add(action);
                    pending.add(new Node(after, List.copyOf(path)));
                }
            }
        }
        ensure(seen.size() >= 60 && edges > 600,
                "state exploration unexpectedly shallow: states=" + seen.size()
                        + " edges=" + edges);
        System.out.println("ORES_JAVA_EXECUTION_AUTHORITY_BOUNDED_REFINEMENT_PASS"
                + " states=" + seen.size() + " transitions=" + edges);
    }

    private static State transition(ActorExecutionAuthority gate, State s, Action a,
                                    ActorExecutionAuthority.Claim[] issued) {
        var p = s.phase();
        long g = s.generation(), seq = s.sequence(), owner = s.owner();
        boolean allowed;
        boolean actual;
        switch (a) {
            case ENTER_A, ENTER_B -> {
                long candidate = a == Action.ENTER_A ? 1 : 2;
                allowed = owner == 0 && (p == ActorExecutionAuthority.Phase.READY
                        || p == ActorExecutionAuthority.Phase.SUSPENDED);
                var claim = gate.enter(g, candidate);
                actual = claim.isPresent();
                ensure(allowed == actual, "entry/refusal mismatch " + a + " " + s);
                if (actual) {
                    ensure(claim.orElseThrow().sequence() == seq + 1,
                            "nonmonotonic claim sequence");
                    issued[0] = claim.orElseThrow();
                    s = new State(ActorExecutionAuthority.Phase.RUNNING, g, seq + 1, candidate);
                }
            }
            case LEAVE_A, LEAVE_B, SUSPEND_A, SUSPEND_B -> {
                long carrier = (a == Action.LEAVE_A || a == Action.SUSPEND_A) ? 1 : 2;
                boolean suspend = (a == Action.SUSPEND_A || a == Action.SUSPEND_B);
                allowed = owner == carrier && (p == ActorExecutionAuthority.Phase.RUNNING
                        || p == ActorExecutionAuthority.Phase.STOPPING);
                // Only the actual issued Claim may release a real owner.
                // An equal-valued record is deliberately a forged claim.
                var supplied = owner == carrier && issued[0] != null ? issued[0]
                        : new ActorExecutionAuthority.Claim(g, seq, carrier);
                actual = gate.leave(supplied, suspend);
                ensure(allowed == actual, "owner release mismatch " + a + " " + s);
                if (actual) {
                    issued[0] = null;
                    var next = p == ActorExecutionAuthority.Phase.STOPPING ? p :
                            suspend ? ActorExecutionAuthority.Phase.SUSPENDED :
                                    ActorExecutionAuthority.Phase.READY;
                    s = new State(next, g, seq, 0);
                }
            }
            case REQUEST_STOP -> {
                allowed = p != ActorExecutionAuthority.Phase.STOPPING
                        && p != ActorExecutionAuthority.Phase.TERMINATED;
                actual = gate.requestStop(g);
                ensure(allowed == actual, "stop mismatch " + s);
                if (actual) s = new State(ActorExecutionAuthority.Phase.STOPPING, g, seq, owner);
            }
            case ACK_RETIRE -> {
                allowed = p == ActorExecutionAuthority.Phase.STOPPING && owner == 0;
                actual = gate.acknowledgeRetired(g);
                ensure(allowed == actual, "retire mismatch " + s);
                if (actual) s = new State(ActorExecutionAuthority.Phase.TERMINATED, g, seq, 0);
            }
            case RESTART -> {
                allowed = p == ActorExecutionAuthority.Phase.TERMINATED;
                var result = gate.restart();
                ensure(allowed == result.isPresent(), "restart mismatch " + s);
                if (allowed) {
                    ensure(result.orElseThrow() == g + 1, "generation reused");
                    s = new State(ActorExecutionAuthority.Phase.READY, g + 1, seq, 0);
                }
            }
            case STALE_LEAVE -> {
                actual = gate.leave(new ActorExecutionAuthority.Claim(
                        g, seq, owner == 0 ? 1 : owner), false);
                ensure(!actual, "equal-valued forged claim stole execution authority");
            }
            case STALE_ENTER -> {
                long wrongGeneration = g == 1 ? 2 : g - 1;
                ensure(gate.enter(wrongGeneration, 1).isEmpty(),
                        "stale generation entered the actor");
            }
        }
        ensure(gate.phase() == s.phase() && gate.generation() == s.generation()
                && gate.ownerActive() == (s.owner() != 0),
                "implementation differs from model after " + a + ": " + s);
        return s;
    }

    private static void ensure(boolean predicate, String message) {
        if (!predicate) throw new AssertionError(message);
    }
}
