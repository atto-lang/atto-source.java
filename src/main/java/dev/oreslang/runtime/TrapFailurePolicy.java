package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CancellationException;
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
    private record RecoveryNode(Throwable error, boolean primary) { }
    private static final ClassValue<Boolean> STANDARD_CAUSE = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getCause").getDeclaringClass() == Throwable.class;
            } catch (ReflectiveOperationException | SecurityException exception) {
                return false;
            }
        }
    };

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
        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        try {
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) return false;
            if (seen.size() > MAX_FAILURE_NODES) return false;
            // A foreign Java caller may instantiate the public diagnostic
            // carrier classes. Their type names alone never convey the right
            // to turn a host exception into a guest trap/catch result.
            if (current instanceof GuestControlSignal signal && !signal.compilerIssued()) {
                return false;
            }
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
            // Do not invoke an overridden foreign accessor: it can alternate
            // between a guest-looking cause and concealed terminal control.
            if (!STANDARD_CAUSE.get(current.getClass())) return false;
            Throwable cause = current.getCause();
            if (cause != null) {
                if (pending.size() + seen.size() >= MAX_FAILURE_NODES) return false;
                pending.addLast(cause);
            }
            for (Throwable suppressed : current.getSuppressed()) {
                if (pending.size() + seen.size() >= MAX_FAILURE_NODES) return false;
                pending.addLast(suppressed);
            }
        }

        // Classification follows the primary cause chain only. A suppressed
        // guest throw must never make an unrelated host failure trappable.
        Set<Throwable> causeSeen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure;
                current != null && causeSeen.add(current);
                current = current.getCause()) {
            if (current instanceof GuestControlSignal.Throw) return true;
            if (current instanceof SecurityException) return explicitGuestCatch;
            if (isMappedGuestFailure(current)) return true;
        }
        return false;
        } catch (RuntimeException malformedHostGraph) {
            return false;
        }
    }

    /**
     * A recovery boundary may consume only a typed RAISE on the primary cause
     * chain. PANIC and every scheduler/host terminal signal bypass it, including
     * if they are suppressed on an otherwise valid RAISE. A suppressed RAISE
     * cannot grant authority to recover an unrelated host failure.
     *
     * The direct-issuer marker rejects foreign-host-manufactured RAISE values.
     * This is not yet a task/actor incarnation capability, nor proof that a
     * host wrapper originated in the same execution domain as the signal.
     * That stronger admission bridge is still required before FFI exposure.
     */
    public static boolean isRecoverableRaise(
            Throwable failure, Class<? extends Throwable> panicType) {
        if (failure == null || panicType == null) return false;
        Deque<RecoveryNode> pending = new ArrayDeque<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.addLast(new RecoveryNode(failure, true));
        boolean sawRaiseOnPrimaryCause = false;
        try {
            while (!pending.isEmpty()) {
                if (seen.size() >= MAX_FAILURE_NODES) return false;
                RecoveryNode node = pending.removeFirst();
                Throwable current = node.error();
                if (!seen.add(current)) return false;
                if (panicType.isInstance(current)
                        || current instanceof GuestControlSignal.Panic
                        || current instanceof GuestControlSignal.Throw
                        || current instanceof Error
                        || current instanceof CancellationException
                        || current instanceof InterruptedException
                        || current instanceof RejectedExecutionException
                        || current instanceof TimeoutException
                        || current instanceof SecurityException) {
                    return false;
                }
                if (current instanceof GuestControlSignal.Raise raised) {
                    if (!node.primary() || !raised.compilerIssued()) return false;
                    sawRaiseOnPrimaryCause = true;
                }
                if (!STANDARD_CAUSE.get(current.getClass())) return false;
                Throwable cause = current.getCause();
                if (cause != null) {
                    if (seen.size() + pending.size() >= MAX_FAILURE_NODES) return false;
                    pending.addLast(new RecoveryNode(cause, node.primary()));
                }
                for (Throwable suppressed : current.getSuppressed()) {
                    if (seen.size() + pending.size() >= MAX_FAILURE_NODES) return false;
                    pending.addLast(new RecoveryNode(suppressed, false));
                }
            }
        } catch (RuntimeException malformedHostGraph) {
            return false;
        }
        return sawRaiseOnPrimaryCause;
    }

    private static boolean isMappedGuestFailure(Throwable failure) {
        if (failure instanceof IllegalArgumentException
                || failure instanceof IllegalStateException
                || failure instanceof ArithmeticException
                || failure instanceof IndexOutOfBoundsException
                || failure instanceof ClassCastException
                || failure instanceof UnsupportedOperationException
                || failure instanceof NoSuchElementException
                || failure instanceof NegativeArraySizeException
                || failure instanceof ArrayStoreException) {
            // These JDK exception types are guest errors only when they were
            // raised by the Oreslang runtime itself. A host/FFI exception with
            // the same Java class must be explicitly mapped by its adapter.
            StackTraceElement[] trace = failure.getStackTrace();
            return trace.length > 0
                    && trace[0].getClassName().startsWith("dev.oreslang.");
        }
        // The interpreter's cast failure is a dedicated guest runtime type.
        return failure.getClass().getName()
                .equals("dev.oreslang.nodes.OresEvalRootNode$OresCastError");
    }
}
