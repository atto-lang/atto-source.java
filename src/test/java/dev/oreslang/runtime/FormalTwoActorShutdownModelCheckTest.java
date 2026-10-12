package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounded exhaustive interleaving check for two independently streaming,
 * cancellation-aware actor/exchange owners sharing one HTTP I/O executor.
 *
 * This finite abstract machine is not a refinement proof of the JVM or LLVM.
 * Guest-source streaming tests are the concrete implementation conformance gate.
 */
final class FormalTwoActorShutdownModelCheckTest {
    private static final int CANCELLED = 1, ACTOR_EXIT = 2;
    private static final int SOCKET_CLOSED = 4, RELEASED = 8;
    private enum Action {
        CLOSE_ADMISSION, CANCEL_A, CANCEL_B, ACTOR_EXIT_A, ACTOR_EXIT_B,
        SOCKET_CLOSE_A, SOCKET_CLOSE_B, RELEASE_A, RELEASE_B,
        STOP_IO, IO_EXIT, RETIRE, STALE_A, STALE_B
    }

    private record State(
            int a, int b, boolean admissionSealed, boolean ioOpen,
            boolean ioExited, boolean retired) {
        static State initial() { return new State(0, 0, false, true, false, false); }
        boolean bothReleased() {
            return (a & RELEASED) != 0 && (b & RELEASED) != 0;
        }
    }

    private static int advanceOwner(int flags, Action event, boolean ownerA, boolean ioOpen) {
        switch (event) {
            case CANCEL_A, CANCEL_B -> {
                if ((event == Action.CANCEL_A) == ownerA) flags |= CANCELLED;
            }
            case ACTOR_EXIT_A, ACTOR_EXIT_B -> {
                if ((event == Action.ACTOR_EXIT_A) == ownerA
                        && (flags & CANCELLED) != 0) flags |= ACTOR_EXIT;
            }
            case SOCKET_CLOSE_A, SOCKET_CLOSE_B -> {
                if ((event == Action.SOCKET_CLOSE_A) == ownerA && ioOpen
                        && (flags & CANCELLED) != 0) flags |= SOCKET_CLOSED;
            }
            case RELEASE_A, RELEASE_B -> {
                if ((event == Action.RELEASE_A) == ownerA
                        && (flags & ACTOR_EXIT) != 0
                        && (flags & SOCKET_CLOSED) != 0) flags |= RELEASED;
            }
            default -> { }
        }
        return flags;
    }

    private static State step(State s, Action event) {
        int a = advanceOwner(s.a, event, true, s.ioOpen);
        int b = advanceOwner(s.b, event, false, s.ioOpen);
        boolean sealed = s.admissionSealed, open = s.ioOpen;
        boolean exited = s.ioExited, retired = s.retired;
        switch (event) {
            case CLOSE_ADMISSION -> sealed = true;
            case STOP_IO -> {
                if (sealed && s.bothReleased()) open = false;
            }
            case IO_EXIT -> {
                if (!open && s.bothReleased()) exited = true;
            }
            case RETIRE -> {
                if (sealed && exited && s.bothReleased()) retired = true;
            }
            case STALE_A, STALE_B -> { /* cannot mutate any generation */ }
            default -> { }
        }
        return new State(a, b, sealed, open, exited, retired);
    }

    private static void checkSafety(State s) {
        for (int flags : new int[] {s.a, s.b}) {
            if ((flags & RELEASED) != 0) {
                assertTrue((flags & ACTOR_EXIT) != 0,
                        "exchange reclaimed while owning actor still executes");
                assertTrue((flags & SOCKET_CLOSED) != 0,
                        "exchange reclaimed before physical socket close");
            }
        }
        if (!s.ioOpen || s.ioExited || s.retired) {
            assertTrue(s.bothReleased(),
                    "HTTP executor physically retired before both exchanges");
        }
        if (s.retired) {
            assertTrue(s.admissionSealed);
            assertTrue(s.ioExited);
        }
    }

    @Test
    void exhaustTwoActorCancellationStreamingAndExecutorExitInterleavings() {
        State initial = State.initial();
        Set<State> seen = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        seen.add(initial);
        queue.add(initial);
        boolean sawRetired = false, sawMixedOwners = false;
        while (!queue.isEmpty()) {
            State s = queue.removeFirst();
            checkSafety(s);
            if (s.retired) sawRetired = true;
            if ((s.a & RELEASED) != 0 && (s.b & RELEASED) == 0) {
                sawMixedOwners = true;
                assertTrue(s.ioOpen, "I/O must still admit the other owner's cleanup");
            }
            for (Action action : Action.values()) {
                State next = step(s, action);
                checkSafety(next);
                if (action == Action.STALE_A || action == Action.STALE_B) {
                    assertEquals(s, next, "stale completion cannot affect ownership");
                }
                if (seen.add(next)) queue.addLast(next);
            }
        }
        assertTrue(sawMixedOwners, "must explore one completed and one live exchange");
        assertTrue(sawRetired, "fairly completed streams must permit eventual retirement");
        assertTrue(seen.size() >= 100, "model was unexpectedly shallow: " + seen.size());
    }

    @Test
    void intentionallyUnsafeEarlyIoRetirementMustProduceCounterexample() {
        State unsafe = new State(0, CANCELLED, true, false, true, true);
        assertThrows(AssertionError.class, () -> checkSafety(unsafe),
                "unsafe shutdown must be rejected by the model's invariant");
        // Once the executor physically exits with an unclosed socket,
        // response work cannot run on the old carriers. This is exactly the
        // dead-end avoided by keeping I/O alive until all owners release.
        State after = unsafe;
        for (Action event : Action.values()) {
            assertFalse(step(after, event).bothReleased(),
                    "an unsafe shutdown could not magically discharge both loans");
        }
    }
}
