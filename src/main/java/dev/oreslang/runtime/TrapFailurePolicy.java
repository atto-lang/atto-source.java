package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Guest trap admission. Only ordinary guest failures may become None.
 *
 * <p>Guest and host boundaries can wrap termination, capability denial, scheduler
 * rejection, or cancellation in otherwise trappable runtime exceptions. The
 * bounded graph traversal includes suppressed cleanup failures, not just causes.
 * Cyclic, excessively deep or excessively wide failure graphs fail closed.</p>
 */
public final class TrapFailurePolicy {
    private static final int MAX_FAILURE_NODES = 64;

    private TrapFailurePolicy() { }

    public static boolean isTrappable(
            RuntimeException failure, Class<? extends Throwable> panicType) {
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
                    || current instanceof Error
                    || current instanceof SecurityException
                    || current instanceof CancellationException
                    || current instanceof InterruptedException
                    || current instanceof RejectedExecutionException
                    || current instanceof TimeoutException) {
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
