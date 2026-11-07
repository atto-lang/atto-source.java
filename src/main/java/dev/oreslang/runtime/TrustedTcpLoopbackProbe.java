package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-only REAL async TCP quota/lifetime probe. The trusted host fixes the
 * loopback destination; this is not a guest->broker API, DNS resolver, or
 * authorization to grant the UNTRUSTED NETWORK capability.
 *
 * The quota lease is held until AsynchronousSocketChannel.close() returns
 * and the channel is observed closed. Caller cancellation closes the actual
 * channel, unlike HTTP's deliberately independent caller response future.
 */
final class TrustedTcpLoopbackProbe implements AutoCloseable {
    private final TrustedHierarchicalNetworkGate gate;
    private final TrustedHierarchicalNetworkGate.Scope child;
    private final TrustedHierarchicalNetworkGate.Scope supervisor;
    private final TrustedHierarchicalNetworkGate.Scope tenant;
    private final InetSocketAddress destination;
    private final Set<Connection> owned = ConcurrentHashMap.newKeySet();
    private boolean closed;

    TrustedTcpLoopbackProbe(
            TrustedHierarchicalNetworkGate gate,
            TrustedHierarchicalNetworkGate.Scope child,
            TrustedHierarchicalNetworkGate.Scope supervisor,
            TrustedHierarchicalNetworkGate.Scope tenant,
            int trustedPort) {
        requireHost();
        if (trustedPort < 1 || trustedPort > 65535) {
            throw new IllegalArgumentException("invalid trusted loopback TCP port");
        }
        this.gate = Objects.requireNonNull(gate);
        this.child = Objects.requireNonNull(child);
        this.supervisor = Objects.requireNonNull(supervisor);
        this.tenant = Objects.requireNonNull(tenant);
        // Fixed literal IP: no DNS, proxy routing, or user-supplied endpoints.
        this.destination = new InetSocketAddress("127.0.0.1", trustedPort);
    }

    synchronized CompletableFuture<Connection> connect() {
        requireHost();
        if (closed) return CompletableFuture.failedFuture(
                new IllegalStateException("TCP probe closed"));
        var admission = gate.tryOpenTcp(child, supervisor, tenant);
        if (admission.lease() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "TCP quota denied: " + admission.status()));
        }

        var lease = admission.lease();
        AsynchronousSocketChannel channel;
        try {
            // Reserve and commit before opening a host socket.
            if (!lease.commitDispatch()) {
                lease.abortBeforeDispatch();
                return CompletableFuture.failedFuture(
                        new IllegalStateException("TCP dispatch revoked"));
            }
            channel = AsynchronousSocketChannel.open();
        } catch (IOException | RuntimeException | Error failure) {
            lease.close();
            return CompletableFuture.failedFuture(failure);
        }

        var connection = new Connection(channel, lease);
        var caller = new CompletableFuture<Connection>();
        owned.add(connection);
        caller.whenComplete((result, failure) -> {
            if (caller.isCancelled()) connection.close();
        });

        try {
            channel.connect(destination, null, new CompletionHandler<Void, Void>() {
                @Override public void completed(Void ignored, Void attachment) {
                    synchronized (TrustedTcpLoopbackProbe.this) {
                        if (closed || !channel.isOpen() || !caller.complete(connection)) {
                            connection.close();
                        }
                    }
                }
                @Override public void failed(Throwable failure, Void attachment) {
                    connection.close();
                    caller.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException | Error failure) {
            connection.close();
            caller.completeExceptionally(failure);
        }
        return caller;
    }

    final class Connection implements AutoCloseable {
        private final AsynchronousSocketChannel channel;
        private final TrustedHierarchicalNetworkGate.Lease lease;
        private final AtomicBoolean settled = new AtomicBoolean();
        private Connection(AsynchronousSocketChannel channel,
                           TrustedHierarchicalNetworkGate.Lease lease) {
            this.channel = channel;
            this.lease = lease;
        }
        boolean isOpen() { return channel.isOpen() && !settled.get(); }

        @Override public void close() {
            // AsynchronousChannel.close is immediate/nonparking. Concurrent
            // close and connect callbacks may arrive in either order.
            try {
                channel.close();
            } catch (IOException failure) {
                if (channel.isOpen()) {
                    throw new IllegalStateException(
                            "unable to close host TCP channel; retain quota lease", failure);
                }
            }
            if (!channel.isOpen() && settled.compareAndSet(false, true)) {
                owned.remove(this);
                lease.close();
            }
        }
    }

    @Override public synchronized void close() {
        requireHost();
        closed = true; // repeat attempts to close any channels still tracked
        RuntimeException first = null;
        for (var connection : owned) {
            try {
                connection.close();
            } catch (RuntimeException failure) {
                if (first == null) first = failure;
                else first.addSuppressed(failure);
            }
        }
        if (first != null) throw first;
        // Never claim closed OS channels just because an actor requested a
        // cancellation. A failed close retains the lease to fail closed.
    }

    private static void requireHost() {
        if (ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new SecurityException("trusted host exclusively owns TCP probe");
        }
    }
}
