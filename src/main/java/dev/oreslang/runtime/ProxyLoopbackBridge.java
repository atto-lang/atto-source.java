package dev.oreslang.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trusted current-runtime bridge from one actor turn to the bounded loopback
 * outbound HTTP proof.
 *
 * <p>This is deliberately package-private and accepts only a host-owned output
 * sink. It is not a guest ABI, not an arbitrary egress primitive, and not a
 * CONNECT implementation.</p>
 *
 * <p>Logical cancellation and physical retirement are intentionally separate.
 * The exposed actor-owned Future revokes the operation and cancels a still
 * pending HTTP acquisition. The physical copy itself is admitted through the
 * current AsyncNativeIo operation ledger, so actor/context teardown cannot
 * reclaim state until that worker actually exits.</p>
 */
final class ProxyLoopbackBridge {
    record Receipt(int status, List<Map.Entry<String, String>> headers,
                   long bytesTransferred) {
        Receipt {
            Objects.requireNonNull(headers, "headers");
            headers = headers.stream()
                    .map(field -> Map.entry(
                            Objects.requireNonNull(field, "header").getKey(),
                            field.getValue()))
                    .toList();
            if (status < 200 || status > 599)
                throw new IllegalArgumentException("invalid final response status");
            if (bytesTransferred < 0)
                throw new IllegalArgumentException("negative body byte count");
        }
    }

    private static final class Revocation {
        private final AtomicBoolean requested = new AtomicBoolean();
        private final AtomicReference<CompletableFuture<ProxyOutboundLoopback.Response>>
                opening = new AtomicReference<>();

        void request() {
            requested.set(true);
            CompletableFuture<ProxyOutboundLoopback.Response> pending = opening.get();
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }

        boolean requested() { return requested.get(); }

        void track(CompletableFuture<ProxyOutboundLoopback.Response> pending) {
            Objects.requireNonNull(pending, "pending");
            if (!opening.compareAndSet(null, pending))
                throw new IllegalStateException("HTTP acquisition already tracked");
            if (requested.get() && !pending.isDone()) pending.cancel(true);
        }

        void clear(CompletableFuture<ProxyOutboundLoopback.Response> pending) {
            opening.compareAndSet(pending, null);
        }
    }

    /**
     * Package-private trusted runtime seam. Production always uses ContextAccess;
     * tests may supply the same actor/native-I/O ownership primitives without
     * constructing a Truffle Env. This is not guest-reachable.
     */
    interface RuntimeAccess {
        ActorRuntime actors();
        void requireNetPermission(String endpoint, String operation);
        <T> OresFuture<T> submitNet(String endpoint, String operation, Callable<T> work);
    }

    private record ContextAccess(OresContext context) implements RuntimeAccess {
        ContextAccess {
            Objects.requireNonNull(context, "context");
        }
        @Override public ActorRuntime actors() { return context.actors(); }
        @Override public void requireNetPermission(String endpoint, String operation) {
            context.requirePermission(RuntimePermissions.Permission.NET, endpoint, operation);
        }
        @Override public <T> OresFuture<T> submitNet(
                String endpoint, String operation, Callable<T> work) {
            // Re-check the exact scoped endpoint at the physical publication
            // boundary. Do not collapse a reviewed endpoint grant into the
            // coarse NETWORK capability between actor authorization and I/O.
            context.requirePermission(
                    RuntimePermissions.Permission.NET, endpoint, operation);
            return context.nativeIo().submit(operation, work);
        }
    }

    private ProxyLoopbackBridge() { }

    static OresFuture<Receipt> copy(
            OresContext context,
            URI target,
            String method,
            OutputStream hostSink,
            long maxResponseBytes) {
        return copy(new ContextAccess(context), target, method, hostSink, maxResponseBytes);
    }

    static OresFuture<Receipt> copy(
            RuntimeAccess access,
            URI target,
            String method,
            OutputStream hostSink,
            long maxResponseBytes) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(hostSink, "hostSink");

