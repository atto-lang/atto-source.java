package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Read-only host/runtime cancellation observation capability.
 *
 * <p>Unlike OresFuture, a Token does not expose cancel(), completion or
 * producer authority. Registrations are host-only; callbacks must never
 * directly execute guest continuations and may run on a cancelling thread.
 * Task source lowering will require scheduler-affine delivery.</p>
 */
public final class OresCancellationToken {
    /** Detaching a subscription never cancels its producer. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override void close();
    }

    private final OresCancellationSource source;

    OresCancellationToken(OresCancellationSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Internal directed-link registration only; never a guest capability. */
    OresCancellationSource ownerSource() {
        return source;
    }

    public boolean isCancellationRequested() {
        return source.isCancellationRequested();
    }

    /** Executes the callback at most once when cancellation is published. */
    public Registration onCancellationRequested(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        OresFuture.RuntimeWaiterRegistration<Void> subscription =
                source.notification().whenCompleteRuntimeCancellable(
                        (ignored, failure) -> callback.run());
        return subscription::detach;
    }
}
