package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Exactly-once, fail-closed resource retirement.
 *
 * The first caller performs physical teardown. Concurrent callers wait for
 * the same confirmed result rather than mistaking "closing" for "closed".
 * The retire action must only release admission capacity after teardown
 * completes. Failures are sanitized and retained for all future callers.
 *
 * Never invoke a retiring Context's guest execution monitor here: the guest
 * may be stuck inside that monitor and must remain cancelable.
 */
final class VerifiedCloseFence {
    private final FutureTask<Void> completion;

    VerifiedCloseFence(Runnable retire) {
        Objects.requireNonNull(retire, "retire");
        this.completion = new FutureTask<>(() -> {
            retire.run();
            return null;
        });
    }

    /** A fatal host-VM error is not a guest exception and must retain its type. */
    static boolean isVmFatal(Throwable failure) {
        return failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError;
    }

    /**
     * Complete sibling teardown even after failure. If a later close throws
     * a VM-fatal error, make that fatal error primary and retain earlier
     * failures as suppressed evidence rather than swallowing it.
     */
    static Throwable mergeRetirementFailure(Throwable current, Throwable next) {
        Objects.requireNonNull(next, "next");
        if (current == null) return next;
        if (current == next) return current;
        if (isVmFatal(next) && !isVmFatal(current)) {
            next.addSuppressed(current);
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    static void rethrowRetirementFailure(Throwable failure) {
        if (failure == null) return;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error fatal) throw fatal;
        throw new IllegalStateException("unexpected checked untrusted-isolate retirement failure");
    }

    void awaitConfirmedClose(String resource) {
        completion.run();
        try {
            completion.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    resource + " retirement interrupted before close was confirmed");
        } catch (ExecutionException failed) {
            // Ordinary guest/native failures may embed guest object references
            // and private source: sanitize them with no causal chain.
            // Host VM-fatal errors must not be masked by the FutureTask fence.
            Throwable cause = failed.getCause();
            if (isVmFatal(cause)) {
                rethrowRetirementFailure(cause);
            }
            throw new IllegalStateException(
                    resource + " retirement failed; capacity remains reserved");
        }
    }
}
