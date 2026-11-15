package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Distinct guest failure boundaries:
 * <ul>
 *   <li>{@code trap} converts explicit guest {@code throw} signals and
 *       classified interpreter-origin failures into a structured
 *       {@code TrapResult}. Unclassified host runtime exceptions, authority
 *       denial and runtime timeout remain terminal.</li>
 *   <li>Explicit guest {@code catch} may recover a denied operation, but cannot
 *       consume runtime timeout, panic, scheduler cancellation or host failures.</li>
 * </ul>
 *
 * <p>Wrapped and suppressed failures are inspected with bounded, identity-based
 * traversal. Cyclic and oversized graphs fail closed.</p>
 */
public final class TrapFailurePolicy {
    private static final int MAX_FAILURE_NODES = 64;
    private static final Set<Class<?>> MAPPED_GUEST_FAILURE_TYPES = Set.of(
            IllegalArgumentException.class, IllegalStateException.class,
            ArithmeticException.class, IndexOutOfBoundsException.class,
            ClassCastException.class, UnsupportedOperationException.class,
            NoSuchElementException.class, NegativeArraySizeException.class,
            ArrayStoreException.class);

    private record Visit(Throwable failure, boolean primary) { }

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
            Throwable failure,
            Class<? extends Throwable> panicType,
            boolean explicitGuestCatch) {
        if (failure == null) return false;

        // First inspect the entire causal/suppressed graph for control signals
        // that must never be downgraded into an ordinary guest error.
        Deque<Visit> pending = new ArrayDeque<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(new Visit(failure, true));
        int queued = 1;
        boolean matchedPrimary = false;
        while (!pending.isEmpty()) {
            Visit visit = pending.removeFirst();
            Throwable current = visit.failure();
            if (!seen.add(current)) return false;
            if ((panicType != null && panicType.isInstance(current))
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
            // A host subtype may override getCause or getStackTrace. Admit
            // only recognized concrete failure classes before reading either.
            if (!hasTrustedCauseAccessor(current)) return false;
            if (visit.primary() && (current instanceof GuestControlSignal.Throw
                    || current.getClass() == SecurityException.class
                    || isMappedGuestFailure(current))) {
                matchedPrimary = true;
            }
            Throwable cause = current.getCause();
            if (cause != null) {
                if (++queued > MAX_FAILURE_NODES) return false;
                pending.addLast(new Visit(cause, visit.primary()));
            }
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed instanceof SourceBoundaryTrace) continue;
                if (++queued > MAX_FAILURE_NODES) return false;
                pending.addLast(new Visit(suppressed, false));
            }
        }
        return matchedPrimary;
    }

    /** Bound evaluator cause lookup to the same concrete classes as trap. */
    public static <T extends Throwable> T findTrustedCause(Throwable failure, Class<T> type) {
        for (int i = 0; i < MAX_FAILURE_NODES && failure != null; i++) {
            if (type.isInstance(failure)) return type.cast(failure);
            if (!hasTrustedCauseAccessor(failure)) return null;
            failure = failure.getCause();
        }
        return null;
    }

    private static boolean hasTrustedCauseAccessor(Throwable failure) {
        Class<?> type = failure.getClass();
        return type == CompletionException.class || type == ExecutionException.class
                || type == SecurityException.class || failure instanceof GuestControlSignal
                || MAPPED_GUEST_FAILURE_TYPES.contains(type) || isGuestCastError(type);
    }

    private static boolean isGuestCastError(Class<?> type) {
        return type.getClassLoader() == TrapFailurePolicy.class.getClassLoader()
                && type.getName().equals("dev.oreslang.nodes.OresEvalRootNode$OresCastError");
    }

    private static boolean isMappedGuestFailure(Throwable failure) {
        if (MAPPED_GUEST_FAILURE_TYPES.contains(failure.getClass())) {
            // These JDK exception types are guest errors only when they were
            // raised by the Oreslang runtime itself. A host/FFI exception with
            // the same Java class must be explicitly mapped by its adapter.
            StackTraceElement[] trace = failure.getStackTrace();
            return trace.length > 0
                    && trace[0].getClassName().startsWith("dev.oreslang.");
        }
        // The interpreter's cast failure is a dedicated guest runtime type.
        return isGuestCastError(failure.getClass());
    }
}
