package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;

/** Shared exceptional-exit precedence for both synchronous and suspended frames. */
public final class OresExceptionPolicy {
    private OresExceptionPolicy() { }

    /** Host-fatal > cancellation > panic > raise > ordinary throw. */
    private static int priority(Throwable failure) {
        if (failure instanceof ProcessExitSignal
                || failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError) return 5;
        if (failure instanceof ActorRuntime.ActorCancellationSignal
                || failure instanceof CancellationException) return 4;
        // Any uncaught Java Error is a host failure at an actor boundary,
        // not an ordinary Oreslang throw. Preserve that classification during
        // defer/finally cleanup instead of allowing a later guest panic to hide
        // it. ActorCancellationSignal (also an Error) is handled above.
        if (failure instanceof Error) return 5;
        if (failure instanceof OresPanic) return 3;
        if (failure instanceof OresRaised) return 2;
        return 1;
    }

    /**
     * Select a dominant failure while preserving the losing error as suppressed.
     * A throw in cleanup cannot convert a pending panic or cancellation into a
     * catchable ordinary exception. No exception is swallowed.
     */
    public static Throwable combine(Throwable pending, Throwable cleanup) {
        if (pending == null) return cleanup;
        if (cleanup == null || cleanup == pending) return pending;
        if (priority(cleanup) > priority(pending)) {
            cleanup.addSuppressed(pending);
            return cleanup;
        }
        pending.addSuppressed(cleanup);
        return pending;
    }

    /** Rethrow at an explicit unwind boundary; never wrap away the signal type. */
    public static void rethrow(Throwable failure) {
        if (failure == null) return;
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        // A raw checked JVM Throwable at this boundary is a host/runtime
        // protocol violation, not a guest catchable RuntimeException. Never
        // manufacture an ordinary throw that source catch/trap could swallow.
        // Keep the original cause attached for trusted host diagnostics.
        throw new AssertionError("unexpected checked host failure at guest unwind boundary", failure);
    }
}
