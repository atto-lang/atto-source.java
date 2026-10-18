package dev.oreslang.runtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
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
            host.whenComplete((result, error) -> lease.close());
            return host.thenApply(HttpResponse::statusCode);
        } catch (RuntimeException | Error failure) {
            lease.close();
            throw failure;
        }
    }

    @Override public synchronized void close() {
        closed = true; // no false early refund of in-flight OS work
    }

    private static void requireHost() {
        if (ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution())
            throw new SecurityException("untrusted actor cannot invoke host HTTP probe");
    }
}
