package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounded, exhaustive safety model for one cancelled guest cleanup with actor,
 * HTTP transport and native-I/O physical reservations.
 *
 * An abstract refinement gate, NOT a proof of arbitrary JVM interleavings.
 * The concrete OresVM, OresScheduler, AsyncNativeIo and HTTP race tests check
 * whether the runtime respects this model at the corresponding boundaries.
 */
final class FormalContextRetirementModelCheckTest {
    private enum Event {
        CLOSE, CLAIM_FINALLY, DELIVER_STALE_CALLBACK, DELIVER_VALID_CALLBACK,
        DUPLICATE_VALID_CALLBACK, EXIT_ACTOR, EXIT_NATIVE_IO, EXIT_HTTP
    }

    private record State(
            boolean sealed,
            boolean cancelled,
            boolean guestActive,
            boolean actorActive,
            boolean nativeIoActive,
            boolean httpActive,
            boolean retired,
            boolean closeFailed,
            int cleanupClaims,
            int acceptedResumes) {

        private static State initial() {
            return new State(false, false, true, true, true, true,
                    false, false, 0, 0);
        }

        private boolean quiescent() {
            return !guestActive && !actorActive && !nativeIoActive && !httpActive;
        }
    }

    private static State step(State s, Event event) {
        boolean sealed = s.sealed;
        boolean cancelled = s.cancelled;
        boolean guest = s.guestActive;
        boolean actor = s.actorActive;
        boolean io = s.nativeIoActive;
        boolean http = s.httpActive;
        boolean retired = s.retired;
        boolean failed = s.closeFailed;
        int claimed = s.cleanupClaims;
        int resumed = s.acceptedResumes;

        switch (event) {
            case CLOSE -> {
                sealed = true;
                cancelled = true;
                // Repeated close may succeed only after all physical owners
                // actually exit. No elapsed deadline manufactures an exit.
                if (!guest && !actor && !io && !http) retired = true;
                else failed = true;
            }
            case CLAIM_FINALLY -> {
                if (cancelled && guest && claimed == 0) claimed = 1;
            }
            case DELIVER_STALE_CALLBACK -> {
                // A prior await generation must have no authority over this
                // task's currently suspended finalizer.
            }
            case DELIVER_VALID_CALLBACK -> {
                if (claimed == 1 && guest && resumed == 0) {
                    resumed = 1;
                    guest = false;
                }
            }
            case DUPLICATE_VALID_CALLBACK -> {
                // An already-delivered completion is fenced out.
            }
            case EXIT_ACTOR -> {
                if (!guest) actor = false;
            }
            case EXIT_NATIVE_IO -> io = false;
            case EXIT_HTTP -> http = false;
        }

        return new State(sealed, cancelled, guest, actor, io, http,
                retired, failed, claimed, resumed);
    }

    private static void invariant(State state) {
        assertTrue(state.cleanupClaims >= 0 && state.cleanupClaims <= 1,
                "a lexical cleanup scope may be claimed only once");
        assertTrue(state.acceptedResumes >= 0 && state.acceptedResumes <= 1,
                "duplicate or stale awaits cannot resume another cleanup");
        if (!state.guestActive) {
            assertEquals(1, state.cleanupClaims,
                    "guest completion cannot skip finalization");
            assertEquals(1, state.acceptedResumes,
                    "guest completion must be driven by an authorized await");
        }
        if (!state.actorActive) {
            assertFalse(state.guestActive,
                    "actor physical retirement cannot precede guest cleanup");
        }
        if (state.retired) {
            assertTrue(state.sealed, "retirement requires sealed admission");
            assertTrue(state.quiescent(),
                    "physical reclamation cannot precede all owner exits");
            assertEquals(1, state.acceptedResumes);
        }
    }

    @Test
    void exhaustiveCancellationShutdownInterleavingsRetainEveryLiveOwner() {
        ArrayDeque<State> frontier = new ArrayDeque<>();
        Set<State> visited = new HashSet<>();
        State initial = State.initial();
        frontier.add(initial);
        visited.add(initial);
        boolean observedFailedThenRetired = false;
        boolean observedOldCallbackIgnored = false;
        while (!frontier.isEmpty()) {
            State state = frontier.removeFirst();
            invariant(state);
            if (state.closeFailed && state.retired) {
                observedFailedThenRetired = true;
            }
            for (Event event : Event.values()) {
                State next = step(state, event);
                invariant(next);
                if (event == Event.DELIVER_STALE_CALLBACK) {
                    assertEquals(state, next, "a stale await cannot mutate execution ownership");
                    observedOldCallbackIgnored = true;
                }
                if (visited.add(next)) frontier.addLast(next);
            }
        }
        assertTrue(visited.size() >= 20,
                "model exploration was unexpectedly reduced: " + visited.size());
        assertTrue(observedFailedThenRetired,
                "must explore a timed-out close followed by valid retry");
        assertTrue(observedOldCallbackIgnored);
    }

