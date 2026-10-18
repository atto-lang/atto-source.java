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
    /** Internal directed children, processed before publishing host observers. */
    private final List<OresCancellationSource> linkedChildren = new ArrayList<>();
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

    int linkedChildCountForTest() {
        synchronized (gate) { return linkedChildren.size(); }
    }

    /** OR-composition: cancellation of ANY upstream requests cancellation. */
    public static OresCancellationSource linked(OresCancellationToken... upstream) {
        Objects.requireNonNull(upstream, "upstream");
        OresCancellationSource child = new OresCancellationSource();
        try {
            for (OresCancellationToken parent : upstream) {
                Objects.requireNonNull(parent, "linked cancellation token");
                OresCancellationToken.Registration registration =
                        parent.ownerSource().registerLinkedChild(child);
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
     * Internal link only: ordinary cancellation-token observers must not run
     * before sibling and transitively linked producers have been revoked.
     * Registrations have no ownership/actor identity and detach one way.
     */
    private OresCancellationToken.Registration registerLinkedChild(
            OresCancellationSource child) {
        Objects.requireNonNull(child, "child");
        boolean cancelledAlready;
        synchronized (gate) {
            cancelledAlready = requested.get();
            if (!cancelledAlready) linkedChildren.add(child);
        }
        if (cancelledAlready) {
            OresCancellationDispatcher.request(child);
        }
        return () -> {
            synchronized (gate) {
                linkedChildren.remove(child);
            }
        };
    }

    /** Called by the package-private iterative directed-cancellation engine. */
    boolean claimCancellation() {
        return requested.compareAndSet(false, true);
    }

    /** Detach inbound references and snapshot children without notifying hosts. */
    List<OresCancellationSource> collectLinkedChildren() {
        final List<OresCancellationToken.Registration> prior;
        final List<OresCancellationSource> children;
        synchronized (gate) {
            prior = new ArrayList<>(upstreams);
            upstreams.clear();
            children = new ArrayList<>(linkedChildren);
            linkedChildren.clear();
        }
        for (OresCancellationToken.Registration link : prior) link.close();
        return children;
    }

    /** Ordinary observers are published only after graph revocation. */
    void publishCancellation() {
        notification.completeFromRuntime(null);
    }

    /**
     * Request cancellation and propagate it through all directed children
     * before delivering ordinary host completion subscribers. The boolean
     * reports which caller claimed this source, not physical Task quiescence.
     */
    public boolean cancel() {
        return OresCancellationDispatcher.request(this);
    }

    @Override
    public void close() {
        cancel();
    }
}
