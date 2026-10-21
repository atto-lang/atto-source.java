import java.util.Locale;

/**
 * Bounded deterministic host reference trace; NOT guest execution.
 * Emits one CSV transition per event for an independent FSM trace-v0
 * semantic validator. All snapshot arithmetic is checked as unsigned u32.
 */
public final class FsmTraceWitness {
    private static final long LIMIT = (1L << 32) - 1;
    private static final int[] EVENTS = {0, 1, 1, 2, 3, 4, 0, 1, 2, 2, 3, 99};
    private record State(int state, long version, int ticks) { }
    private record Decision(boolean accepted, String reason, State after, int effect) { }

    private static Decision step(State before, int event) {
        if (before.state < 0 || before.version < 0 || before.version > LIMIT
                || before.ticks < 0 || event < 0)
            throw new IllegalArgumentException("negative or overflowing input");
        if (before.state > 2) return new Decision(false, "invalid-state", before, 0);
        if (before.ticks > 3) return new Decision(false, "invalid-ticks", before, 0);
        if (before.version == LIMIT) return new Decision(false, "version-exhausted", before, 0);
        if (before.state == 0 && event == 0)
            return new Decision(true, "none", new State(1, before.version + 1, 0), 1);
        if (before.state == 1 && event == 1) {
            if (before.ticks == 3) return new Decision(false, "guard", before, 0);
            return new Decision(true, "none", new State(1, before.version + 1, before.ticks + 1), 2);
        }
        if (before.state == 1 && event == 2) {
            if (before.ticks < 2) return new Decision(false, "guard", before, 0);
            return new Decision(true, "none", new State(2, before.version + 1, before.ticks), 3);
        }
        if (before.state == 2 && event == 3)
            return new Decision(true, "none", new State(0, before.version + 1, 0), 4);
        return new Decision(false, "unhandled", before, 0);
    }

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("no CLI inputs accepted");
        final int entities = 5;
        final int length = 2040;
        State[] current = new State[entities];
        for (int i = 0; i < entities; i++) current[i] = new State(0, 0, 0);
        for (int seq = 0; seq < length; seq++) {
            int entity = seq % entities;
            int event = EVENTS[(seq / entities) % EVENTS.length];
            State before = current[entity];
            Decision result = step(before, event);
            State after = result.after;
            System.out.printf(Locale.ROOT,
                    "%d,%d,%d,%s,%s,%d,%d,%d,%d,%d,%d,%d%n",
                    seq, entity, event, result.accepted, result.reason,
                    before.state, before.version, before.ticks,
                    after.state, after.version, after.ticks, result.effect);
            current[entity] = after;
        }
    }
}
