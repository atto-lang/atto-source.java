package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounded abstract safety checker for the host Future boundary.
 * This is NOT a machine-checked refinement of Truffle entry/leave.
 * Real guest carrier tests must still establish implementation behavior.
 */
final class FormalHostFutureHandoffModelCheckTest {
    private enum Event {
        LEGACY_REJECT, CLAIM_TICKET, REPLAY_CLAIM, ABORT_BEFORE_DISPATCH,
        START_PRODUCER, FORGED_PUBLISH, PUBLISH, DUPLICATE_PUBLISH,
        REJECT_PUBLICATION, HOST_SCOPE_EXIT,
        CANCEL, CLEANUP_FINISH, REQUEST_CLOSE, RETRY_CLOSE
    }

    private record State(
            boolean legacyRejected, boolean claimed, boolean producerStarted,
            boolean producerRunning, boolean published, boolean hostExited,
            boolean cancelled, boolean cleaned, boolean closeRequested,
            boolean contextRetired, int claims, int publications) {
        static State initial() {
            return new State(false, false, false, false, false, false,
                    false, false, false, false, 0, 0);
        }
    }

    private static State step(State s, Event event) {
        boolean legacy = s.legacyRejected, claim = s.claimed;
        boolean started = s.producerStarted, running = s.producerRunning;
        boolean published = s.published, hostExited = s.hostExited;
        boolean cancelled = s.cancelled, cleaned = s.cleaned;
        boolean close = s.closeRequested, retired = s.contextRetired;
        int claims = s.claims, pubs = s.publications;
        switch (event) {
            case LEGACY_REJECT -> legacy = true;
            case CLAIM_TICKET -> {
                if (!claim && !close && !retired) { claim = true; claims++; }
            }
            case START_PRODUCER -> {
                if (claim && !started && !hostExited && !close) {
                    started = true;
                    running = true;
                }
            }
            case PUBLISH -> {
                if (claim && started && !published && !hostExited) {
                    published = true;
                    pubs++;
                }
            }
            case ABORT_BEFORE_DISPATCH -> {
                if (claim && !started) hostExited = true;
            }
            case REJECT_PUBLICATION -> {
                if (claim && started && !published && !hostExited) {
                    // The producer exists, but the host cannot receive it:
                    // cancellation must be requested before capture retires.
                    cancelled = true;
                    hostExited = true;
                }
            }
            case HOST_SCOPE_EXIT -> {
                if (published) hostExited = true;
            }
            case CANCEL -> {
                if (published && running) cancelled = true;
            }
            case CLEANUP_FINISH -> {
                if (cancelled && running) { running = false; cleaned = true; }
            }
            case REQUEST_CLOSE -> {
                close = true;
                if (!running && (!claim || hostExited)) retired = true;
            }
            case RETRY_CLOSE -> {
                if (close && !running && (!claim || hostExited)) {
                    retired = true;
                }
            }
            case REPLAY_CLAIM, FORGED_PUBLISH, DUPLICATE_PUBLISH -> {
                // Authorization failures may not advance guest state.
            }
        }
        return new State(legacy, claim, started, running, published, hostExited,
                cancelled, cleaned, close, retired, claims, pubs);
    }

    private static void invariant(State s) {
        assertTrue(s.claims >= 0 && s.claims <= 1, "claim is not single-use");
        assertTrue(s.publications >= 0 && s.publications <= 1,
                "publication is not single-use");
        if (s.producerStarted) assertTrue(s.claimed,
                "producer needs a claimed host receiver ticket");
        if (s.published) assertTrue(s.claimed && s.producerStarted,
                "publication needs both claim and producer");
        if (s.hostExited && s.producerStarted && !s.published) {
            assertTrue(s.cancelled,
                    "failed publication cannot abandon a running producer");
        }
        if (s.cleaned) assertTrue(s.cancelled && !s.producerRunning,
                "physical cleanup cannot be fabricated");
        if (s.contextRetired) {
            assertTrue(s.closeRequested && !s.producerRunning,
                    "retirement cannot overlap live guest cleanup");
            assertTrue(!s.claimed || s.hostExited,
                    "retirement cannot revoke an active host capture");
        }
    }

