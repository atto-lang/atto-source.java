package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pull-oriented rx-ores subscription.
 *
 * <p>Exactly one pull may be outstanding. The pull Future is the common
 * suspension/cancellation boundary shared with Oreslang await.</p>
 */
public abstract class OresSubscription<T> {
    private final Object gate = new Object();
    private final AtomicBoolean cleanupOnce = new AtomicBoolean();
    private boolean cancelled;
    private boolean terminal;
    private boolean pulling;
    private OresFuture<OresNotification<T>> active;

    public final OresFuture<OresNotification<T>> next() {
        synchronized (gate) {
            if (cancelled || terminal) return OresFuture.completed(OresNotification.complete());
            if (pulling) {
                return OresFuture.failed(new IllegalStateException(
                        "rx-ores subscription already has an outstanding next()"));
            }
            pulling = true;
        }

        final OresFuture<OresNotification<T>> source;
        try {
            source = Objects.requireNonNull(nextFromRuntime(), "nextFromRuntime returned null Future");
        } catch (Throwable failure) {
            synchronized (gate) {
                pulling = false;
                terminal = true;
            }
            cleanupOnce();
            return OresFuture.failed(failure);
        }

        OresFuture<OresNotification<T>> exposed =
                new OresFuture<>(() -> source.cancel(true));

        synchronized (gate) {
            if (cancelled || terminal) {
                pulling = false;
                source.cancel(true);
                cleanupOnce();
                return OresFuture.completed(OresNotification.complete());
            }
            active = exposed;
        }

        source.whenCompleteRuntime((notification, failure) -> {
            Throwable terminalFailure = failure == null ? null : OresFuture.unwrap(failure);
            synchronized (gate) {
                if (terminalFailure != null || notification == null ||
                        (notification != null && notification.isComplete())) {
                    terminal = true;
                }
            }

            if (notification == null && terminalFailure == null) {
                terminalFailure = new IllegalStateException(
                        "rx-ores source completed a pull with null notification");
            }

            if (!exposed.isDone()) {
                if (terminalFailure == null) {
                    exposed.completeFromRuntime(notification);
                } else if (source.isCancelled()) {
                    exposed.cancel(true);
                } else {
                    exposed.failFromRuntime(terminalFailure);
                }
            }

            if (terminalFailure != null || (notification != null && notification.isComplete())) {
                cleanupOnce();
            }

            synchronized (gate) {
                if (active == exposed) active = null;
                pulling = false;
            }
        });

        return exposed;
    }

    public final boolean cancel() {
        OresFuture<OresNotification<T>> pending;
        synchronized (gate) {
            if (cancelled || terminal) return false;
            cancelled = true;
            terminal = true;
            pulling = false;
            pending = active;
            active = null;
        }
        if (pending != null) pending.cancel(true);
        cleanupOnce();
        return true;
    }

    public final boolean isCancelled() {
        synchronized (gate) { return cancelled; }
    }

    public final boolean isTerminated() {
        synchronized (gate) { return terminal; }
    }

    protected abstract OresFuture<OresNotification<T>> nextFromRuntime();

    protected void cancelFromRuntime() { }

    private void cleanupOnce() {
        if (!cleanupOnce.compareAndSet(false, true)) return;
        try {
            cancelFromRuntime();
        } catch (RuntimeException | Error ignored) {
            // Terminal state is authoritative; cleanup must remain exactly-once.
        }
    }
}
