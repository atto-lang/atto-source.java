package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-free exhaustive finite-state reference harness. Run directly:
 * javac --release 21 -d out SupervisorLifecycleFsm.java SupervisorLifecycleFsmOracle.java
 * java -ea -cp out dev.oreslang.runtime.SupervisorLifecycleFsmOracle
 *
 * This verifies the Java reducer's BOUNDED abstraction, not the independent
 * TLA+ specification or actual actor/FFI/scheduler operations.
 */
public final class SupervisorLifecycleFsmOracle {
    private static final Set<Long> CHILDREN = Set.of(11L, 22L);

    private static void check(boolean condition, String problem) {
        if (!condition) throw new AssertionError(problem);
    }

    private static SupervisorLifecycleFsm.State apply(
            SupervisorLifecycleFsm.State s, SupervisorLifecycleFsm.Event e) {
        return SupervisorLifecycleFsm.reduce(s, e);
    }

    private static void mustReject(SupervisorLifecycleFsm.State s,
            SupervisorLifecycleFsm.Event e) {
        try {
            apply(s, e);
            throw new AssertionError("illegal event accepted: " + e + " in " + s);
        } catch (IllegalStateException expected) {
            // guard correctly rejected
        }
    }

    private static void focused() {
        var s = SupervisorLifecycleFsm.State.initial();
        mustReject(s, new SupervisorLifecycleFsm.RestartCommitted(1));
        mustReject(s, new SupervisorLifecycleFsm.PhysicalRelease(1));
        s = apply(s, new SupervisorLifecycleFsm.ShutdownRequested(1, CHILDREN));
        check(apply(s, new SupervisorLifecycleFsm.ShutdownRequested(1, CHILDREN)) == s,
                "idempotent shutdown must retain original child topology");
        mustReject(s, new SupervisorLifecycleFsm.ShutdownRequested(1, Set.of(11L)));
        check(!s.admissionsOpen(), "admissions remained open after fence");
        mustReject(s, new SupervisorLifecycleFsm.RestartRequested(1));
        mustReject(s, new SupervisorLifecycleFsm.ChildFinished(1, 11, false));
        s = apply(s, new SupervisorLifecycleFsm.ActorsFinished(1, false));
        mustReject(s, new SupervisorLifecycleFsm.ActorsFinished(1, true));
        s = apply(s, new SupervisorLifecycleFsm.PhysicalRelease(1));
        s = apply(s, new SupervisorLifecycleFsm.BeforeHookFinished(1, false));
        mustReject(s, new SupervisorLifecycleFsm.BeforeHookFinished(1, true));
        check(s.phase() == SupervisorLifecycleFsm.Phase.WAIT_CHILDREN,
                "early actor completion lost at children barrier");
        s = apply(s, new SupervisorLifecycleFsm.ChildFinished(1, 22, false));
        mustReject(s, new SupervisorLifecycleFsm.ChildFinished(1, 22, true));
        var beforeDuplicate = s;
        s = apply(s, new SupervisorLifecycleFsm.ChildFinished(1, 22, false));
        check(s == beforeDuplicate, "duplicate child completion changed revision");
        s = apply(s, new SupervisorLifecycleFsm.ChildFinished(1, 11, false));
        check(s.phase() == SupervisorLifecycleFsm.Phase.AFTER_HOOK,
                "workers already retired but no transition to post-hook");
        s = apply(s, new SupervisorLifecycleFsm.AfterHookFinished(1, false));
        mustReject(s, new SupervisorLifecycleFsm.AfterHookFinished(1, true));
        check(s.phase() == SupervisorLifecycleFsm.Phase.STOPPED,
                "parent reclaimed before children physically released");
        s = apply(s, new SupervisorLifecycleFsm.ChildReclaimed(1, 11));
        check(s.phase() == SupervisorLifecycleFsm.Phase.STOPPED,
                "parent reclaimed after only one child");
        s = apply(s, new SupervisorLifecycleFsm.ChildReclaimed(1, 22));
        check(s.cleanlyReclaimed(), "final physical release did not settle parent");
        check(apply(s, new SupervisorLifecycleFsm.DeadlineExpired(1)) == s,
                "terminal timeout mutated settled state");
        mustReject(s, new SupervisorLifecycleFsm.ChildFinished(2, 11, false));

        // A pre-fence retirement cannot prove the currently admitted
        // workload has quiesced: the RUNNING phase can admit new workers.
        var recycled = SupervisorLifecycleFsm.State.initial();
        recycled = apply(recycled, new SupervisorLifecycleFsm.ActorsFinished(1, false));
        recycled = apply(recycled, new SupervisorLifecycleFsm.PhysicalRelease(1));
        recycled = apply(recycled, new SupervisorLifecycleFsm.RestartRequested(1));
        check(!recycled.actorsDone() && !recycled.physicalReleased(),
                "restart retained stale pre-fence actor/loan receipts");
        mustReject(recycled, new SupervisorLifecycleFsm.RestartCommitted(1));
        recycled = apply(recycled, new SupervisorLifecycleFsm.ActorsFinished(1, false));
        recycled = apply(recycled, new SupervisorLifecycleFsm.PhysicalRelease(1));
        recycled = apply(recycled, new SupervisorLifecycleFsm.RestartCommitted(1));
        check(recycled.epoch() == 2, "fresh restart receipt was ignored");

        var staleLegacy = SupervisorLifecycleFsm.State.initial();
        staleLegacy = apply(staleLegacy, new SupervisorLifecycleFsm.ActorsFinished(1, false));
        staleLegacy = apply(staleLegacy, new SupervisorLifecycleFsm.PhysicalRelease(1));
        staleLegacy = apply(staleLegacy, new SupervisorLifecycleFsm.ShutdownRequested(1, Set.of()));
        check(!staleLegacy.actorsDone() && !staleLegacy.physicalReleased(),
                "legacy shutdown inherited an unfenced physical release");

        var staleShutdown = SupervisorLifecycleFsm.State.initial();
        staleShutdown = apply(staleShutdown, new SupervisorLifecycleFsm.ActorsFinished(1, false));
        staleShutdown = apply(staleShutdown, new SupervisorLifecycleFsm.PhysicalRelease(1));
        staleShutdown = apply(staleShutdown,
                new SupervisorLifecycleFsm.ShutdownWithChildEpochs(1, Map.of()));
        check(!staleShutdown.actorsDone() && !staleShutdown.physicalReleased(),
                "shutdown retained stale pre-fence physical receipt");
        staleShutdown = apply(staleShutdown,
                new SupervisorLifecycleFsm.BeforeHookFinished(1, false));
        staleShutdown = apply(staleShutdown,
                new SupervisorLifecycleFsm.ActorsFinished(1, false));
        staleShutdown = apply(staleShutdown,
                new SupervisorLifecycleFsm.AfterHookFinished(1, false));
        check(staleShutdown.phase() == SupervisorLifecycleFsm.Phase.STOPPED,
                "shutdown incorrectly declared RECLAIMED without post-fence proof");
        staleShutdown = apply(staleShutdown, new SupervisorLifecycleFsm.PhysicalRelease(1));
        check(staleShutdown.phase() == SupervisorLifecycleFsm.Phase.RECLAIMED,
                "fresh physical receipt not recognized");

        var restart = SupervisorLifecycleFsm.State.initial();
        restart = apply(restart, new SupervisorLifecycleFsm.RestartRequested(1));
        mustReject(restart, new SupervisorLifecycleFsm.RestartCommitted(1));
        restart = apply(restart, new SupervisorLifecycleFsm.ActorsFinished(1, false));
        restart = apply(restart, new SupervisorLifecycleFsm.PhysicalRelease(1));
        restart = apply(restart, new SupervisorLifecycleFsm.RestartCommitted(1));
        check(restart.epoch() == 2 && restart.phase() == SupervisorLifecycleFsm.Phase.RUNNING,
                "generation fencing failed");
        var unchanged = apply(restart, new SupervisorLifecycleFsm.ActorsFinished(1, false));
        check(unchanged == restart, "stale actor callback mutated new incarnation");
        restart = apply(restart, new SupervisorLifecycleFsm.ShutdownRequested(2, Set.of()));
        restart = apply(restart, new SupervisorLifecycleFsm.DeadlineExpired(2));
        check(restart.deadlineExpired() && !restart.logicallyStopped(),
                "deadline fabricated completion");
    }

