package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Distinct guest failure boundaries:
 * <ul>
 *   <li>{@code trap} converts ordinary guest failures into a structured
 *       {@code TrapResult}. Authority denial and runtime timeout remain terminal.</li>
 *   <li>Explicit guest {@code catch} may recover a denied operation, but cannot
 *       consume runtime timeout, panic, scheduler cancellation or host failures.</li>
 * </ul>
 *
 * <p>Wrapped and suppressed failures are inspected with bounded, identity-based
 * traversal. Cyclic and oversized graphs fail closed.</p>
 */
public final class TrapFailurePolicy {
    private static final int MAX_FAILURE_NODES = 64;

    private TrapFailurePolicy() { }

    public static boolean isTrappable(
            RuntimeException failure, Class<? extends Throwable> panicType) {
        return permits(failure, panicType, false);
    }

    public static boolean isGuestCatchable(
            Throwable failure, Class<? extends Throwable> panicType) {
        return permits(failure, panicType, true);
    }

    private static boolean permits(
            Throwable failure, Class<? extends Throwable> panicType,
            boolean explicitGuestCatch) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(panicType, "panicType");

        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
        pending.addLast(failure);
        while (!pending.isEmpty()) {
            if (visited.size() >= MAX_FAILURE_NODES) return false;
            Throwable current = pending.removeFirst();
            if (visited.put(current, Boolean.TRUE) != null) return false;
            if (panicType.isInstance(current)
                    || current instanceof GuestControlSignal.Raise
                    || current instanceof GuestControlSignal.Panic
                    || current instanceof Error
                    || current instanceof CancellationException
                    || current instanceof InterruptedException
                    || current instanceof RejectedExecutionException
                    || current instanceof TimeoutException) {
                return false;
            }
            if (!explicitGuestCatch && current instanceof SecurityException) {
                return false;
            }
            Throwable cause = current.getCause();
            if (cause != null) pending.addLast(cause);
            for (Throwable suppressed : current.getSuppressed()) {
                pending.addLast(suppressed);
                if (pending.size() + visited.size() > MAX_FAILURE_NODES) return false;
            }
            if (pending.size() + visited.size() > MAX_FAILURE_NODES) return false;
        }
        return true;
    }
}
