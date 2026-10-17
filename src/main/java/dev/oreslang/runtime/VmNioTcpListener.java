package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

/**
 * Supervisor-owned std/net TCP facade using the owning VM's NIO selectors.
 * No listener or connection allocates a selector thread. The VM owns the
 * readiness/notification executors; this facade only owns socket descriptors.
 *
 * The one-operation-per-channel lease in NioReadinessGroup is intentionally
 * conservative: duplex or cross-actor socket access is not authorized here.
 */
public final class VmNioTcpListener implements AutoCloseable {
    private final NioReadinessGroup readiness;
    private final ServerSocketChannel server;
    private final int maxConnections;
    private final int maxPending;
    private final Object gate = new Object();
    private final Set<Connection> connections = new HashSet<>();
    private OresFuture<Connection> pendingAccept;
    private int admittedOperations; // guarded by gate; includes Future callbacks
    private boolean closed;

    static VmNioTcpListener loopback(NioReadinessGroup readiness, int port,
                                     int backlog, int maxConnections,
                                     int maxPending) throws IOException {
        Objects.requireNonNull(readiness, "readiness");
        if (port < 0 || port > 65535 || backlog < 1 || maxConnections < 1
                || maxConnections > 65536 || maxPending < 1 || maxPending > 65536)
            throw new IllegalArgumentException("invalid TCP listener bounds");
        ServerSocketChannel server = ServerSocketChannel.open();
        try {
            server.configureBlocking(false);
            // The source facade explicitly accepts "127.0.0.1", not a
            // platform-dependent IPv4/IPv6 loopback address.
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), backlog);
            return new VmNioTcpListener(readiness, server, maxConnections, maxPending);
        } catch (IOException | RuntimeException | Error failure) {
            try { server.close(); }
            catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private VmNioTcpListener(NioReadinessGroup readiness, ServerSocketChannel server,
                             int maxConnections, int maxPending) {
        this.readiness = readiness;
        this.server = server;
        this.maxConnections = maxConnections;
        this.maxPending = maxPending;
    }

    public int localPort() {
        try { return ((InetSocketAddress) server.getLocalAddress()).getPort(); }
        catch (IOException closedSocket) { throw new IllegalStateException("TCP listener closed", closedSocket); }
    }

    public boolean isTerminated() {
        synchronized (gate) { return closed; }
    }

    int pendingOperations() {
        synchronized (gate) { return admittedOperations; }
    }

    private boolean reserveOperation() {
        synchronized (gate) {
            if (closed || admittedOperations >= maxPending) return false;
            ++admittedOperations;
            return true;
        }
    }

    private void releaseOperation() {
        synchronized (gate) {
            if (admittedOperations <= 0)
                throw new IllegalStateException("TCP operation reservation underflow");
            --admittedOperations;
        }
    }

    public OresFuture<Connection> acceptAsync() {
        final OresFuture<SocketChannel> source;
        final OresFuture<Connection> exported;
        synchronized (gate) {
            if (closed) return OresFuture.failed(new IOException("TCP listener closed"));
            if (connections.size() >= maxConnections)
                return OresFuture.failed(new RejectedExecutionException("TCP connection capacity exhausted"));
            if (pendingAccept != null)
                return OresFuture.failed(new IllegalStateException("listener already has a pending accept"));
            if (admittedOperations >= maxPending)
                return OresFuture.failed(new RejectedExecutionException("TCP operation capacity exhausted"));
            ++admittedOperations;
            // A single pending listener operation is stricter than maxPending,
            // and is required by the manager's exclusive readiness-key lease.
            try {
                source = readiness.acceptAsync(server);
                exported = new OresFuture<>(() -> source.cancel(false), () -> { });
                pendingAccept = exported;
            } catch (RuntimeException | Error failure) {
                --admittedOperations;
                throw failure;
            }
        }
        source.whenCompleteRuntime((socket, failure) -> {
            Connection connection = null;
            try {
                // Exported cancellation first revokes this source Future.
                // Its synchronous cancellation notification must not settle
                // the exported Future before the caller's cancel CAS does.
                if (source.isCancelled()) return;
                if (failure != null) {
                    exported.failFromRuntime(failure);
                    return;
                }
                synchronized (gate) {
                    if (!closed && connections.size() < maxConnections && !exported.isDone()) {
                        connection = new Connection(socket);
                        connections.add(connection);
                    }
                }
                if (connection == null) {
                    socket.close();
                    exported.failFromRuntime(new IOException("TCP listener closed or connection quota exhausted"));
                } else if (!exported.completeFromRuntime(connection)) {
                    connection.close();
                }
            } catch (IOException | RuntimeException | Error problem) {
                if (connection != null) connection.close();
                else if (socket != null) {
                    try { socket.close(); } catch (IOException closeFailure) { problem.addSuppressed(closeFailure); }
                }
                exported.failFromRuntime(problem);
                if (problem instanceof VirtualMachineError fatal) throw fatal;
                if (problem instanceof ThreadDeath fatal) throw fatal;
            } finally {
                synchronized (gate) {
                    if (pendingAccept == exported) pendingAccept = null;
                }
                releaseOperation();
            }
        });
        return exported;
    }

    public OresFuture<Void> closeAsync() {
        close();
        return OresFuture.completed(null);
    }

    @Override public void close() {
        final OresFuture<Connection> pending;
        final List<Connection> snapshot;
        synchronized (gate) {
            if (closed) return;
            closed = true;
            pending = pendingAccept;
            snapshot = new ArrayList<>(connections);
        }
        // Closing the caller-owned listener revokes the readiness registration.
        // NIO terminal notification will settle any pending source Future.
        try { server.close(); } catch (IOException ignored) { }
        if (pending != null) pending.cancel(false);
        for (Connection connection : snapshot) connection.close();
    }

    public final class Connection implements AutoCloseable {
        private final SocketChannel channel;
        private boolean terminated;
        // Only terminal-exported operations are retained; each removes itself
        // on settlement. Guarded by the listener gate with 'terminated'.
        private final Set<OresFuture<?>> outstanding = new HashSet<>();

        private Connection(SocketChannel channel) { this.channel = channel; }

        private <T> OresFuture<T> track(OresFuture<T> future) {
            boolean shutdown;
            synchronized (gate) {
                shutdown = closed || terminated;
                if (!shutdown && !future.isDone()) outstanding.add(future);
            }
            future.whenCompleteRuntime((value, failure) -> {
                synchronized (gate) { outstanding.remove(future); }
            });
            if (shutdown && !future.isDone()) {
                // The kernel may already have committed a write; a failed
                // cancellation is not a license to fabricate a clean cancel.
                // Closing the socket below forces ordinary terminal delivery.
                future.cancel(false);
            }
            return future;
        }

        public VmNioTcpListener ownerListener() { return VmNioTcpListener.this; }

        private Throwable reserveSocketOperation() {
            synchronized (gate) {
                if (closed || terminated) return new IOException("TCP connection closed");
                if (admittedOperations >= maxPending)
                    return new RejectedExecutionException("TCP operation capacity exhausted");
                ++admittedOperations;
                return null;
            }
        }

        public OresFuture<List<Long>> readBytesAsync(int maximum) {
            Throwable denial = reserveSocketOperation();
            if (denial != null) return OresFuture.failed(denial);
            final OresFuture<byte[]> raw;
            try {
                raw = readiness.readAsync(channel, maximum);
            } catch (RuntimeException | Error failure) {
                releaseOperation();
                throw failure;
            }
            OresFuture<List<Long>> exposed = new OresFuture<>(() -> raw.cancel(false), () -> { });
            raw.whenCompleteRuntime((bytes, failure) -> {
                try {
                    // Internal read revocation can synchronously notify this
                    // waiter during exposed.cancel admission. Leave the
                    // exported terminal CAS to the cancelling caller.
                    if (raw.isCancelled()) return;
                    if (failure != null) {
                        exposed.failFromRuntime(failure);
                        return;
                    }
                    if (exposed.isDone()) return;
                    ArrayList<Long> list = new ArrayList<>(bytes.length);
                    for (byte b : bytes) list.add((long) (b & 0xff));
                    exposed.completeFromRuntime(List.copyOf(list));
                } catch (RuntimeException | Error mappingFailure) {
                    exposed.failFromRuntime(mappingFailure);
                    if (mappingFailure instanceof VirtualMachineError fatal) throw fatal;
                    if (mappingFailure instanceof ThreadDeath fatal) throw fatal;
                } finally {
                    releaseOperation();
                }
            });
            return track(exposed);
        }

        public OresFuture<Integer> writeBytesAsync(List<?> bytes) {
            if (bytes == null || bytes.size() > 65536)
                return OresFuture.failed(new IllegalArgumentException("invalid TCP write bytes"));
            byte[] snapshot = new byte[bytes.size()];
            for (int i = 0; i < snapshot.length; i++) {
                Object value = bytes.get(i);
                if (!(value instanceof Long) && !(value instanceof Integer))
                    return OresFuture.failed(new IllegalArgumentException("TCP bytes must be integers"));
                long b = ((Number) value).longValue();
                if (b < 0 || b > 255)
                    return OresFuture.failed(new IllegalArgumentException("TCP byte outside 0..255"));
                snapshot[i] = (byte) b;
            }
            Throwable denial = reserveSocketOperation();
            if (denial != null) return OresFuture.failed(denial);
            final OresFuture<Integer> written;
            try {
                written = readiness.writeAsync(channel, snapshot);
            } catch (RuntimeException | Error failure) {
                releaseOperation();
                throw failure;
            }
            written.whenCompleteRuntime((value, failure) -> releaseOperation());
            return track(written);
        }

        public OresFuture<Void> closeAsync() {
            close();
            return OresFuture.completed(null);
        }

        @Override public void close() {
            final List<OresFuture<?>> snapshot;
            synchronized (gate) {
                if (terminated) return;
                terminated = true;
                connections.remove(this);
                snapshot = new ArrayList<>(outstanding);
            }
            // Revoke only still-cancellable operations. An already committed
            // kernel write may instead fail or finish after descriptor close,
            // but must never be reported as cleanly cancelled.
            for (OresFuture<?> pending : snapshot) pending.cancel(false);
            try { channel.close(); } catch (IOException ignored) { }
        }
    }
}
