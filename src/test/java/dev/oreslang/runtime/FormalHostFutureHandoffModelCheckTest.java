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
        LEGACY_REJECT, CLAIM_TICKET, REPLAY_CLAIM, START_PRODUCER,
        FORGED_PUBLISH, PUBLISH, DUPLICATE_PUBLISH, HOST_SCOPE_EXIT,
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
                if (!running && (!started || (published && hostExited))) retired = true;
            }
            case RETRY_CLOSE -> {
                if (close && !running && (!started || (published && hostExited))) {
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
        if (s.hostExited) assertTrue(s.published,
                "successful handoff must publish before scope exit");
        if (s.cleaned) assertTrue(s.cancelled && !s.producerRunning,
                "physical cleanup cannot be fabricated");
        if (s.contextRetired) assertTrue(s.closeRequested && !s.producerRunning,
                "retirement cannot overlap live guest cleanup");
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
}
