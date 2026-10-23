package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-free Java 21 conformance test for real production reducer,
 * generation-fence and trace classes. No ActorRuntime test double.
 */
public final class SupervisorLifecycleTraceOracle {
    private SupervisorLifecycleTraceOracle() {}

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
    private static void reject(Runnable action, String description) {
        try {
            action.run();
            throw new AssertionError(description);
        } catch (IllegalStateException expected) {
            // Fail-closed admission / replay rejection
        }
    }

    public static void main(String[] args) {
        var journal = new SupervisorLifecycleTrace.Journal(32);
        var root = SupervisorLifecycleFsm.State.initial();
        var child = SupervisorLifecycleFsm.State.initial();
        root = record(journal, 1, root,
                new SupervisorLifecycleFsm.ShutdownRequested(1, Set.of(2L)));
        root = record(journal, 1, root,
                new SupervisorLifecycleFsm.BeforeHookFinished(1, false));
        child = record(journal, 2, child,
                new SupervisorLifecycleFsm.ShutdownRequested(1, Set.of()));
        child = record(journal, 2, child,
                new SupervisorLifecycleFsm.BeforeHookFinished(1, false));
        child = record(journal, 2, child,
                new SupervisorLifecycleFsm.ActorsFinished(1, false));
        child = record(journal, 2, child,
                new SupervisorLifecycleFsm.AfterHookFinished(1, false));
        root = record(journal, 1, root,
                new SupervisorLifecycleFsm.ChildFinished(1, 2L, false));
        root = record(journal, 1, root,
                new SupervisorLifecycleFsm.ActorsFinished(1, false));
        root = record(journal, 1, root,
                new SupervisorLifecycleFsm.AfterHookFinished(1, false));
        check(root.logicallyStopped() && child.logicallyStopped(), "logical drain");
        check(!root.cleanlyReclaimed() && !child.cleanlyReclaimed(),
                "logical termination is not physical reclamation");
        var snapshot = journal.snapshot();
        check(snapshot.complete(), "unexpected truncation");
        check(snapshot.issued() == 9, "unexpected trace event count");
        SupervisorLifecycleTrace.verifyComplete(snapshot);

        // A duplicate or stale event must not change the trace sequence.
        record(journal, 1, root, new SupervisorLifecycleFsm.BeforeHookFinished(0, false));
        check(journal.snapshot().issued() == 9, "stale event wrote trace");

        var falseAfter = SupervisorLifecycleFsm.State.initial();
        var tampered = new ArrayList<>(snapshot.entries());
        var first = tampered.get(0);
        tampered.set(0, new SupervisorLifecycleTrace.Entry(
                first.sequence(), first.supervisorId(), first.before(),
                first.event(), falseAfter));
        reject(() -> SupervisorLifecycleTrace.verifyComplete(
                new SupervisorLifecycleTrace.Snapshot(tampered, 0, tampered.size())),
                "tampered after-state must not verify");

        var missing = new ArrayList<>(snapshot.entries());
        missing.remove(3);
        reject(() -> SupervisorLifecycleTrace.verifyComplete(
                new SupervisorLifecycleTrace.Snapshot(missing, 0, snapshot.issued())),
                "missing entry must not verify");

        var small = new SupervisorLifecycleTrace.Journal(2);
        for (var event : snapshot.entries()) {
            small.record(event.supervisorId(), event.before(), event.event(), event.after());
        }
        check(small.snapshot().dropped() == 7, "overflow not recorded");
        reject(() -> SupervisorLifecycleTrace.verifyComplete(small.snapshot()),
                "overflow must never be presented as full verification");

        // An individually valid parent reducer transition cannot certify
        // child completion while that child's onStopped hook is still open.
        var premature = new SupervisorLifecycleTrace.Journal(16);
        var pRoot = SupervisorLifecycleFsm.State.initial();
        var pChild = SupervisorLifecycleFsm.State.initial();
        pRoot = record(premature, 1, pRoot,
                new SupervisorLifecycleFsm.ShutdownRequested(1, Set.of(2L)));
        pRoot = record(premature, 1, pRoot,
                new SupervisorLifecycleFsm.BeforeHookFinished(1, false));
        pChild = record(premature, 2, pChild,
                new SupervisorLifecycleFsm.ShutdownRequested(1, Set.of()));
        pChild = record(premature, 2, pChild,
                new SupervisorLifecycleFsm.BeforeHookFinished(1, false));
        pChild = record(premature, 2, pChild,
                new SupervisorLifecycleFsm.ActorsFinished(1, false));
        pRoot = record(premature, 1, pRoot,
                new SupervisorLifecycleFsm.ChildFinished(1, 2L, false));
        pChild = record(premature, 2, pChild,
                new SupervisorLifecycleFsm.AfterHookFinished(1, false));
        pRoot = record(premature, 1, pRoot,
                new SupervisorLifecycleFsm.ActorsFinished(1, false));
        pRoot = record(premature, 1, pRoot,
                new SupervisorLifecycleFsm.AfterHookFinished(1, false));
        reject(() -> SupervisorLifecycleTrace.verifyComplete(premature.snapshot()),
                "premature child acknowledgement accepted by tree trace checker");

        var doubleParent = new SupervisorLifecycleTrace.Journal(8);
        var p1 = SupervisorLifecycleFsm.State.initial();
        var p2 = SupervisorLifecycleFsm.State.initial();
        p1 = record(doubleParent, 1, p1,
                new SupervisorLifecycleFsm.ShutdownRequested(1, Set.of(3L)));
        p2 = record(doubleParent, 2, p2,
                new SupervisorLifecycleFsm.ShutdownRequested(1, Set.of(3L)));
        reject(() -> SupervisorLifecycleTrace.verifyComplete(doubleParent.snapshot()),
                "child cannot belong to two parents");

        var cycle = new SupervisorLifecycleTrace.Journal(8);
        var c1 = SupervisorLifecycleFsm.State.initial();
        var c2 = SupervisorLifecycleFsm.State.initial();
        c1 = record(cycle, 1, c1,
                new SupervisorLifecycleFsm.ShutdownRequested(1, Set.of(2L)));
        c2 = record(cycle, 2, c2,
                new SupervisorLifecycleFsm.ShutdownRequested(1, Set.of(1L)));
        reject(() -> SupervisorLifecycleTrace.verifyComplete(cycle.snapshot()),
                "cyclic supervisor tree cannot verify");

        // Exercise the actual generation-tagged reducer and trace schema.
        var generations = new SupervisorLifecycleTrace.Journal(32);
        var oldChild = SupervisorLifecycleFsm.State.initial();
        oldChild = record(generations, 6, oldChild,
                new SupervisorLifecycleFsm.RestartRequested(1));
        oldChild = record(generations, 6, oldChild,
                new SupervisorLifecycleFsm.ActorsFinished(1, false));
        oldChild = record(generations, 6, oldChild,
                new SupervisorLifecycleFsm.PhysicalRelease(1));
        oldChild = record(generations, 6, oldChild,
                new SupervisorLifecycleFsm.RestartCommitted(1));
        check(oldChild.epoch() == 2, "child restart generation did not advance");

        var protectedParent = SupervisorLifecycleFsm.State.initial();
        protectedParent = record(generations, 5, protectedParent,
                new SupervisorLifecycleFsm.ShutdownWithChildEpochs(
                        1, Map.of(6L, 2L)));
        final var capturedParent = protectedParent;
        reject(() -> SupervisorLifecycleFsm.reduce(capturedParent,
                new SupervisorLifecycleFsm.ChildFinished(1, 6L, false)),
                "legacy child receipt must not bypass frozen generation");
        reject(() -> SupervisorLifecycleFsm.reduce(capturedParent,
                new SupervisorLifecycleFsm.ShutdownRequested(1, Set.of(6L))),
                "legacy re-freeze must not strip child epoch");
        reject(() -> SupervisorLifecycleFsm.reduce(capturedParent,
                new SupervisorLifecycleFsm.ShutdownWithChildEpochs(1, Map.of(6L, 1L))),
                "re-freeze cannot change pinned epoch");
        protectedParent = record(generations, 5, protectedParent,
                new SupervisorLifecycleFsm.BeforeHookFinished(1, false));
        final var awaitingChild = protectedParent;
        reject(() -> SupervisorLifecycleFsm.reduce(awaitingChild,
                new SupervisorLifecycleFsm.ChildFinishedWithEpoch(1, 6L, 1L, false)),
                "stale epoch receipt accepted");
        oldChild = record(generations, 6, oldChild,
                new SupervisorLifecycleFsm.ShutdownWithChildEpochs(2, Map.of()));
        oldChild = record(generations, 6, oldChild,
                new SupervisorLifecycleFsm.BeforeHookFinished(2, false));
        oldChild = record(generations, 6, oldChild,
                new SupervisorLifecycleFsm.ActorsFinished(2, false));
        oldChild = record(generations, 6, oldChild,
                new SupervisorLifecycleFsm.AfterHookFinished(2, false));
        protectedParent = record(generations, 5, protectedParent,
                new SupervisorLifecycleFsm.ChildFinishedWithEpoch(1, 6L, 2L, false));
        protectedParent = record(generations, 5, protectedParent,
                new SupervisorLifecycleFsm.ActorsFinished(1, false));
        protectedParent = record(generations, 5, protectedParent,
                new SupervisorLifecycleFsm.AfterHookFinished(1, false));
        check(protectedParent.logicallyStopped(), "generation-tagged parent did not drain");
        SupervisorLifecycleTrace.verifyComplete(generations.snapshot());

        var fence = new SupervisorChildGenerationFence(Map.of(2L, 4L));
        fence.requireReceipt(2L, 4L, 4L);
        reject(() -> fence.requireReceipt(2L, 3L, 4L),
                "stale captured epoch");
        reject(() -> fence.requireReceipt(2L, 4L, 5L),
                "live child switched generation");
        reject(() -> fence.requireReceipt(99L, 4L, 4L),
                "unregistered child");
        reject(() -> fence.requireReceipt(2L, 5L, 5L),
                "foreign incarnation");
        check(fence.expectedEpochs().equals(Map.of(2L, 4L)),
                "frozen expected generations mutated");

        var conflict = SupervisorLifecycleFsm.State.initial();
        conflict = SupervisorLifecycleFsm.reduce(conflict,
                new SupervisorLifecycleFsm.ShutdownRequested(1, Set.of(2L)));
        final var locked = conflict;
        reject(() -> SupervisorLifecycleFsm.reduce(locked,
                new SupervisorLifecycleFsm.ShutdownRequested(1, Set.of())),
                "conflicting frozen topology");

        System.out.println("PASS: legacy and epoch-tagged real reducer traces, tamper/gap/overflow, "
                + "cross-node ordering and stale child-generation denial");
    }

    private static SupervisorLifecycleFsm.State record(
            SupervisorLifecycleTrace.Journal journal, long id,
            SupervisorLifecycleFsm.State before, SupervisorLifecycleFsm.Event event) {
        var after = SupervisorLifecycleFsm.reduce(before, event);
        journal.record(id, before, event, after);
        return after;
    }
}
