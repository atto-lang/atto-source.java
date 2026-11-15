package dev.oreslang.runtime;

/**
 * Guest-language exceptional control channels.
 *
 * <p>Throw is an ordinary guest error and may be handled by try/catch or
 * converted to TrapResult at a trap boundary. Raise and Panic are deliberate
 * non-trappable control signals. The draft recovery block accepts explicit raises only.
 * Runtime-enforced cancellation and termination use separate runtime signals.
 */
public abstract sealed class GuestControlSignal extends RuntimeException
        permits GuestControlSignal.Throw, GuestControlSignal.Raise, GuestControlSignal.Panic {
    private final Object payload;
    private final String kind;

    private GuestControlSignal(String kind, Object payload) {
        super("Oreslang " + kind + " signal", null, true, true);
        this.kind = kind;
        this.payload = payload;
    }

    public final Object payload() {
        return payload;
    }

    public final String kind() {
        return kind;
    }

    private static final int MAX_RECOVERY_NODES = 64;

    /** Return a primary raise only if every related failure permits recovery. */
    public static Raise recoverableRaise(Throwable failure) {
        Throwable primary = failure;
        for (int depth = 0; isAsyncWrapper(primary); depth++) {
            if (depth == MAX_RECOVERY_NODES) return null;
            primary = primary.getCause();
        }
        if (!(primary instanceof Raise raised)) return null;

        var pending = new java.util.ArrayDeque<Throwable>();
        var seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Throwable, Boolean>());
        pending.add(failure);
        int queued = 1;
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)
                    || !(current instanceof Raise || current instanceof Throw || isAsyncWrapper(current))) {
                return null;
            }
            if (current.getCause() != null) {
                if (++queued > MAX_RECOVERY_NODES) return null;
                pending.addLast(current.getCause());
            }
            for (Throwable suppressed : current.getSuppressed()) {
                if (++queued > MAX_RECOVERY_NODES) return null;
                pending.addLast(suppressed);
            }
        }
        return raised;
    }

    private static boolean isAsyncWrapper(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException;
    }

    public static final class Throw extends GuestControlSignal {
        public Throw(Object payload) {
            super("throw", payload);
        }
    }

    public static final class Raise extends GuestControlSignal {
        public Raise(Object payload) {
            super("raise", payload);
        }
    }

    public static final class Panic extends GuestControlSignal {
        public Panic(Object payload) {
            super("panic", payload);
        }
    }
}
