package dev.oreslang.runtime;

import java.util.HashSet;
import java.util.Map;
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
            ShutdownWithChildEpochs, BeforeHookFinished, ChildFinished,
            ChildFinishedWithEpoch, ChildReclaimed, ChildReclaimedWithEpoch, ActorsFinished,
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
    /** New, generation-authenticated shutdown snapshot; legacy ShutdownRequested
     * is retained for existing models but cannot be mixed with a fenced epoch. */
    public record ShutdownWithChildEpochs(long epoch, Map<Long, Long> children) implements Event {
        public ShutdownWithChildEpochs {
            children = Map.copyOf(Objects.requireNonNull(children, "children"));
            for (var child : children.entrySet()) {
                if (child.getKey() < 1 || child.getValue() < 1) {
                    throw new IllegalArgumentException("invalid child incarnation");
                }
            }
        }
    }
    public record BeforeHookFinished(long epoch, boolean failed) implements Event {}
    public record ChildFinished(long epoch, long childId, boolean failed) implements Event {}
    public record ChildFinishedWithEpoch(
            long epoch, long childId, long childEpoch, boolean failed) implements Event {}
    public record ChildReclaimed(long epoch, long childId) implements Event {}
    public record ChildReclaimedWithEpoch(
            long epoch, long childId, long childEpoch) implements Event {}
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
            Map<Long, Long> expectedChildEpochs,
            Set<Long> completedChildren,
            Set<Long> reclaimedChildren,
            Set<Long> failedChildren,
            boolean preHookDone,
            boolean actorsDone,
            boolean postHookDone,
            boolean preHookFailed,
            boolean actorsFailed,
            boolean postHookFailed,
            boolean physicalReleased,
            boolean failed,
            boolean deadlineExpired) {
        public State {
            Objects.requireNonNull(phase, "phase");
            expectedChildren = Set.copyOf(expectedChildren);
            expectedChildEpochs = Map.copyOf(expectedChildEpochs);
            completedChildren = Set.copyOf(completedChildren);
            reclaimedChildren = Set.copyOf(reclaimedChildren);
            failedChildren = Set.copyOf(failedChildren);
            if (epoch < 1 || revision < 0) throw new IllegalArgumentException("negative identity");
            if (!expectedChildEpochs.isEmpty()
                    && !expectedChildEpochs.keySet().equals(expectedChildren)) {
                throw new IllegalArgumentException("frozen child IDs and epochs mismatch");
            }
            if (!expectedChildren.containsAll(completedChildren)) {
                throw new IllegalArgumentException("completion not in frozen child set");
            }
            if (!completedChildren.containsAll(reclaimedChildren)) {
                throw new IllegalArgumentException("physical child ack before child logical completion");
            }
            if (!completedChildren.containsAll(failedChildren)) {
                throw new IllegalArgumentException("failed child missing completion receipt");
            }
            if ((preHookFailed && !preHookDone)
                    || (actorsFailed && !actorsDone)
                    || (postHookFailed && !postHookDone)) {
                throw new IllegalArgumentException("failure without matching completed receipt");
            }
            if (!failed && (preHookFailed || actorsFailed || postHookFailed
                    || !failedChildren.isEmpty())) {
                throw new IllegalArgumentException("aggregate failure must be sticky");
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
            return new State(Phase.RUNNING, 1L, 0L, Set.of(), Map.of(), Set.of(), Set.of(),
                    Set.of(), false, false, false, false, false, false,
                    false, false, false);
        }
        public boolean admissionsOpen() { return phase == Phase.RUNNING; }
        public boolean restartPermitted() { return phase == Phase.RUNNING; }
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
        Map<Long, Long> generations = s.expectedChildEpochs();
        Set<Long> done = s.completedChildren();
        Set<Long> reclaimed = s.reclaimedChildren();
        Set<Long> failedChildren = s.failedChildren();
        boolean preFailed = s.preHookFailed();
        boolean actorFailed = s.actorsFailed();
        boolean postFailed = s.postHookFailed();
        boolean pre = s.preHookDone();
        boolean actors = s.actorsDone();
        boolean post = s.postHookDone();
        boolean released = s.physicalReleased();
        boolean failed = s.failed();
        boolean timedOut = s.deadlineExpired();
        long epoch = s.epoch();

        if (e instanceof RestartRequested) {
            if (p == Phase.RESTARTING) return s;
            require(p == Phase.RUNNING, "restart requested after shutdown fence");
            // RUNNING has open admissions: any earlier retirement receipt
            // may describe a smaller, already replaced worker snapshot.
            // A restart MUST revalidate old-incarnation actor retirement
            // and physical loans *after* the restart admission fence.
            actors = false;
            actorFailed = false;
            released = false;
            next = Phase.RESTARTING;
        } else if (e instanceof RestartCommitted) {
            require(p == Phase.RESTARTING, "restart completion out of order");
            require(actors && released, "cannot restart before old actor/arena retirement");
            if (epoch == Long.MAX_VALUE) throw new IllegalStateException("epoch overflow");
            epoch++;
            next = Phase.RUNNING;
            expected = Set.of();
            generations = Map.of();
            done = Set.of();
            reclaimed = Set.of();
            failedChildren = Set.of();
            pre = actors = post = released = failed = timedOut = false;
            preFailed = actorFailed = postFailed = false;
        } else if (e instanceof ShutdownWithChildEpochs request) {
            if (p != Phase.RUNNING && p != Phase.RESTARTING) {
                require(expected.equals(request.children().keySet())
                                && generations.equals(request.children()),
                        "conflicting or downgraded shutdown child generations");
                return s;
            }
            expected = request.children().keySet();
            generations = request.children();
            done = Set.of();
            reclaimed = Set.of();
            failedChildren = Set.of();
            // New shutdown admission invalidates pre-fence observations:
            // RUNNING or RESTARTING must not carry a stale physical receipt
            // into the shutdown of a new worker admission snapshot.
            actors = false;
            actorFailed = false;
            released = false;
            next = Phase.BEFORE_HOOK;
        } else if (e instanceof ShutdownRequested request) {
            if (p != Phase.RUNNING && p != Phase.RESTARTING) {
                // Idempotence requires the SAME frozen child identities.
                // A conflicting request would otherwise hide an orphaned
                // child after a concurrent topology modification.
                require(expected.equals(request.children()) && generations.isEmpty(),
                        "conflicting or unauthenticated shutdown child set for frozen epoch");
                return s;
            }
            expected = request.children();
            generations = Map.of();
            done = Set.of();
            reclaimed = Set.of();
            failedChildren = Set.of();
            // New shutdown admission invalidates pre-fence observations:
            // RUNNING or RESTARTING must not carry a stale physical receipt
            // into the shutdown of a new worker admission snapshot.
            actors = false;
            actorFailed = false;
            released = false;
            next = Phase.BEFORE_HOOK;
        } else if (e instanceof BeforeHookFinished result) {
            if (pre) {
                require(preFailed == result.failed(), "conflicting pre-hook completion receipt");
                return s;
            }
            require(p == Phase.BEFORE_HOOK, "pre-hook completed out of order");
            pre = true;
            preFailed = result.failed();
            failed |= result.failed();
            next = expected.isEmpty() ? Phase.WAIT_ACTORS : Phase.WAIT_CHILDREN;
        } else if (e instanceof ChildFinishedWithEpoch child) {
            require(generations.containsKey(child.childId())
                            && generations.get(child.childId()) == child.childEpoch(),
                    "stale, unknown or incorrectly generated child completion");
            if (done.contains(child.childId())) {
                require(failedChildren.contains(child.childId()) == child.failed(),
                        "conflicting generation-bound child completion");
                return s;
            }
            require(p == Phase.WAIT_CHILDREN, "generation child completed out of order");
            HashSet<Long> updated = new HashSet<>(done);
            updated.add(child.childId());
            done = Set.copyOf(updated);
            if (child.failed()) {
                HashSet<Long> failures = new HashSet<>(failedChildren);
                failures.add(child.childId());
                failedChildren = Set.copyOf(failures);
            }
            failed |= child.failed();
            if (done.size() == expected.size()) next = Phase.WAIT_ACTORS;
        } else if (e instanceof ChildFinished child) {
            require(generations.isEmpty(), "legacy child completion cannot bypass epoch fence");
            require(expected.contains(child.childId()), "unknown or unowned child receipt");
            if (done.contains(child.childId())) {
                require(failedChildren.contains(child.childId()) == child.failed(),
                        "conflicting child completion receipt");
                return s;
            }
            require(p == Phase.WAIT_CHILDREN, "child completion before pre-hook");
            HashSet<Long> updated = new HashSet<>(done);
            updated.add(child.childId());
            done = Set.copyOf(updated);
            if (child.failed()) {
                HashSet<Long> failures = new HashSet<>(failedChildren);
                failures.add(child.childId());
                failedChildren = Set.copyOf(failures);
            }
            failed |= child.failed();
            if (done.size() == expected.size()) next = Phase.WAIT_ACTORS;
        } else if (e instanceof ChildReclaimedWithEpoch child) {
            require(generations.containsKey(child.childId())
                            && generations.get(child.childId()) == child.childEpoch(),
                    "stale, unknown or incorrectly generated physical release");
            if (reclaimed.contains(child.childId())) return s;
            require(done.contains(child.childId()), "child release before logical completion");
            HashSet<Long> updated = new HashSet<>(reclaimed);
            updated.add(child.childId());
            reclaimed = Set.copyOf(updated);
            if (p == Phase.STOPPED && released && reclaimed.equals(expected)) {
                next = Phase.RECLAIMED;
            }
        } else if (e instanceof ChildReclaimed child) {
            require(generations.isEmpty(), "legacy child release cannot bypass epoch fence");
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
            if (actors) {
                require(actorFailed == result.failed(), "conflicting actor completion receipt");
                return s;
            }
            // Receipts can arrive while the tree is being fenced, but must
            // always belong to the current epoch.
            actors = true;
            actorFailed = result.failed();
            failed |= result.failed();
            if (p == Phase.WAIT_ACTORS) next = Phase.AFTER_HOOK;
        } else if (e instanceof PhysicalRelease) {
            if (released) return s;
            require(actors, "cannot reclaim memory before actors retire");
            released = true;
            if (p == Phase.STOPPED && reclaimed.equals(expected)) next = Phase.RECLAIMED;
        } else if (e instanceof AfterHookFinished result) {
            if (post) {
                require(postFailed == result.failed(), "conflicting post-hook completion receipt");
                return s;
            }
            require(p == Phase.AFTER_HOOK, "post-hook completed before children and actors");
            post = true;
            postFailed = result.failed();
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
                && generations.equals(s.expectedChildEpochs())
                && done.equals(s.completedChildren())
                && reclaimed.equals(s.reclaimedChildren())
                && failedChildren.equals(s.failedChildren())
                && preFailed == s.preHookFailed()
                && actorFailed == s.actorsFailed()
                && postFailed == s.postHookFailed()
                && pre == s.preHookDone()
                && actors == s.actorsDone() && post == s.postHookDone()
                && released == s.physicalReleased() && failed == s.failed()
                && timedOut == s.deadlineExpired()) return s;

        if (s.revision() == Long.MAX_VALUE) {
            throw new IllegalStateException("supervisor transition revision overflow");
        }
        return new State(next, epoch, s.revision() + 1,
                expected, generations, done, reclaimed, failedChildren,
                pre, actors, post, preFailed, actorFailed, postFailed,
                released, failed, timedOut);
    }

    private static void require(boolean condition, String error) {
        if (!condition) throw new IllegalStateException(error);
    }
}