    private static List<SupervisorLifecycleFsm.Event> candidates(
            SupervisorLifecycleFsm.State s) {
        long e = s.epoch();
        return List.of(
                new SupervisorLifecycleFsm.RestartRequested(e),
                new SupervisorLifecycleFsm.RestartCommitted(e),
                new SupervisorLifecycleFsm.ShutdownRequested(e, CHILDREN),
                new SupervisorLifecycleFsm.BeforeHookFinished(e, false),
                new SupervisorLifecycleFsm.BeforeHookFinished(e, true),
                new SupervisorLifecycleFsm.ChildFinished(e, 11, false),
                new SupervisorLifecycleFsm.ChildFinished(e, 11, true),
                new SupervisorLifecycleFsm.ChildFinished(e, 22, false),
                new SupervisorLifecycleFsm.ChildFinished(e, 22, true),
                new SupervisorLifecycleFsm.ChildReclaimed(e, 11),
                new SupervisorLifecycleFsm.ChildReclaimed(e, 22),
                new SupervisorLifecycleFsm.ActorsFinished(e, false),
                new SupervisorLifecycleFsm.ActorsFinished(e, true),
                new SupervisorLifecycleFsm.AfterHookFinished(e, false),
                new SupervisorLifecycleFsm.AfterHookFinished(e, true),
                new SupervisorLifecycleFsm.PhysicalRelease(e),
                new SupervisorLifecycleFsm.CoordinatorFailed(e),
                new SupervisorLifecycleFsm.DeadlineExpired(e));
    }

