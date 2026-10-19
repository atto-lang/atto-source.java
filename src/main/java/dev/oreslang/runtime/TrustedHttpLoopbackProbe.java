package dev.oreslang.runtime;

import java.net.URI;
import java.net.ProxySelector;
import java.net.Proxy;
import java.net.SocketAddress;
import java.io.IOException;
import java.util.List;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Experimental HOST-ONLY probe of real asynchronous HTTP quota accounting.
 * This is NOT a protected guest broker or a grant of untrusted networking.
 * The supervisor chooses the destination (fixed 127.0.0.1 port) and owns all
 * quota scopes; no guest actor can construct or call this package-private API.
 */
final class TrustedHttpLoopbackProbe implements AutoCloseable {
    private final TrustedHierarchicalNetworkGate gate;
    private final TrustedHierarchicalNetworkGate.Scope child;
    private final TrustedHierarchicalNetworkGate.Scope supervisor;
    private final TrustedHierarchicalNetworkGate.Scope tenant;
    private final HttpClient client;
    private final URI destination;
    // Host-owned pending transports, never caller-controlled futures.
    private final Set<CompletableFuture<HttpResponse<Void>>> active =
            ConcurrentHashMap.newKeySet();
    private boolean closed;

    TrustedHttpLoopbackProbe(TrustedHierarchicalNetworkGate gate,
            TrustedHierarchicalNetworkGate.Scope child,
            TrustedHierarchicalNetworkGate.Scope supervisor,
            TrustedHierarchicalNetworkGate.Scope tenant, int trustedPort) {
        requireHost();
        if (trustedPort < 1 || trustedPort > 65535) {
            throw new IllegalArgumentException("invalid trusted port");
        }
        this.gate = Objects.requireNonNull(gate);
        this.child = Objects.requireNonNull(child);
        this.supervisor = Objects.requireNonNull(supervisor);
        this.tenant = Objects.requireNonNull(tenant);
        this.destination = URI.create("http://127.0.0.1:" + trustedPort + "/");
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI target) {
                        return List.of(Proxy.NO_PROXY);
                    }
                    @Override public void connectFailed(
                        URI uri, SocketAddress address, IOException error) { }
                })
                .connectTimeout(Duration.ofSeconds(2)).build();
    }

    synchronized CompletableFuture<Integer> getStatus() {
        requireHost();
        if (closed) return CompletableFuture.failedFuture(
                new IllegalStateException("broker closed"));
        var admission = gate.tryStartHttp(child, supervisor, tenant);
        if (admission.lease() == null) return CompletableFuture.failedFuture(
                new IllegalStateException("HTTP quota denied: " + admission.status()));
        var lease = admission.lease();
        try {
            if (!lease.commitDispatch()) {
                lease.abortBeforeDispatch();
                return CompletableFuture.failedFuture(
                        new IllegalStateException("HTTP dispatch revoked"));
            }
            var request = HttpRequest.newBuilder(destination).GET()
                    .timeout(Duration.ofSeconds(3)).build();
            // Fully discard body before releasing permit. Cancellation of
            // the returned dependent future cannot refund an active request.
            CompletableFuture<HttpResponse<Void>> host =
                    client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            // An isolated caller future must not own or cancel the host I/O.
            // The host completion alone releases the in-flight permit.
            CompletableFuture<Integer> caller = new CompletableFuture<>();
            active.add(host);
            host.whenComplete((result, failure) -> {
                active.remove(host);
                lease.close();
                if (failure != null) caller.completeExceptionally(failure);
                else caller.complete(result.statusCode());
            });
            return caller;
        } catch (RuntimeException | Error failure) {
            lease.close();
            throw failure;
        }
    }

    /**
     * Pure admission fence: safe to invoke under the actor bridge monitor
     * during termination notification. No HTTP cancellation or network I/O.
     * Synchronized with getStatus() so any subsequent dispatch is rejected.
     */
    synchronized void fenceNewDispatch() {
        closed = true;
    }

    /**
     * Host-only bounded shutdown barrier. A successful cancellation request
     * or a completed caller future is not sufficient to return a cleanup
     * reservation to the allocator. Confirm HttpClient termination and its
     * outstanding host completion callbacks before reporting success.
     *
     * This proves termination at the JDK HTTP client boundary, NOT kernel-
     * observed socket quiescence. A lower-level socket census is still
     * required before treating this broker as production-safe.
     */
    @Override public void close() {
        requireHost();
        synchronized (this) {
            closed = true;
        }
        for (var host : active) host.cancel(true);
        client.shutdownNow();
        final boolean terminated;
        try {
            terminated = client.awaitTermination(Duration.ofMillis(500));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "HTTP client shutdown interrupted: cleanup reservation retained", interrupted);
        }
        if (!terminated || !client.isTerminated() || !active.isEmpty()) {
            throw new IllegalStateException(
                    "HTTP client not quiescent: cleanup reservation retained");
        }
    }

    /** Host-side assertion only; does not certify kernel socket teardown. */
    boolean clientTerminatedForHost() {
        requireHost();
        return client.isTerminated() && active.isEmpty();
    }

    private static void requireHost() {
        if (ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution())
            throw new SecurityException("untrusted actor cannot invoke host HTTP probe");
    }
}
