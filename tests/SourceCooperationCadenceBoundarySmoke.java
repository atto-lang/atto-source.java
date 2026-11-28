import dev.oreslang.runtime.SourceCooperationQuantum;
import java.util.function.LongSupplier;

/**
 * Run without Maven/JUnit:
 *   javac -d /tmp/atto-quantum-smoke src/main/java/dev/oreslang/runtime/SourceCooperationQuantum.java tests/SourceCooperationCadenceBoundarySmoke.java
 *   java -cp /tmp/atto-quantum-smoke SourceCooperationCadenceBoundarySmoke
 *
 * This covers a trusted SOFT cooperation quantum only, not UNTRUSTED hard
 * fuel/deadline accounting, compiler instrumentation, actual cancellation,
 * heap continuations or source guest execution.
 */
public final class SourceCooperationCadenceBoundarySmoke {
    private static final class Clock implements LongSupplier {
        long now;
        int reads;
        @Override public long getAsLong() {
            reads++;
            return now;
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    private static void advance(SourceCooperationQuantum quantum,
                                Clock clock, int from, int to, int expectedPolls) {
        int polls = 0;
        int oldReads = clock.reads;
        for (int i = from; i <= to; i++) {
            boolean due = quantum.completedBackedgeNeedsCheck();
            require(due == (i % 128 == 0), "wrong poll boundary " + i);
            if (due) polls++;
        }
        require(polls == expectedPolls, "wrong batch poll count");
        require(clock.reads == oldReads,
                "expensive clock accessed on a committed backedge fast path");
    }

    public static void main(String[] args) {
        require(SourceCooperationQuantum.LOOP_CHECK_INTERVAL == 128,
                "trusted cooperative polling unexpectedly changed");
        final Clock clock = new Clock();
        final SourceCooperationQuantum quantum = new SourceCooperationQuantum(clock);
        require(clock.reads == 1, "constructor should sample once");
        quantum.beginDispatch();
        require(clock.reads == 2, "begin dispatch should sample once");

        advance(quantum, clock, 1, 127, 0);
        require(quantum.completedBackedgesThisDispatch() == 127,
                "fast path lost reduction debt");
        int offCycleReads = clock.reads;
        require(!quantum.shouldCooperateAtCheck(true),
                "watchdog cannot mint an off-cycle poll ticket");
        require(clock.reads == offCycleReads, "off-cycle poll read clock");
        advance(quantum, clock, 128, 128, 1);
        require(!quantum.shouldCooperateAtCheck(false), "early clock-only cooperation");
        int afterFirstPoll = clock.reads;
        require(!quantum.shouldCooperateAtCheck(true), "poll ticket was reusable");
        require(clock.reads == afterFirstPoll, "duplicate poll read clock");

        clock.now = SourceCooperationQuantum.SOFT_DISPATCH_QUANTUM_NANOS - 1;
        advance(quantum, clock, 129, 256, 1);
        require(!quantum.shouldCooperateAtCheck(false),
                "soft deadline must not fire a nanosecond early");
        clock.now = SourceCooperationQuantum.SOFT_DISPATCH_QUANTUM_NANOS;
        advance(quantum, clock, 257, 384, 1);
        require(quantum.shouldCooperateAtCheck(false),
                "soft deadline equality should request cooperation");
        require(quantum.pendingHandoffOnNoYieldExit(), "deadline debt was lost");

        // A fresh dispatch after await is not a completed cooperation.
        quantum.beginDispatch();
        require(quantum.completedBackedgesThisDispatch() == 0,
                "dispatch admission must reset only the soft reduction counter");
        require(quantum.pendingHandoffOnNoYieldExit(), "await erased overdue handoff");
        advance(quantum, clock, 1, 128, 1);
        int beforePending = clock.reads;
        require(quantum.shouldCooperateAtCheck(false), "latched debt lost on resume");
        require(clock.reads == beforePending, "latched handoff read clock");
        quantum.recordedCooperate();
        quantum.beginDispatch();
        require(!quantum.pendingHandoffOnNoYieldExit(), "completed handoff remained latched");

        // Separate fixed-clock trace isolates the reduction cap and watchdog.
        final Clock fixed = new Clock();
        final SourceCooperationQuantum reductions = new SourceCooperationQuantum(fixed);
        reductions.beginDispatch();
        for (int boundary = 128; boundary <= 512; boundary += 128) {
            advance(reductions, fixed, boundary - 127, boundary, 1);
            int before = fixed.reads;
            require(reductions.shouldCooperateAtCheck(false) == (boundary == 512),
                    "incorrect reduction cap at " + boundary);
            require(fixed.reads == before + (boundary == 512 ? 0 : 1),
                    "incorrect slow-path clock cost");
        }
        advance(reductions, fixed, 513, 4096, 28);
        require(reductions.completedBackedgesThisDispatch() == 4096,
                "remaining after 512 must not erase completed reduction count");
        reductions.recordedCooperate();
        reductions.beginDispatch();
        advance(reductions, fixed, 1, 64, 0);
        reductions.beginDispatch();
        for (int i = 1; i <= 64; i++) {
            require(reductions.completedBackedgeNeedsCheck() == (i == 64),
                    "short dispatch lost persistent poll phase");
        }
        int beforeWatchdog = fixed.reads;
        require(reductions.shouldCooperateAtCheck(true),
                "watchdog notice must be observable at an admitted slow poll");
        require(fixed.reads == beforeWatchdog, "watchdog shortcut read clock");

        // Signed-wrap and unrepresentable elapsed diagnostics fail toward fairness.
        final Clock wrap = new Clock();
        wrap.now = Long.MAX_VALUE;
        final SourceCooperationQuantum wrapping = new SourceCooperationQuantum(wrap);
        wrapping.beginDispatch();
        wrap.now = Long.MIN_VALUE + 9;
        advance(wrapping, wrap, 1, 128, 1);
        require(wrapping.shouldCooperateAtCheck(false),
                "signed clock overflow must request overdue cooperation");
        wrapping.recordedCooperate();
        wrap.now = Long.MAX_VALUE;
        require(wrapping.timeSinceLastCooperateNanos() == Long.MAX_VALUE,
                "unrepresentable elapsed diagnostic must saturate");
        System.out.println("PASS: trusted soft quantum 128/512 cadence, poll tickets, persistent handoff and deadline edges (standalone Java host only)");
    }
}