    private static void invariant(SupervisorLifecycleFsm.State s) {
        var p = s.phase();
        boolean terminal = s.logicallyStopped();
        check(s.epoch() >= 1 && s.epoch() <= 2, "epoch escaped model bounds");
        check(s.expectedChildren().containsAll(s.completedChildren()), "unknown child");
        check(s.completedChildren().containsAll(s.reclaimedChildren()),
                "physically released child without logical receipt");
        check(s.completedChildren().containsAll(s.failedChildren()),
                "child failure without completion receipt");
        check(!s.preHookFailed() || s.preHookDone(), "pre-hook failure without receipt");
        check(!s.actorsFailed() || s.actorsDone(), "actor failure without receipt");
        check(!s.postHookFailed() || s.postHookDone(), "post-hook failure without receipt");
        check(s.failed() || (!s.preHookFailed() && !s.actorsFailed()
                && !s.postHookFailed() && s.failedChildren().isEmpty()),
                "failure aggregate dropped completed failure receipt");
        check(!s.physicalReleased() || s.actorsDone(), "arena reclaimed before actor");
        if (terminal) {
            check(s.preHookDone() && s.postHookDone() && s.actorsDone(), "early terminal");
            check(s.completedChildren().equals(s.expectedChildren()),
                    "terminated with live descendants");
        }
        if (p == SupervisorLifecycleFsm.Phase.RECLAIMED) {
            check(s.physicalReleased()
                            && s.reclaimedChildren().equals(s.expectedChildren()),
                    "false full reclamation");
        }
        if (p == SupervisorLifecycleFsm.Phase.RUNNING) {
            check(!s.deadlineExpired(), "active supervisor received shutdown timeout");
        }
        if (p == SupervisorLifecycleFsm.Phase.AFTER_HOOK) {
            check(s.preHookDone() && s.actorsDone()
                            && s.completedChildren().equals(s.expectedChildren()),
                    "post-hook started before prerequisites");
        }
    }

    private static void exhaustive() {
        var initial = SupervisorLifecycleFsm.State.initial();
        Set<SupervisorLifecycleFsm.State> seen = new HashSet<>();
        ArrayDeque<SupervisorLifecycleFsm.State> queue = new ArrayDeque<>();
        Map<SupervisorLifecycleFsm.State, List<SupervisorLifecycleFsm.State>> edges =
                new HashMap<>();
        seen.add(initial);
        queue.add(initial);
        int traversals = 0;
        while (!queue.isEmpty()) {
            var state = queue.removeFirst();
            invariant(state);
            List<SupervisorLifecycleFsm.State> successors = new ArrayList<>();
            for (var event : candidates(state)) {
                try {
                    var next = apply(state, event);
                    if (next == state) continue; // duplicate receipt, not a new state
                    if (next.epoch() > 2) continue; // finite instantiation
                    invariant(next);
                    successors.add(next);
                    traversals++;
                    if (seen.add(next)) queue.addLast(next);
                } catch (IllegalStateException rejected) {
                    // The reducer correctly rejected an unenabled transition.
                }
            }
            edges.put(state, List.copyOf(successors));
            check(seen.size() <= 250_000, "bounded model unexpectedly exploded");
        }
        check(seen.size() >= 40, "insufficient interleaving coverage");
        // Existential progress: from every admitted shutdown state there is
        // a path to logical/physical completion IF the environment supplies
        // missing hook, worker, loan and child acknowledgments.
        Set<SupervisorLifecycleFsm.State> canReclaim = new HashSet<>();
        for (var state : seen) if (state.phase() == SupervisorLifecycleFsm.Phase.RECLAIMED) {
            canReclaim.add(state);
        }
        boolean grew;
        do {
            grew = false;
            for (var entry : edges.entrySet()) {
                if (!canReclaim.contains(entry.getKey())
                        && entry.getValue().stream().anyMatch(canReclaim::contains)) {
                    canReclaim.add(entry.getKey());
                    grew = true;
                }
            }
        } while (grew);
        for (var state : seen) {
            if (state.phase() != SupervisorLifecycleFsm.Phase.RUNNING
                    && state.phase() != SupervisorLifecycleFsm.Phase.RESTARTING) {
                check(canReclaim.contains(state),
                        "shutdown state has no cooperative completion path: " + state);
            }
        }
        System.out.println("PASS: bounded Java reducer BFS: " + seen.size()
                + " states, " + traversals + " transitions; safety and conditional reachability");
    }

    public static void main(String[] args) {
        focused();
        System.out.println("PASS: epoch fencing, early receipts, conflicting acknowledgments, "
                + "child topology, timeouts and loan barriers");
        exhaustive();
    }
}
