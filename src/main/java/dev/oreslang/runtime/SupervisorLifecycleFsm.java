package dev.oreslang.runtime;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, deterministic extended FSM for one supervisor. All events are
 * immutable data; side effects (hooks, restarts, cancellation, memory release)
 * belong to the owner CONTROL scheduler AFTER a successful transition.
 *
 * <p>Many small FSMs compose hierarchically; never form a global Cartesian
 * product of child states. A child is identified by a stable supervisor ID.
 * Epochs are fencing tokens: stale completion from a previous incarnation
 * cannot mutate the new incarnation.</p>
 *
 * <p>STOPPED means hooks and owned-actor logical completion. Only RECLAIMED
 * asserts both the local loan/arena release receipt and a separate release
 * acknowledgment for every frozen child incarnation. The existing
 * ActorRef.done() is NOT sufficient to emit PhysicalRelease.</p>
 */
public final class SupervisorLifecycleFsm {
    private SupervisorLifecycleFsm() {}

    public enum Phase {
        RUNNING, RESTARTING, BEFORE_HOOK, WAIT_CHILDREN, WAIT_ACTORS,
        AFTER_HOOK, STOPPED, RECLAIMED
    }

    public sealed interface Event permits
            RestartRequested, RestartCommitted, ShutdownRequested,
            BeforeHookFinished, ChildFinished, ChildReclaimed, ActorsFinished,
            AfterHookFinished, PhysicalRelease, DeadlineExpired, CoordinatorFailed {
        long epoch();
    }

    public record RestartRequested(long epoch) implements Event {}
    public record RestartCommitted(long epoch) implements Event {}
    public record ShutdownRequested(long epoch, Set<Long> children) implements Event {
        public ShutdownRequested {
            children = Set.copyOf(Objects.requireNonNull(children, "children"));
            if (children.stream().anyMatch(id -> id == null || id < 1)) {
                throw new IllegalArgumentException("invalid supervisor child identity");
            }
        }
    }
    public record BeforeHookFinished(long epoch, boolean failed) implements Event {}
    public record ChildFinished(long epoch, long childId, boolean failed) implements Event {}
    public record ChildReclaimed(long epoch, long childId) implements Event {}
    public record ActorsFinished(long epoch, boolean failed) implements Event {}
    public record AfterHookFinished(long epoch, boolean failed) implements Event {}
    public record PhysicalRelease(long epoch) implements Event {}
    public record DeadlineExpired(long epoch) implements Event {}
    /** CONTROL loss is a failed outcome, not a fake transition to STOPPED. */
    public record CoordinatorFailed(long epoch) implements Event {}

    public record State(
            Phase phase,
            long epoch,
            long revision,
            Set<Long> expectedChildren,
            Set<Long> completedChildren,
            Set<Long> reclaimedChildren,
            boolean preHookDone,
            boolean actorsDone,
            boolean postHookDone,
            boolean physicalReleased,
            boolean failed,
            boolean deadlineExpired) {
        public State {
            Objects.requireNonNull(phase, "phase");
            expectedChildren = Set.copyOf(expectedChildren);
            completedChildren = Set.copyOf(completedChildren);
            reclaimedChildren = Set.copyOf(reclaimedChildren);
            if (epoch < 1 || revision < 0) throw new IllegalArgumentException("negative identity");
            if (!expectedChildren.containsAll(completedChildren)) {
                throw new IllegalArgumentException("completion not in frozen child set");
            }
            if (!completedChildren.containsAll(reclaimedChildren)) {
                throw new IllegalArgumentException("physical child ack before child logical completion");
            }
            if (phase == Phase.RUNNING
                    && (preHookDone || actorsDone || postHookDone
                        || physicalReleased || deadlineExpired
                        || !expectedChildren.isEmpty()
                        || !completedChildren.isEmpty()
                        || !reclaimedChildren.isEmpty())) {
                throw new IllegalArgumentException(
                        "active supervisor cannot own shutdown/restart receipts");
            }
            if (physicalReleased && !actorsDone) {
                throw new IllegalArgumentException("physical release before actor retirement");
            }
            if (phase == Phase.RECLAIMED
                    && (!physicalReleased || !reclaimedChildren.equals(expectedChildren))) {
                throw new IllegalArgumentException("reclaimed without release receipt");
            }
            if ((phase == Phase.STOPPED || phase == Phase.RECLAIMED)
                    && (!preHookDone || !postHookDone || !actorsDone
                        || !completedChildren.equals(expectedChildren))) {
                throw new IllegalArgumentException("premature terminal supervisor state");
            }
        }
        public static State initial() {
            return new State(Phase.RUNNING, 1L, 0L, Set.of(), Set.of(), Set.of(),
                    false, false, false, false, false, false);
        }
        // CONTROL failure is a fail-closed admission fence, even before
        // a partially failed tree can record ShutdownRequested on this node.
        public boolean admissionsOpen() { return phase == Phase.RUNNING && !failed; }
        public boolean restartPermitted() { return phase == Phase.RUNNING && !failed; }
        public boolean logicallyStopped() {
            return phase == Phase.STOPPED || phase == Phase.RECLAIMED;
        }
        public boolean cleanlyReclaimed() {
            return phase == Phase.RECLAIMED && !failed && !deadlineExpired;
        }
    }