    @Test
    void cancellationAloneCannotReleaseAnOutstandingNativeOrHttpLoan() {
        State s = State.initial();
        s = step(s, Event.CLOSE);
        s = step(s, Event.CLAIM_FINALLY);
        s = step(s, Event.DELIVER_VALID_CALLBACK);
        s = step(s, Event.EXIT_ACTOR);
        assertFalse(s.quiescent(), "native/HTTP owners remain after guest cleanup");
        s = step(s, Event.CLOSE);
        assertFalse(s.retired);
        s = step(s, Event.EXIT_NATIVE_IO);
        assertFalse(s.quiescent(), "HTTP still owns a transport reservation");
        s = step(s, Event.EXIT_HTTP);
        assertTrue(step(s, Event.CLOSE).retired);
    }

    private enum HttpEvent {
        STOP_LISTENER, REQUEST_ABORT, ABORT_WORKER_CLOSES_SOCKET, ACTOR_EXIT,
        MARK_ABORT_REJECTED, SHUTDOWN_IO, PHYSICAL_IO_EXIT,
        RECLAIM_REJECTED_AFTER_IO_EXIT, RELEASE_EXCHANGE, CLOSE_RETRY
    }

    private record HttpState(
            boolean accepting,
            boolean tracked,
            boolean socketClosed,
            boolean actorRunning,
            boolean abortQueued,
            boolean abortRejected,
            boolean ioOpen,
            boolean ioExited,
            boolean retired) {

        static HttpState initiallyActive() {
            return new HttpState(true, true, false, true, false, false,
                    true, false, false);
        }
    }

    private static HttpState httpStep(HttpState s, HttpEvent e) {
        boolean accepting = s.accepting, tracked = s.tracked;
        boolean socketClosed = s.socketClosed, actor = s.actorRunning;
        boolean queued = s.abortQueued, rejected = s.abortRejected;
        boolean ioOpen = s.ioOpen, ioExited = s.ioExited, retired = s.retired;

        switch (e) {
            case STOP_LISTENER -> accepting = false;
            case REQUEST_ABORT -> {
                if (!accepting && tracked && !socketClosed && !queued && !rejected) {
                    if (ioOpen) queued = true;
                    else rejected = true;
                }
            }
            case ABORT_WORKER_CLOSES_SOCKET -> {
                if (queued) {
                    socketClosed = true;
                    queued = false;
                }
            }
            case ACTOR_EXIT -> actor = false;
            case MARK_ABORT_REJECTED -> {
                // A callback arriving after executor shutdown may only
                // record pending socket ownership; it cannot release it.
                if (!ioOpen && !socketClosed && tracked) rejected = true;
            }
            case SHUTDOWN_IO -> {
                // The corrected close path keeps its executor admitting
                // already-owned cleanup until the exchange is released.
                if (!tracked) ioOpen = false;
            }
            case PHYSICAL_IO_EXIT -> {
                if (!ioOpen && !queued) ioExited = true;
            }
            case RECLAIM_REJECTED_AFTER_IO_EXIT -> {
                if (rejected && ioExited) {
                    socketClosed = true;
                    rejected = false;
                }
            }
            case RELEASE_EXCHANGE -> {
                if (socketClosed && !actor && !queued && !rejected) tracked = false;
            }
            case CLOSE_RETRY -> {
                if (!accepting && !tracked && ioExited && !rejected) retired = true;
            }
        }
        return new HttpState(accepting, tracked, socketClosed, actor, queued,
                rejected, ioOpen, ioExited, retired);
    }

    @Test
    void exhaustiveHttpShutdownRetainsUnreleasedExchangesAcrossRetry() {
        java.util.ArrayDeque<HttpState> pending = new java.util.ArrayDeque<>();
        java.util.Set<HttpState> visited = new java.util.HashSet<>();
        pending.add(HttpState.initiallyActive());
        visited.add(HttpState.initiallyActive());
        boolean sawRetired = false, sawStoppedButStillTracked = false;
        while (!pending.isEmpty()) {
            HttpState s = pending.removeFirst();
            if (s.retired) {
                sawRetired = true;
                assertFalse(s.tracked, "retired HTTP context retained an exchange");
                assertTrue(s.socketClosed, "HTTP socket was never physically closed");
                assertFalse(s.actorRunning, "actor turn still owned HTTP transfer");
                assertTrue(s.ioExited, "I/O carriers had not exited");
            }
            if (!s.accepting && s.tracked) sawStoppedButStillTracked = true;
            if (s.tracked && !s.socketClosed) {
                assertFalse(s.retired, "unclosed socket cannot be reclaimed");
            }
            if (s.abortRejected && !s.ioExited) {
                assertFalse(s.socketClosed,
                        "rejected abort cannot fabricate physical close");
            }
            for (HttpEvent e : HttpEvent.values()) {
                HttpState next = httpStep(s, e);
                if (visited.add(next)) pending.addLast(next);
            }
        }
        assertTrue(sawRetired);
        assertTrue(sawStoppedButStillTracked);
        assertTrue(visited.size() >= 15,
                "HTTP shutdown exploration unexpectedly small: " + visited.size());
    }
}
