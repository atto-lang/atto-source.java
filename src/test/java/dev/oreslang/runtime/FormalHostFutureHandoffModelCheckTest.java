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

}
