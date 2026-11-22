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
 *   <li>{@code trap} converts only ordinary failures to Err(Error). Authority denial
 *       and timeout remain terminal at that boundary.</li>
 *   <li>Explicit guest {@code catch} may recover denied operations and timeouts,
 *       without consuming panic, scheduler cancellation or host failures.</li>
 *   <li>Logical stack exhaustion ({@link LogicalStackOverflowException}) is
 *       panic-class: neither boundary consumes it, defers still run, and the
 *       owning actor or Task fails under supervision.</li>
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
            // Only a directly thrown typed guest signal can preserve its
            // original Error identity at a synchronous catch/trap boundary.
            // A host/async wrapper around it is not an authentic typed
            // source throw and must not be converted to a sanitized Err.
            if (current instanceof GuestOrdinaryThrow && current != failure) return false;
            // These are JVM/engine implementation faults, not typed Atto
            // `throw Error(...)` values. Never turn them into a guest Err,
            // even if an async wrapper or cleanup suppression hides the cause.
            // Checked integer overflow is terminal, not an ordinary throw.
            if (panicType.isInstance(current)
                    || current instanceof Error
                    || current instanceof NullPointerException
                    || current instanceof ClassCastException
                    || current instanceof IndexOutOfBoundsException
                    || current instanceof ArrayStoreException
                    || current instanceof NegativeArraySizeException
                    || current instanceof ArithmeticException
                    || current instanceof IllegalMonitorStateException
                    || current instanceof LogicalStackOverflowException
                    || current instanceof CancellationException
                    || current instanceof InterruptedException
                    || current instanceof RejectedExecutionException) {
                return false;
            }
            if (!explicitGuestCatch
                    && (current instanceof SecurityException
                            || current instanceof TimeoutException)) {
                return false;
            }
            Throwable cause = current.getCause();
            if (cause != null) pending.addLast(cause);
            for (Throwable suppressed : current.getSuppressed()) {
                // Trusted source-boundary markers are stackless diagnostics, not
                // additional throwable causes or exceptional control effects.
                // Counting them could turn an ordinary throw into a non-trappable
                // failure merely because source tracing was enabled.
                if (suppressed instanceof SourceBoundaryTrace) continue;
                pending.addLast(suppressed);
                if (pending.size() + visited.size() > MAX_FAILURE_NODES) return false;
            }
            if (pending.size() + visited.size() > MAX_FAILURE_NODES) return false;
        }
        return true;
    }
}
