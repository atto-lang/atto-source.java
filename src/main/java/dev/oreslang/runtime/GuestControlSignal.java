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
                // Runtime-created source locations are inert, final leaf
                // diagnostics. They are attached as suppressed exceptions
                // when a raise crosses a function or an await boundary.
                if (suppressed instanceof SourceBoundaryTrace) continue;
                if (++queued > MAX_RECOVERY_NODES) return null;
                pending.addLast(suppressed);
            }
        }
        return raised;
    }

    private static boolean isAsyncWrapper(Throwable failure) {
        if (failure == null) return false;
        // Subclasses can override getCause() and present different failures
        // during classification, or execute host code on the recovery path.
        Class<?> type = failure.getClass();
        return type == java.util.concurrent.CompletionException.class
                || type == java.util.concurrent.ExecutionException.class;
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
