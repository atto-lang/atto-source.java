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
        require(!quantum.shouldCooperateAtCheck(false),
                "early clock-only cooperation requested at zero elapsed");
        advance(quantum, clock, 128, 256, 2); // 128 and 256
        clock.now = 1_999_999L;
        require(!quantum.shouldCooperateAtCheck(false),
                "soft deadline must not fire a nanosecond early");
        advance(quantum, clock, 257, 383, 0);
        require(quantum.shouldCooperateAtCheck(true),
                "watchdog notice should be visible on the slow path");
        clock.now = SourceCooperationQuantum.SOFT_DISPATCH_QUANTUM_NANOS;
        advance(quantum, clock, 384, 384, 1);
        require(quantum.shouldCooperateAtCheck(false),
                "soft deadline equality should request cooperation");
        advance(quantum, clock, 385, 511, 0);
        advance(quantum, clock, 512, 512, 1);
        int readsBeforeOverdue = clock.reads;
        require(quantum.shouldCooperateAtCheck(false),
                "512 completed reductions must request cooperative handoff");
        require(clock.reads == readsBeforeOverdue,
                "overdue reduction cap should avoid an additional clock read");
        advance(quantum, clock, 513, 4096, 28);
        require(quantum.completedBackedgesThisDispatch() == 4096,
                "remaining after 512 must not erase completed reduction count");

        // A fresh carrier dispatch resets only the trusted soft quantum.
        quantum.beginDispatch();
        require(quantum.completedBackedgesThisDispatch() == 0,
                "dispatch admission must reset only the soft reduction counter");
        advance(quantum, clock, 1, 127, 0);
        require(quantum.completedBackedgesThisDispatch() == 127,
                "repeated dispatch cadence changed");
        int beforeWatchdog = clock.reads;
        require(quantum.shouldCooperateAtCheck(true),
                "watchdog notice must be observable at an admitted slow poll");
        require(clock.reads == beforeWatchdog,
                "watchdog shortcut must not read the clock again");

        // Clock regression or signed wrap must fail closed; neither mints time.
        clock.now = Long.MAX_VALUE;
        quantum.beginDispatch();
        clock.now = Long.MIN_VALUE + 9;
        require(quantum.shouldCooperateAtCheck(false),
                "signed clock overflow must request overdue cooperation");
        quantum.recordedCooperate();
        clock.now = Long.MAX_VALUE;
        require(quantum.timeSinceLastCooperateNanos() == Long.MAX_VALUE,
                "unrepresentable elapsed diagnostic must saturate");
        System.out.println("PASS: trusted soft quantum 128/512 cadence and deadline edges (standalone Java host only)");
    }
}