    /**
     * Pure reducer. Duplicate receipts and stale epochs are inert. A future
     * epoch, unknown child, illegal order, or premature reclamation fails
     * closed. No guest hook, actor code, or blocking operation runs here.
     */
    public static State reduce(State s, Event e) {
        Objects.requireNonNull(s, "state");
        Objects.requireNonNull(e, "event");
        if (e.epoch() < s.epoch()) return s;
        if (e.epoch() > s.epoch()) {
            throw new IllegalStateException("future supervisor epoch receipt");
        }

        Phase p = s.phase();
        Phase next = p;
        Set<Long> expected = s.expectedChildren();
        Set<Long> done = s.completedChildren();
        Set<Long> reclaimed = s.reclaimedChildren();
        boolean pre = s.preHookDone();
        boolean actors = s.actorsDone();
        boolean post = s.postHookDone();
        boolean released = s.physicalReleased();
        boolean failed = s.failed();
        boolean timedOut = s.deadlineExpired();
        long epoch = s.epoch();

        if (e instanceof RestartRequested) {
            if (p == Phase.RESTARTING) return s;
            require(p == Phase.RUNNING && !failed,
                    "restart requested after shutdown or coordinator failure fence");
            next = Phase.RESTARTING;
        } else if (e instanceof RestartCommitted) {
            require(p == Phase.RESTARTING, "restart completion out of order");
            require(actors && released, "cannot restart before old actor/arena retirement");
            if (epoch == Long.MAX_VALUE) throw new IllegalStateException("epoch overflow");
            epoch++;
            next = Phase.RUNNING;
            expected = Set.of();
            done = Set.of();
            reclaimed = Set.of();
            pre = actors = post = released = failed = timedOut = false;
        } else if (e instanceof ShutdownRequested request) {
            if (p != Phase.RUNNING && p != Phase.RESTARTING) {
                // An already admitted shutdown is idempotent; never mutate
                // frozen child identity or the original shutdown reason.
                return s;
            }
            expected = request.children();
            done = Set.of();
            reclaimed = Set.of();
            next = Phase.BEFORE_HOOK;
        } else if (e instanceof BeforeHookFinished result) {
            if (pre) return s;
            require(p == Phase.BEFORE_HOOK, "pre-hook completed out of order");
            pre = true;
            failed |= result.failed();
            next = expected.isEmpty() ? Phase.WAIT_ACTORS : Phase.WAIT_CHILDREN;
        } else if (e instanceof ChildFinished child) {
            require(expected.contains(child.childId()), "unknown or unowned child receipt");
            if (done.contains(child.childId())) return s;
            require(p == Phase.WAIT_CHILDREN, "child completion before pre-hook");
            HashSet<Long> updated = new HashSet<>(done);
            updated.add(child.childId());
            done = Set.copyOf(updated);
            failed |= child.failed();
            if (done.size() == expected.size()) next = Phase.WAIT_ACTORS;
        } else if (e instanceof ChildReclaimed child) {
            require(expected.contains(child.childId()), "unknown or unowned child release");
            if (reclaimed.contains(child.childId())) return s;
            require(done.contains(child.childId()), "child release before child retirement");
            HashSet<Long> updated = new HashSet<>(reclaimed);
            updated.add(child.childId());
            reclaimed = Set.copyOf(updated);
            if (p == Phase.STOPPED && released && reclaimed.equals(expected)) {
                next = Phase.RECLAIMED;
            }
        } else if (e instanceof ActorsFinished result) {
            if (actors) return s;
            // An actor retirement receipt belongs to an admitted shutdown
            // or restart epoch. A spoofed/pre-fence completion while RUNNING
            // would incorrectly satisfy a future shutdown's release barrier.
            require(p != Phase.RUNNING,
                    "actor retirement without admitted shutdown or restart");
            // Receipts can arrive while the tree is being fenced, but must
            // always belong to the current epoch.
            actors = true;
            failed |= result.failed();
            if (p == Phase.WAIT_ACTORS) next = Phase.AFTER_HOOK;
        } else if (e instanceof PhysicalRelease) {
            if (released) return s;
            require(p != Phase.RUNNING,
                    "physical release without admitted shutdown or restart");
            require(actors, "cannot reclaim memory before actors retire");
            released = true;
            if (p == Phase.STOPPED && reclaimed.equals(expected)) next = Phase.RECLAIMED;
        } else if (e instanceof AfterHookFinished result) {
            if (post) return s;
            require(p == Phase.AFTER_HOOK, "post-hook completed before children and actors");
            post = true;
            failed |= result.failed();
            next = released && reclaimed.equals(expected) ? Phase.RECLAIMED : Phase.STOPPED;
        } else if (e instanceof CoordinatorFailed) {
            if (failed) return s;
            failed = true;
        } else if (e instanceof DeadlineExpired) {
            if (timedOut || p == Phase.RECLAIMED) return s;
            require(p != Phase.RUNNING && p != Phase.RESTARTING,
                    "timeout without admitted shutdown");
            timedOut = true;
        } else {
            throw new IllegalStateException("unrecognized lifecycle event");
        }

        // An actor may have retired before the pre-hook or last child ack.
        // Never require a second, fabricated ActorsFinished receipt merely
        // to advance the shutdown automaton.
        if (next == Phase.WAIT_ACTORS && actors) next = Phase.AFTER_HOOK;

        if (s.epoch() == epoch && next == p && expected.equals(s.expectedChildren())
                && done.equals(s.completedChildren())
                && reclaimed.equals(s.reclaimedChildren()) && pre == s.preHookDone()
                && actors == s.actorsDone() && post == s.postHookDone()
                && released == s.physicalReleased() && failed == s.failed()
                && timedOut == s.deadlineExpired()) return s;

        if (s.revision() == Long.MAX_VALUE) {
            throw new IllegalStateException("supervisor transition revision overflow");
        }
        return new State(next, epoch, s.revision() + 1,
                expected, done, reclaimed, pre, actors, post, released, failed, timedOut);
    }

    private static void require(boolean condition, String error) {
        if (!condition) throw new IllegalStateException(error);
    }
}