    @Test
    void exhaustiveBoundedInterleavingsProtectProducerAndCleanup() {
        ArrayDeque<State> queue = new ArrayDeque<>();
        Set<State> seen = new HashSet<>();
        State initial = State.initial();
        queue.add(initial);
        seen.add(initial);
        boolean sawLegacy = false, sawPublication = false;
        boolean sawPendingClose = false, sawFinalRetirement = false;
        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            invariant(state);
            sawLegacy |= state.legacyRejected;
            sawPublication |= state.published;
            sawPendingClose |= state.closeRequested && state.producerRunning
                    && !state.contextRetired;
            sawFinalRetirement |= state.cleaned && state.contextRetired;
            for (Event event : Event.values()) {
                State next = step(state, event);
                invariant(next);
                if (event == Event.REPLAY_CLAIM || event == Event.FORGED_PUBLISH
                        || event == Event.DUPLICATE_PUBLISH) {
                    assertEquals(state, next,
                            "replay or forgery changed runtime ownership");
                }
                if (event == Event.LEGACY_REJECT) {
                    assertEquals(state.producerStarted, next.producerStarted,
                            "a rejected legacy call created a producer");
                }
                if (seen.add(next)) queue.addLast(next);
            }
        }
        assertTrue(seen.size() >= 20, "unexpectedly small model: " + seen.size());
        assertTrue(sawLegacy && sawPublication && sawPendingClose && sawFinalRetirement);
    }

    @Test
    void inFlightHostClaimMustExitBeforeContextCanPhysicallyRetire() {
        State claim = step(State.initial(), Event.CLAIM_TICKET);
        State pendingClose = step(claim, Event.REQUEST_CLOSE);
        assertFalse(pendingClose.contextRetired,
                "an unreturned host bridge ticket still owns context admission");
        State aborted = step(pendingClose, Event.ABORT_BEFORE_DISPATCH);
        assertFalse(aborted.contextRetired);
        State retired = step(aborted, Event.RETRY_CLOSE);
        assertTrue(retired.contextRetired);
        invariant(retired);
    }

    @Test
    void rejectedPublicationCancelsProducerBeforeClosingCapture() {
        State s = State.initial();
        s = step(s, Event.CLAIM_TICKET);
        s = step(s, Event.START_PRODUCER);
        s = step(s, Event.REJECT_PUBLICATION);
        assertTrue(s.cancelled && s.hostExited);
        assertFalse(s.published);
        s = step(s, Event.REQUEST_CLOSE);
        assertFalse(s.contextRetired);
        s = step(s, Event.CLEANUP_FINISH);
        s = step(s, Event.RETRY_CLOSE);
        assertTrue(s.contextRetired);
        invariant(s);
    }

    @Test
    void incompleteCancellationCleanupBlocksContextRetirementUntilRetry() {
        State s = State.initial();
        for (Event event : new Event[]{Event.CLAIM_TICKET, Event.START_PRODUCER,
                Event.PUBLISH, Event.HOST_SCOPE_EXIT, Event.CANCEL, Event.REQUEST_CLOSE}) {
            s = step(s, event);
        }
        assertTrue(s.closeRequested);
        assertTrue(s.producerRunning);
        assertFalse(s.contextRetired);
        s = step(s, Event.CLEANUP_FINISH);
        assertFalse(s.contextRetired, "cleanup must not bypass explicit close retry");
        s = step(s, Event.RETRY_CLOSE);
        assertTrue(s.contextRetired);
        invariant(s);
    }
    private enum PhysicalEvent {
        HOST_BEGIN, ROOT_CLAIM, FUTURE_SETTLED, ROOT_DONE, HOST_EXIT, SEAL, CLOSE_CHECK
    }

    private record PhysicalState(
            boolean host, boolean root, boolean futureSettled,
            boolean sealed, boolean retired) {
        static PhysicalState initial() {
            return new PhysicalState(false, false, false, false, false);
        }
    }

    private static PhysicalState physicalStep(PhysicalState s, PhysicalEvent event) {
        boolean host = s.host, root = s.root, settled = s.futureSettled;
        boolean sealed = s.sealed, retired = s.retired;
        switch (event) {
            case HOST_BEGIN -> {
                if (!sealed && !host && !root) host = true;
            }
            case ROOT_CLAIM -> {
                if (!host && !root && !retired) {
                    root = true;
                    settled = false;
                }
            }
            case FUTURE_SETTLED -> {
                // Result publication may precede guest-turn executor exit.
                // It must NOT surrender the physical root-entry claim.
                if (root) settled = true;
            }
            case ROOT_DONE -> root = false;
            case HOST_EXIT -> host = false;
            case SEAL -> sealed = true;
            case CLOSE_CHECK -> {
                if (sealed && !host && !root) retired = true;
            }
        }
        return new PhysicalState(host, root, settled, sealed, retired);
    }

    private static void physicalInvariant(PhysicalState state) {
        assertFalse(state.host && state.root,
                "host entry and a physical root turn overlap");
        assertFalse(state.retired && (state.host || state.root),
                "context retired while physical guest entry remained active");
    }

    @Test
    void boundedHostAndRootPhysicalEntryAreMutuallyExclusive() {
        Set<PhysicalState> visited = new HashSet<>();
        ArrayDeque<PhysicalState> frontier = new ArrayDeque<>();
        PhysicalState start = PhysicalState.initial();
        visited.add(start);
        frontier.add(start);
        boolean sawHost = false, sawRoot = false, sawRetired = false;
        while (!frontier.isEmpty()) {
            PhysicalState state = frontier.removeFirst();
            physicalInvariant(state);
            sawHost |= state.host;
            sawRoot |= state.root;
            sawRetired |= state.retired;
            for (PhysicalEvent event : PhysicalEvent.values()) {
                PhysicalState next = physicalStep(state, event);
                physicalInvariant(next);
                if (visited.add(next)) frontier.addLast(next);
            }
        }
        assertTrue(sawHost && sawRoot && sawRetired);
        assertTrue(visited.size() >= 5);
    }

    @Test
    void publicFutureSettlementIsNotPhysicalRootTurnRelease() {
        PhysicalState state = physicalStep(PhysicalState.initial(),
                PhysicalEvent.ROOT_CLAIM);
        assertTrue(state.root);
        state = physicalStep(state, PhysicalEvent.FUTURE_SETTLED);
        assertTrue(state.futureSettled);
        assertTrue(state.root, "Future settlement cannot imply guest exit");
        state = physicalStep(state, PhysicalEvent.HOST_BEGIN);
        assertFalse(state.host, "host entry must be rejected until physical root exit");
        state = physicalStep(state, PhysicalEvent.SEAL);
        state = physicalStep(state, PhysicalEvent.CLOSE_CHECK);
        assertFalse(state.retired, "sealing is not a physical exit receipt");
        state = physicalStep(state, PhysicalEvent.ROOT_DONE);
        state = physicalStep(state, PhysicalEvent.CLOSE_CHECK);
        assertTrue(state.retired);
        physicalInvariant(state);
    }

    @Test
    void unsafeHostBeginAfterRootPhysicalClaimViolatesInvariant() {
        PhysicalState invalid = new PhysicalState(true, true, true, false, false);
        assertThrows(AssertionError.class, () -> physicalInvariant(invalid));
    }


    /**
     * Abstraction of HostHandoffLease.close()/RootHandoffGate.release().
     * An executor rejection can leave queued guest work after the unique
     * host scope has been released; a repeated owner close may retry draining,
     * but must never release the lease or dispatch a guest twice.
     */
    private enum LeaseRetryEvent {
        HOST_BEGIN, QUEUE_ROOT, FOREIGN_CLOSE, OWNER_RELEASE_REJECT,
        OWNER_RELEASE_ACCEPT, RETRY_REJECT, RETRY_ACCEPT, SEAL
    }
    private record LeaseRetryState(
            boolean host, boolean released, boolean queued,
            boolean dispatched, boolean sealed, int releases, int guestStarts) {
        static LeaseRetryState initial() {
            return new LeaseRetryState(false, false, false, false, false, 0, 0);
        }
    }
    private static LeaseRetryState leaseRetryStep(
            LeaseRetryState s, LeaseRetryEvent event) {
        boolean host = s.host, released = s.released, queued = s.queued;
        boolean dispatched = s.dispatched, sealed = s.sealed;
        int releases = s.releases, starts = s.guestStarts;
        switch (event) {
            case HOST_BEGIN -> {
                if (!sealed && !host && !released) host = true;
            }
            case QUEUE_ROOT -> {
                if (host && !queued && !dispatched) queued = true;
            }
            case OWNER_RELEASE_REJECT -> {
                if (host && queued) {
                    host = false;
                    released = true;
                    releases++;
                }
            }
            case OWNER_RELEASE_ACCEPT -> {
                if (host) {
                    host = false;
                    released = true;
                    releases++;
                    if (queued) {
                        queued = false;
                        dispatched = true;
                        starts++;
                    }
                }
            }
            case RETRY_REJECT -> {
                // No release count change, and the queued owner remains held.
            }
            case RETRY_ACCEPT -> {
                if (released && !host && queued) {
                    queued = false;
                    dispatched = true;
                    starts++;
                }
            }
            case SEAL -> sealed = true;
            case FOREIGN_CLOSE -> {
                // Foreign threads have no authority to release host entry.
            }
        }
        return new LeaseRetryState(
                host, released, queued, dispatched, sealed, releases, starts);
    }
    private static void leaseRetryInvariant(LeaseRetryState s) {
        assertTrue(s.releases >= 0 && s.releases <= 1,
                "host physical entry lease released multiple times");
        assertTrue(s.guestStarts >= 0 && s.guestStarts <= 1,
                "retries executed guest code more than once");
        assertFalse(s.host && s.dispatched,
                "root guest dispatched under live host entry");
        assertFalse(s.queued && s.dispatched,
                "root task simultaneously queued and running");
        if (s.dispatched) assertTrue(s.released,
                "root guest dispatched before host physically exited");
    }

    @Test
    void boundedHostLeaseRetryNeverDuplicatesReleaseOrGuestExecution() {
        Set<LeaseRetryState> visited = new HashSet<>();
        ArrayDeque<LeaseRetryState> work = new ArrayDeque<>();
        LeaseRetryState initial = LeaseRetryState.initial();
        visited.add(initial);
        work.addLast(initial);
        boolean sawRejected = false, sawRetry = false, sawSeal = false;
        while (!work.isEmpty()) {
            LeaseRetryState state = work.removeFirst();
            leaseRetryInvariant(state);
            sawRejected |= state.released && state.queued;
            sawRetry |= state.dispatched && state.guestStarts == 1;
            sawSeal |= state.sealed;
            for (LeaseRetryEvent event : LeaseRetryEvent.values()) {
                LeaseRetryState next = leaseRetryStep(state, event);
                leaseRetryInvariant(next);
                if (event == LeaseRetryEvent.FOREIGN_CLOSE) {
                    assertEquals(state, next,
                            "foreign host release changed physical admission");
                }
                if (state.released && event == LeaseRetryEvent.RETRY_REJECT) {
                    assertEquals(state, next,
                            "rejected retry discarded its physical reservation");
                }
                if (visited.add(next)) work.addLast(next);
            }
        }
        assertTrue(sawRejected && sawRetry && sawSeal && visited.size() >= 5);
    }

    @Test
    void unsafeRetryDispatchWhileHostOwnsEntryViolatesInvariant() {
        LeaseRetryState unsafe = new LeaseRetryState(
                true, false, false, true, false, 0, 1);
        assertThrows(AssertionError.class, () -> leaseRetryInvariant(unsafe));
    }

    /**
     * Bounded executor model: downstream submission is not physical entry.
     * An inline executor can run and then throw; only CAS-revoked, unstarted
     * runnables may return to the admission FIFO. An uncertain in-flight exit
     * poisons admission instead of manufacturing successful DONE.
     */
    private enum ExecutorEvent {
        SUBMIT, EXECUTE, REPORT_REJECTION, INLINE_EXECUTOR_THROWS,
        PHYSICAL_DONE, UNPROVEN_EXIT, RETRY
    }
    private record ExecutorState(boolean queued, boolean submitted,
            boolean started, boolean done, boolean poisoned, int guestStarts) {
        static ExecutorState initial() {
            return new ExecutorState(true, false, false, false, false, 0);
        }
    }
    private static ExecutorState executorStep(ExecutorState s, ExecutorEvent e) {
        boolean queued = s.queued, submitted = s.submitted;
        boolean started = s.started, done = s.done, poisoned = s.poisoned;
        int starts = s.guestStarts;
        switch (e) {
            case SUBMIT, RETRY -> {
                if (queued && !poisoned) {
                    queued = false;
                    submitted = true;
                }
            }
            case EXECUTE -> {
                if (submitted && !started && !poisoned) {
                    started = true;
                    starts++;
                }
            }
            case REPORT_REJECTION, INLINE_EXECUTOR_THROWS -> {
                if (submitted && !started) {
                    // The stale executor runnable has been CAS-revoked.
                    submitted = false;
                    queued = true;
                } else if (submitted && started && !done) {
                    // A throw racing a live guest turn is NOT an exit receipt.
                    poisoned = true;
                }
                // If the guest already completed, its effects are committed:
                // an executor's later throw must never requeue it.
            }
            case PHYSICAL_DONE -> {
                if (started) done = true;
            }
            case UNPROVEN_EXIT -> {
                if (started && !done) poisoned = true;
            }
        }
        return new ExecutorState(queued, submitted, started, done, poisoned, starts);
    }
    private static void executorInvariant(ExecutorState s) {
        assertTrue(s.guestStarts >= 0 && s.guestStarts <= 1,
                "physical guest body executed more than once");
        assertFalse(s.started && s.queued,
                "already-executed guest body requeued after executor failure");
        if (s.poisoned) assertFalse(s.queued,
                "unknown physical exit released a reusable guest turn");
    }
    @Test
    void boundedInlineExecutorFailureCannotReplayPhysicalGuestEffects() {
        Set<ExecutorState> visited = new HashSet<>();
        ArrayDeque<ExecutorState> frontier = new ArrayDeque<>();
        visited.add(ExecutorState.initial());
        frontier.addLast(ExecutorState.initial());
        boolean sawInlineAfterDone = false, sawRetry = false, sawPoison = false;
        while (!frontier.isEmpty()) {
            ExecutorState s = frontier.removeFirst();
            executorInvariant(s);
            sawPoison |= s.poisoned;
            sawRetry |= s.submitted && !s.started;
            for (ExecutorEvent event : ExecutorEvent.values()) {
                ExecutorState next = executorStep(s, event);
                executorInvariant(next);
                if (event == ExecutorEvent.INLINE_EXECUTOR_THROWS && s.done) {
                    sawInlineAfterDone = true;
                    assertEquals(s, next,
                            "executor failed after physical completion: never replay");
                }
                if (s.poisoned && event == ExecutorEvent.RETRY) {
                    assertEquals(s, next, "poisoned entry cannot be retried");
                }
                if (visited.add(next)) frontier.addLast(next);
            }
        }
        assertTrue(sawInlineAfterDone && sawRetry && sawPoison);
    }
    @Test
    void unsafeInlineFailureReplayViolatesExecutorOwnershipInvariant() {
        ExecutorState unsafe = new ExecutorState(true, false,
                true, true, false, 2);
        assertThrows(AssertionError.class, () -> executorInvariant(unsafe));
    }

    /** An executor may throw before, during or after physical guest execution. */
    private enum ExecutorEvent {
        START, EXIT_NORMAL, EXECUTOR_THROWS, RETRY, RETIRE
    }

    private enum ExecutorPhase { QUEUED, RUNNING, EXITED, REVOKED, UNCERTAIN }

    private record ExecutorState(ExecutorPhase phase, boolean failed,
            boolean poisoned, boolean retired, int starts, int retries) {
        static ExecutorState initial() {
            return new ExecutorState(ExecutorPhase.QUEUED, false, false, false, 0, 0);
        }
    }

    private static ExecutorState executorStep(ExecutorState s, ExecutorEvent e) {
        ExecutorPhase phase = s.phase;
        boolean failed = s.failed, poisoned = s.poisoned, retired = s.retired;
        int starts = s.starts, retries = s.retries;
        switch (e) {
            case START -> {
                if (phase == ExecutorPhase.QUEUED && !poisoned && !retired) {
                    phase = ExecutorPhase.RUNNING;
                    starts++;
                }
            }
            case EXIT_NORMAL -> {
                if (phase == ExecutorPhase.RUNNING) phase = ExecutorPhase.EXITED;
            }
            case EXECUTOR_THROWS -> {
                if (phase == ExecutorPhase.QUEUED) {
                    // Atomic CAS revokes execution before its first entry.
                    phase = ExecutorPhase.REVOKED;
                    failed = true;
                } else if (phase == ExecutorPhase.RUNNING) {
                    // The executor reported failure while guest entry was
                    // physically active; a later result is not a DONE proof.
                    phase = ExecutorPhase.UNCERTAIN;
                    failed = true;
                    poisoned = true;
                } else if (phase == ExecutorPhase.EXITED) {
                    // The guarded runnable returned normally before executor
                    // failure. No replay, but its physical exit is proven.
                    failed = true;
                }
            }
            case RETRY -> {
                if (phase == ExecutorPhase.REVOKED && retries == 0) {
                    phase = ExecutorPhase.QUEUED;
                    retries++;
                }
            }
            case RETIRE -> {
                if (phase == ExecutorPhase.EXITED && !poisoned) retired = true;
            }
        }
        return new ExecutorState(phase, failed, poisoned, retired, starts, retries);
    }

    private static void executorInvariant(ExecutorState s) {
        assertTrue(s.starts >= 0 && s.starts <= 1,
                "replayed a physically entered guest turn");
        assertTrue(s.retries >= 0 && s.retries <= 1);
        if (s.phase == ExecutorPhase.UNCERTAIN) {
            assertTrue(s.poisoned, "in-flight failure omitted physical-exit quarantine");
        }
        if (s.poisoned) {
            assertTrue(s.failed && !s.retired,
                    "unproven physical exit was treated as retirement authority");
        }
        if (s.retired) {
            assertEquals(ExecutorPhase.EXITED, s.phase,
                    "a result or executor rejection is not a physical exit");
        }
    }

    @Test
    void boundedExecutorRejectionPreservesSingleEntryAndUnknownExitQuarantine() {
        var seen = new HashSet<ExecutorState>();
        var work = new ArrayDeque<ExecutorState>();
        ExecutorState initial = ExecutorState.initial();
        work.add(initial);
        seen.add(initial);
        boolean sawRevoked = false, sawUncertain = false, sawAfterExit = false;
        while (!work.isEmpty()) {
            ExecutorState s = work.removeFirst();
            executorInvariant(s);
            sawRevoked |= s.phase == ExecutorPhase.REVOKED;
            sawUncertain |= s.phase == ExecutorPhase.UNCERTAIN && s.poisoned;
            sawAfterExit |= s.phase == ExecutorPhase.EXITED && s.failed;
            for (ExecutorEvent event : ExecutorEvent.values()) {
                ExecutorState next = executorStep(s, event);
                executorInvariant(next);
                if (seen.add(next)) work.addLast(next);
            }
        }
        assertTrue(seen.size() >= 6 && sawRevoked && sawUncertain && sawAfterExit);
    }

    @Test
    void unsafeRetirementOfUncertainRootExitViolatesExecutorInvariant() {
        ExecutorState unsafe = new ExecutorState(
                ExecutorPhase.UNCERTAIN, true, false, true, 1, 0);
        assertThrows(AssertionError.class, () -> executorInvariant(unsafe));
    }

}
