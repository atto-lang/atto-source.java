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

    static boolean request(OresCancellationSource target) {
        Objects.requireNonNull(target, "target");
        Dispatch active = ACTIVE.get();
        if (active != null) return active.enqueue(target);
        Dispatch dispatch = new Dispatch();
        boolean won = dispatch.enqueue(target);
        ACTIVE.set(dispatch);
        try {
            while (!dispatch.pending.isEmpty() || !dispatch.toNotify.isEmpty()) {
                // Revoke all transitive links before running host observers.
                while (!dispatch.pending.isEmpty()) {
                    OresCancellationSource next = dispatch.pending.removeFirst();
                    try {
                        for (OresCancellationSource child : next.collectLinkedChildren()) {
                            dispatch.enqueue(child);
                        }
                    } catch (RuntimeException | Error problem) {
                        dispatch.record(problem);
                    } finally {
                        dispatch.toNotify.addLast(next);
                    }
                }
                if (!dispatch.toNotify.isEmpty()) {
                    try {
                        dispatch.toNotify.removeFirst().publishCancellation();
                    } catch (RuntimeException | Error problem) {
                        dispatch.record(problem);
                    }
                }
            }
        } finally {
            dispatch.pending.clear();
            dispatch.toNotify.clear();
            ACTIVE.remove();
        }
        if (dispatch.failure instanceof RuntimeException runtimeFailure)
            throw runtimeFailure;
        if (dispatch.failure instanceof Error error) throw error;
        return won;
    }
}
