package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
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
    private record FailureNode(Throwable error, boolean primary) { }

    // Dedicated interpreter-defined exception classes retain their class
    // identity even if a foreign caller forges their textual name or edits an
    // ordinary JDK Throwable's StackTraceElement array. Avoid a global
    // StackTraceElement/class-name-based guest admission fallback.
    private static final ClassValue<Boolean> TRUSTED_INTERPRETER_FAULT =
            new ClassValue<>() {
                @Override protected Boolean computeValue(Class<?> type) {
                    return type.getNestHost() == dev.oreslang.nodes.OresEvalRootNode.class
                            && type.getName().equals(
                                    "dev.oreslang.nodes.OresEvalRootNode$OresCastError");
                }
            };
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
        if (failure == null || panicType == null) return false;

        // Scan every graph edge just once. Two scans could inspect different
        // mutable cause/suppressed graphs and classify a failure using a
        // different snapshot than the one checked for terminal controls.
        Deque<FailureNode> pending = new ArrayDeque<>();
        IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
        pending.addLast(new FailureNode(failure, true));
        boolean primaryThrow = false;
        boolean primaryDenial = false;
        boolean primaryRuntimeGuestFault = false;
        try {
            while (!pending.isEmpty()) {
                if (seen.size() >= MAX_FAILURE_NODES) return false;
                FailureNode node = pending.removeFirst();
                Throwable current = node.error();
                if (seen.put(current, Boolean.TRUE) != null) return false;
                if (current instanceof GuestControlSignal signal && !signal.compilerIssued()) {
                    return false;
                }
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
                if (current instanceof SecurityException && !explicitGuestCatch) {
                    return false;
                }
                if (node.primary()) {
                    primaryThrow |= current instanceof GuestControlSignal.Throw;
                    primaryDenial |= current instanceof SecurityException;
                    primaryRuntimeGuestFault |= isMappedGuestFailure(current);
                }

                // A custom getCause() can fabricate ordinary guest errors,
                // hide terminal control on another read, or throw.
                if (!STANDARD_CAUSE.get(current.getClass())) return false;
                Throwable cause = current.getCause();
                if (cause != null) {
                    if (seen.size() + pending.size() >= MAX_FAILURE_NODES) return false;
                    pending.addLast(new FailureNode(cause, node.primary()));
                }
                for (Throwable suppressed : current.getSuppressed()) {
                    if (seen.size() + pending.size() >= MAX_FAILURE_NODES) return false;
                    pending.addLast(new FailureNode(suppressed, false));
                }
            }
        } catch (RuntimeException malformedHostGraph) {
            return false;
        }
        return primaryThrow || (explicitGuestCatch && primaryDenial)
                || primaryRuntimeGuestFault;
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
        Deque<FailureNode> pending = new ArrayDeque<>();
        IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
        pending.addLast(new FailureNode(failure, true));
        boolean sawRaiseOnPrimaryCause = false;
        try {
            while (!pending.isEmpty()) {
                if (seen.size() >= MAX_FAILURE_NODES) return false;
                FailureNode node = pending.removeFirst();
                Throwable current = node.error();
                if (seen.put(current, Boolean.TRUE) != null) return false;
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
                    pending.addLast(new FailureNode(cause, node.primary()));
                }
                for (Throwable suppressed : current.getSuppressed()) {
                    if (seen.size() + pending.size() >= MAX_FAILURE_NODES) return false;
                    pending.addLast(new FailureNode(suppressed, false));
                }
            }
        } catch (RuntimeException malformedHostGraph) {
            return false;
        }
        return sawRaiseOnPrimaryCause;
    }

    /**
     * Only dedicated interpreter-owned fault classes are admitted as implicit
     * guest runtime exceptions. Ordinary JDK exception types are NOT trusted
     * simply because their setStackTrace() contents look like interpreter code.
     * Every other built-in guest error must be explicitly lowered to a
     * compiler-issued Throw signal before trap/catch may consume it.
     */
    private static boolean isMappedGuestFailure(Throwable failure) {
        return TRUSTED_INTERPRETER_FAULT.get(failure.getClass());
    }
}
