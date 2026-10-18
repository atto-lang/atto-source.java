package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Host-only stack-safe trampoline for directed cancellation-token links.
 * An upstream cancellation callback queues its downstream source instead
 * of synchronously nesting one Java call stack per linked source.
 * This grants no cross-actor execution or ownership authority.
 */
final class OresCancellationDispatcher {
    private static final ThreadLocal<Dispatch> ACTIVE = new ThreadLocal<>();

    private static final class Dispatch {
        private final ArrayDeque<OresCancellationSource> pending = new ArrayDeque<>();
        private final ArrayDeque<OresCancellationSource> toNotify = new ArrayDeque<>();

        private boolean enqueue(OresCancellationSource source) {
            if (!source.claimCancellation()) return false;
            pending.addLast(source);
            return true;
        }
        private Throwable failure;

        private void record(Throwable problem) {
            if (failure == null) failure = problem;
            else if (failure != problem) failure.addSuppressed(problem);
        }
    }

    private OresCancellationDispatcher() { }

    static void request(OresCancellationSource target) {
        Objects.requireNonNull(target, "target");
        Dispatch active = ACTIVE.get();
        if (active != null) {
            active.pending.addLast(target);
            return;
        }
        Dispatch dispatch = new Dispatch();
        dispatch.pending.addLast(target);
        ACTIVE.set(dispatch);
        try {
            while (!dispatch.pending.isEmpty()) {
                OresCancellationSource next = dispatch.pending.removeFirst();
                try {
                    next.cancel();
                } catch (RuntimeException | Error problem) {
                    // Even a faulty listener must not strand later siblings.
                    dispatch.record(problem);
                }
            }
        } finally {
            dispatch.pending.clear();
            ACTIVE.remove();
        }
        if (dispatch.failure instanceof RuntimeException runtimeFailure)
            throw runtimeFailure;
        if (dispatch.failure instanceof Error error) throw error;
    }
}
