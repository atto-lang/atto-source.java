package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host/runtime cancellation authority with directed, one-way links.
 *
 * <p>Cancellation of any upstream token cancels this source; cancelling this
 * source cannot cancel any upstream. Links transfer no Task ownership, actor
 * identity or execution rights. Sources unsubscribe from their upstreams on
 * cancellation/close to avoid retaining completed scopes indefinitely.</p>
 */
public final class OresCancellationSource implements AutoCloseable {
    private final AtomicBoolean requested = new AtomicBoolean();
    private final Object gate = new Object();
    private final List<OresCancellationToken.Registration> upstreams =
            new ArrayList<>();
    private final OresFuture<Void> notification = new OresFuture<>();
    private final OresCancellationToken token = new OresCancellationToken(this);

    public OresCancellationToken token() {
        return token;
    }

    public boolean isCancellationRequested() {
        return requested.get();
    }

    OresFuture<Void> notification() {
        return notification;
    }

    /** OR-composition: cancellation of ANY upstream requests cancellation. */
    public static OresCancellationSource linked(OresCancellationToken... upstream) {
        Objects.requireNonNull(upstream, "upstream");
        OresCancellationSource child = new OresCancellationSource();
        try {
            for (OresCancellationToken parent : upstream) {
                Objects.requireNonNull(parent, "linked cancellation token");
                OresCancellationToken.Registration registration =
                        parent.onCancellationRequested(() -> OresCancellationDispatcher.request(child));
                synchronized (child.gate) {
                    if (child.requested.get()) {
                        registration.close();
                    } else {
                        child.upstreams.add(registration);
                    }
                }
            }
            return child;
        } catch (RuntimeException | Error failure) {
            child.close();
            throw failure;
        }
    }

    /**
     * Request cancellation. The claim is atomic and idempotent.
     * Host listeners are notified only after all upstream subscriptions are
     * detached; their callbacks should not block scheduler or actor carriers.
     */
    public boolean cancel() {
        if (!requested.compareAndSet(false, true)) return false;
        final List<OresCancellationToken.Registration> prior;
        synchronized (gate) {
            prior = new ArrayList<>(upstreams);
            upstreams.clear();
        }
        for (OresCancellationToken.Registration link : prior) link.close();
        notification.completeFromRuntime(null);
        return true;
    }

    @Override
    public void close() {
        cancel();
    }
}