        ActorRuntime actors = Objects.requireNonNull(access.actors(), "actors");
        if (!ActorRuntime.inActorExecution()
                || ActorRuntime.currentActorRuntime() != actors) {
            throw new SecurityException(
                    "proxy loopback bridge requires an actor turn owned by its runtime");
        }

        // Canonicalize/deny before looking up authority. The only currently
        // admitted endpoint is literal IPv4 loopback.
        ProxyOutboundLoopback.validateTarget(target, method);
        String endpoint = "127.0.0.1:" + target.getPort();
        access.requireNetPermission(endpoint, "proxy.loopback.copy");

        Revocation revocation = new Revocation();
        OresFuture<Receipt> exposed = new OresFuture<>(revocation::request);

        // Ownership is established before any native worker can be published.
        // If the actor is already stopping, ownCurrentActorFuture cancels this
        // Future and no physical operation is admitted.
        actors.ownCurrentActorFuture(exposed);
        if (exposed.isCancelled()) return exposed;

        final OresFuture<Receipt> physical;
        try {
            physical = Objects.requireNonNull(
                    access.submitNet(
                            endpoint,
                            "proxy.loopback.copy.physical",
                            () -> copyOnNativeWorker(
                                    target, method, hostSink, maxResponseBytes, revocation)),
                    "runtime returned null physical I/O future");
        } catch (RuntimeException | Error publicationFailure) {
            // Ownership must precede physical publication, so a synchronous
            // publication denial can occur after the Future is actor-owned.
            // Settle that Future before propagating or actor teardown could
            // wait forever on a Future that can no longer make progress.
            exposed.failFromRuntime(publicationFailure);
            throw publicationFailure;
        }

        physical.whenCompleteRuntime((receipt, failure) -> {
            if (exposed.isDone()) return;
            if (failure == null) {
                exposed.completeFromRuntime(receipt);
                return;
            }
            Throwable cause = OresFuture.unwrap(failure);
            if (revocation.requested() || cause instanceof CancellationException) {
                exposed.cancel(false);
            } else {
                exposed.failFromRuntime(cause);
            }
        });
        return exposed;
    }

    private static Receipt copyOnNativeWorker(
            URI target,
            String method,
            OutputStream hostSink,
            long maxResponseBytes,
            Revocation revocation) throws IOException {
        if (revocation.requested())
            throw new CancellationException("proxy loopback request revoked before start");

        // Keep the transport operation-local until a pooled transport can be
        // represented by current AsyncNativeIo IoLoan/resource generations.
        // Ordinary transport close is intentionally nonjoining; the actor
        // bridge must hold its AsyncNativeIo operation until HttpClient itself
        // reports physical termination.
        ProxyOutboundLoopback transport =
                new ProxyOutboundLoopback(1, maxResponseBytes);
        try {
            if (revocation.requested())
                throw new CancellationException("proxy loopback request revoked before acquisition");

            CompletableFuture<ProxyOutboundLoopback.Response> opening =
                    transport.request(target, method);
            revocation.track(opening);
            final ProxyOutboundLoopback.Response response;
            try {
                response = opening.join();
            } catch (CompletionException failed) {
                Throwable cause = OresFuture.unwrap(failed);
                if (cause instanceof CancellationException cancelled) throw cancelled;
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IOException("proxy loopback acquisition failed", cause);
            } finally {
                revocation.clear(opening);
            }

            try (response) {
                if (revocation.requested())
                    throw new CancellationException("proxy loopback request revoked after headers");
                long bytes = response.streamBody(hostSink, revocation::requested);
                if (revocation.requested())
                    throw new CancellationException("proxy loopback request revoked after body");
                return new Receipt(response.status(), response.headers(), bytes);
            }
        } finally {
            transport.closeAndJoin();
        }
    }
}
