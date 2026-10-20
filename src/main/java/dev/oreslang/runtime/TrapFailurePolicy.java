package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
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
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) return false;
            if (seen.size() > MAX_FAILURE_NODES) return false;
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
            // The presence of one authentic guest Throw elsewhere in the
            // cause graph cannot launder an unrelated HOST exception through
            // a suppressed cleanup edge. Only known transparent wrappers,
            // an explicit guest signal, or a trusted mapped interpreter
            // error may cross this boundary. Fail closed on everything else.
            if (!(current instanceof GuestControlSignal.Throw
                    || current instanceof java.util.concurrent.CompletionException
                    || current instanceof java.util.concurrent.ExecutionException
                    || (explicitGuestCatch && current instanceof SecurityException)
                    || isMappedGuestFailure(current))) {
                return false;
            }
            if (current.getCause() != null) pending.addLast(current.getCause());
            for (Throwable suppressed : current.getSuppressed()) pending.addLast(suppressed);
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
    }

    private static boolean isMappedGuestFailure(Throwable failure) {
        // Throwable.setStackTrace() is public. A host/FFI exception can forge
        // dev.oreslang.* frame names, and cannot become a guest throw merely
        // because a Java exception's mutable diagnostic text says so.
        // Ordinary JDK exceptions must be explicitly wrapped by a future
        // trusted interpreter-origin mapper with real sealed provenance.
        //
        // Keep only the interpreter's dedicated cast-error type, and verify
        // its actual runtime ClassLoader rather than trusting its string
        // name alone. This intentional fail-closed restriction temporarily
        // excludes unclassified host-JDK-like failures from trap/catch.
        return failure.getClass().getName()
                    .equals("dev.oreslang.nodes.OresEvalRootNode$OresCastError")
                && failure.getClass().getClassLoader()
                    == TrapFailurePolicy.class.getClassLoader();
    }
}
