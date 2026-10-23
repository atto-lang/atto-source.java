package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Deny-by-default exceptional-effect boundaries in the guest interpreter.
 * An ordinary guest THROW is catchable/trappable. A RAISE may enter only an
 * eligible recover boundary; PANIC, cancellation, forced shutdown and host
 * fatal errors are not locally consumable.
 *
 * <p>This classifier does not mint GuestControlSignal provenance. A future
 * compiler-issued signal envelope must prevent foreign Java code from
 * manufacturing a guest RAISE. Until then callers must not export this as an
 * untrusted guest/FFI recovery admission interface.</p>
 */
public final class TrapFailurePolicy {
    private static final int MAX_FAILURE_NODES = 64;
    private enum Boundary { TRAP, GUEST_CATCH, RECOVER }

    private record Pending(Throwable failure, boolean primaryCause) { }

    private TrapFailurePolicy() { }

    public static boolean isTrappable(
            RuntimeException failure, Class<? extends Throwable> panicType) {
        return permits(failure, panicType, Boundary.TRAP);
    }

    public static boolean isGuestCatchable(
            Throwable failure, Class<? extends Throwable> panicType) {
        return permits(failure, panicType, Boundary.GUEST_CATCH);
    }

    public static boolean isRecoverableRaise(
            Throwable failure, Class<? extends Throwable> panicType) {
        return permits(failure, panicType, Boundary.RECOVER);
    }

    private static boolean permits(
            Throwable failure, Class<? extends Throwable> panicType,
            Boundary boundary) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(panicType, "panicType");

        ArrayDeque<Pending> pending = new ArrayDeque<>();
        IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
        pending.addLast(new Pending(failure, true));
        boolean sawPrimaryRaise = false;
        try {
            while (!pending.isEmpty()) {
                if (visited.size() >= MAX_FAILURE_NODES) return false;
                Pending entry = pending.removeFirst();
                Throwable current = entry.failure();
                if (visited.put(current, Boolean.TRUE) != null) return false;
                if (panicType.isInstance(current)
                        || current instanceof Error
                        || current instanceof GuestControlSignal.Panic
                        || current instanceof CancellationException
                        || current instanceof InterruptedException
                        || current instanceof RejectedExecutionException) {
                    return false;
                }
                if (current instanceof GuestControlSignal.Raise) {
                    if (boundary != Boundary.RECOVER || !entry.primaryCause()) return false;
                    sawPrimaryRaise = true;
                }
                if (boundary == Boundary.RECOVER
                        && (current instanceof GuestControlSignal.Throw
                                || current instanceof SecurityException
                                || current instanceof TimeoutException)) {
                    return false;
                }
                if (boundary == Boundary.TRAP
                        && (current instanceof SecurityException
                                || current instanceof TimeoutException)) {
                    return false;
                }
                Throwable cause = current.getCause();
                if (cause != null) {
                    if (pending.size() + visited.size() >= MAX_FAILURE_NODES) return false;
                    pending.addLast(new Pending(cause, entry.primaryCause()));
                }
                for (Throwable suppressed : current.getSuppressed()) {
                    if (suppressed instanceof SourceBoundaryTrace) continue;
                    if (pending.size() + visited.size() >= MAX_FAILURE_NODES) return false;
                    pending.addLast(new Pending(suppressed, false));
                }
            }
        } catch (RuntimeException malformedHostGraph) {
            // Never turn a foreign Throwable accessor failure into permission to
            // consume the original guest control effect.
            return false;
        }
        return boundary != Boundary.RECOVER || sawPrimaryRaise;
    }
}
