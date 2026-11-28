package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Supervisor-owned TCP facade backed by one VM-owned NIO readiness domain.
 *
 * <p>No listener or connection creates a selector thread. All channels are
 * nonblocking and use one-shot readiness watches. One connection admits at
 * most one outstanding socket operation at a time; duplex/cross-actor socket
 * access is intentionally not authorized by this first contract.</p>
 */
public final class VmNioTcpListener implements AutoCloseable {
    private static final int MAX_IO_BYTES = 65_536;

    private final NioReadinessGroup readiness;
    private final ServerSocketChannel server;
    private final int maxConnections;
    private final int maxPending;
    private final Runnable onTerminated;
    private final Object gate = new Object();
    private final Set<Connection> connections = new HashSet<>();
    private final OresFuture<Void> listenerClosed = new OresFuture<>();

    private OresFuture<Connection> pendingAccept;
    private int admittedOperations;
    private boolean closed;
    private boolean terminationReported;

    /**
     * Permission-gated source/runtime entrypoint.
     *
     * <p>Listeners are supervisor-owned. Actor turns cannot create process
     * listeners, and scoped NET permission is checked before the descriptor is
     * opened. Port 0 checks the exact requested resource (127.0.0.1:0); the
     * kernel-selected port is not used to broaden authorization.</p>
     */
    public static VmNioTcpListener loopback(
            OresContext context,
            int port,
            int backlog,
            int maxConnections,
            int maxPending) throws IOException {
        Objects.requireNonNull(context, "context");
        if (ActorRuntime.inActorExecution()) {
            throw new SecurityException(
                    "TCP listeners belong to the supervisor, not an actor turn");
        }
        validateBounds(port, backlog, maxConnections, maxPending);
        context.requirePermission(
                RuntimePermissions.Permission.NET,
                "127.0.0.1:" + port,
                "net.listen");
        return context.vm().openNioTcpLoopback(
                port, backlog, maxConnections, maxPending);
    }

    /** Runtime/test path after permission admission has already succeeded. */
    static VmNioTcpListener openOwned(
            NioReadinessGroup readiness,
            int port,
            int backlog,
            int maxConnections,
            int maxPending,
            Runnable onTerminated) throws IOException {
        Objects.requireNonNull(readiness, "readiness");
        Objects.requireNonNull(onTerminated, "onTerminated");
        validateBounds(port, backlog, maxConnections, maxPending);

        ServerSocketChannel server = ServerSocketChannel.open();
        try {
            server.configureBlocking(false);
            server.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), port), backlog);
            return new VmNioTcpListener(
                    readiness, server, maxConnections, maxPending, onTerminated);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                server.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    static VmNioTcpListener loopback(
            NioReadinessGroup readiness,
            int port,
            int backlog,
            int maxConnections,
            int maxPending) throws IOException {
        return openOwned(
                readiness, port, backlog, maxConnections, maxPending, () -> { });
    }

    private static void validateBounds(
            int port, int backlog, int maxConnections, int maxPending) {
        if (port < 0 || port > 65_535
                || backlog < 1 || backlog > 65_536
                || maxConnections < 1 || maxConnections > 65_536
                || maxPending < 1 || maxPending > 65_536) {
            throw new IllegalArgumentException("invalid TCP listener bounds");
        }
    }

    private VmNioTcpListener(
            NioReadinessGroup readiness,
            ServerSocketChannel server,
            int maxConnections,
            int maxPending,
            Runnable onTerminated) {
        this.readiness = readiness;
        this.server = server;
        this.maxConnections = maxConnections;
        this.maxPending = maxPending;
        this.onTerminated = onTerminated;
    }

    public int localPort() {
        try {
            return ((InetSocketAddress) server.getLocalAddress()).getPort();
        } catch (IOException failure) {
            throw new IllegalStateException("TCP listener closed", failure);
        }
    }

    public boolean isTerminated() {
        synchronized (gate) {
            return terminatedLocked();
        }
    }

    int pendingOperations() {
        synchronized (gate) {
            return admittedOperations;
        }
    }

    private boolean terminatedLocked() {
        return closed
                && admittedOperations == 0
                && pendingAccept == null
                && connections.isEmpty();
    }

    private void releaseOperation() {
        synchronized (gate) {
            if (admittedOperations <= 0) {
                throw new IllegalStateException(
                        "TCP operation reservation underflow");
            }
            --admittedOperations;
            gate.notifyAll();
        }
        reportTerminationIfQuiesced();
    }

    private void reportTerminationIfQuiesced() {
        boolean report = false;
        synchronized (gate) {
            if (terminatedLocked() && !terminationReported) {
                terminationReported = true;
                report = true;
            }
        }
        if (!report) return;

        try {
            listenerClosed.completeFromRuntime(null);
        } finally {
            onTerminated.run();
        }
    }

    public OresFuture<Connection> acceptAsync() {
        final AtomicReference<OresFuture<Integer>> watchRef =
                new AtomicReference<>();
        final OresFuture<Connection> exported;

        synchronized (gate) {
            if (closed) {
                return OresFuture.failed(new IOException("TCP listener closed"));
            }
            if (pendingAccept != null) {
                return OresFuture.failed(new IllegalStateException(
                        "listener already has a pending accept"));
            }
            if (connections.size() >= maxConnections) {
                return OresFuture.failed(new RejectedExecutionException(
                        "TCP connection capacity exhausted"));
            }
            if (admittedOperations >= maxPending) {
                return OresFuture.failed(new RejectedExecutionException(
                        "TCP operation capacity exhausted"));
            }

            ++admittedOperations;
            exported = new OresFuture<>(
                    () -> cancelWatch(watchRef),
                    this::releaseOperation);
            pendingAccept = exported;
        }

        // Cancellation may win before the selector reports anything. Clear
        // the logical accept lease on every terminal path, independently of
        // the underlying readiness watch's eventual callback.
        exported.whenCompleteRuntime((value, failure) ->
                clearPendingAccept(exported));
        armAccept(exported, watchRef);
        return exported;
    }

    private void armAccept(
            OresFuture<Connection> exported,
            AtomicReference<OresFuture<Integer>> watchRef) {
        if (exported.isDone()) return;

        try {
            SocketChannel socket = server.accept();
            if (socket != null) {
                socket.configureBlocking(false);
                completeAccepted(exported, socket);
                return;
            }
        } catch (IOException | RuntimeException failure) {
            completeAcceptFailure(exported, failure);
            return;
        }

        OresFuture<Integer> watch =
                readiness.watch(server, SelectionKey.OP_ACCEPT);
        watchRef.set(watch);
        if (exported.isDone()) {
            if (watchRef.compareAndSet(watch, null)) watch.cancel(false);
            return;
        }

        watch.whenCompleteRuntime((ready, failure) -> {
            watchRef.compareAndSet(watch, null);
            if (exported.isDone()) return;
            if (failure != null) {
                completeAcceptFailure(exported, failure);
                return;
            }
            armAccept(exported, watchRef);
        });
    }

    private void completeAccepted(
            OresFuture<Connection> exported,
            SocketChannel socket) {
        Connection connection = null;
        try {
            synchronized (gate) {
                if (!closed
                        && pendingAccept == exported
                        && !exported.isDone()
                        && connections.size() < maxConnections) {
                    connection = new Connection(socket);
                    connections.add(connection);
                }
            }

            if (connection == null) {
                socket.close();
                exported.failFromRuntime(new IOException(
                        "TCP listener closed or connection quota exhausted"));
            } else if (!exported.completeFromRuntime(connection)) {
                connection.close();
            }
        } catch (IOException | RuntimeException | Error failure) {
            if (connection != null) {
                connection.close();
            } else {
                try {
                    socket.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            exported.failFromRuntime(failure);
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
        } finally {
            clearPendingAccept(exported);
        }
    }

    private void completeAcceptFailure(
            OresFuture<Connection> exported,
            Throwable failure) {
        try {
            if (!exported.isDone()) exported.failFromRuntime(failure);
        } finally {
            clearPendingAccept(exported);
        }
    }

    private void clearPendingAccept(OresFuture<Connection> exported) {
        synchronized (gate) {
            if (pendingAccept == exported) {
                pendingAccept = null;
                gate.notifyAll();
            }
        }
        reportTerminationIfQuiesced();
    }

    private static void cancelWatch(
            AtomicReference<OresFuture<Integer>> watchRef) {
        OresFuture<Integer> watch = watchRef.getAndSet(null);
        if (watch != null) watch.cancel(false);
    }

    public OresFuture<Void> closeAsync() {
        close();
        return listenerClosed;
    }

    @Override
    public void close() {
        final OresFuture<Connection> accept;
        final List<Connection> snapshot;
        synchronized (gate) {
            if (closed) return;
            closed = true;
            accept = pendingAccept;
            snapshot = new ArrayList<>(connections);
            gate.notifyAll();
        }

        try {
            server.close();
        } catch (IOException ignored) {
            // Pending readiness/caller cancellation still owns terminal delivery.
        }
        if (accept != null) accept.cancel(false);
        for (Connection connection : snapshot) connection.close();
        reportTerminationIfQuiesced();
    }

    boolean awaitTerminated(long timeout, TimeUnit unit)
            throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (gate) {
            while (!terminatedLocked()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(gate, left);
            }
            return true;
        }
    }

    public final class Connection implements AutoCloseable {
        private final SocketChannel channel;
        private final Set<OresFuture<?>> outstanding = new HashSet<>();
        private final OresFuture<Void> connectionClosed = new OresFuture<>();
        private boolean terminated;
        private int activeOperations;

        private Connection(SocketChannel channel) {
            this.channel = channel;
        }

        public VmNioTcpListener ownerListener() {
            return VmNioTcpListener.this;
        }

        private Throwable reserveSocketOperation() {
            synchronized (gate) {
                if (closed || terminated) {
                    return new IOException("TCP connection closed");
                }
                if (activeOperations != 0) {
                    return new IllegalStateException(
                            "connection already has an outstanding socket operation");
                }
                if (admittedOperations >= maxPending) {
                    return new RejectedExecutionException(
                            "TCP operation capacity exhausted");
                }
                ++activeOperations;
                ++admittedOperations;
                return null;
            }
        }

        private void releaseSocketOperation() {
            boolean connectionQuiesced;
            synchronized (gate) {
                if (activeOperations <= 0) {
                    throw new IllegalStateException(
                            "TCP socket operation reservation underflow");
                }
                --activeOperations;
                connectionQuiesced = terminated && activeOperations == 0;
                gate.notifyAll();
            }
            releaseOperation();
            if (connectionQuiesced) completeConnectionCloseIfQuiesced();
        }

        private <T> OresFuture<T> track(OresFuture<T> future) {
            boolean shutdown;
            synchronized (gate) {
                shutdown = closed || terminated;
                if (!shutdown && !future.isDone()) outstanding.add(future);
            }
            future.whenCompleteRuntime((value, failure) -> {
                synchronized (gate) {
                    outstanding.remove(future);
                    gate.notifyAll();
                }
                completeConnectionCloseIfQuiesced();
            });
            if (shutdown && !future.isDone()) future.cancel(false);
            return future;
        }

        public OresFuture<List<Long>> readBytesAsync(int maximum) {
            if (maximum < 1 || maximum > MAX_IO_BYTES) {
                return OresFuture.failed(new IllegalArgumentException(
                        "invalid TCP read bound"));
            }
            Throwable denial = reserveSocketOperation();
            if (denial != null) return OresFuture.failed(denial);

            AtomicReference<OresFuture<Integer>> watchRef =
                    new AtomicReference<>();
            ByteBuffer buffer = ByteBuffer.allocate(maximum);
            OresFuture<List<Long>> exported = new OresFuture<>(
                    () -> cancelWatch(watchRef),
                    this::releaseSocketOperation);
            track(exported);
            armRead(buffer, exported, watchRef);
            return exported;
        }

        private void armRead(
                ByteBuffer buffer,
                OresFuture<List<Long>> exported,
                AtomicReference<OresFuture<Integer>> watchRef) {
            if (exported.isDone()) return;
            try {
                int read = channel.read(buffer);
                if (read < 0) {
                    exported.completeFromRuntime(List.of());
                    return;
                }
                if (read > 0) {
                    buffer.flip();
                    ArrayList<Long> bytes = new ArrayList<>(read);
                    while (buffer.hasRemaining()) {
                        bytes.add((long) (buffer.get() & 0xff));
                    }
                    exported.completeFromRuntime(List.copyOf(bytes));
                    return;
                }
            } catch (IOException | RuntimeException failure) {
                exported.failFromRuntime(failure);
                return;
            }

            OresFuture<Integer> watch =
                    readiness.watch(channel, SelectionKey.OP_READ);
            watchRef.set(watch);
            if (exported.isDone()) {
                if (watchRef.compareAndSet(watch, null)) watch.cancel(false);
                return;
            }
            watch.whenCompleteRuntime((ready, failure) -> {
                watchRef.compareAndSet(watch, null);
                if (exported.isDone()) return;
                if (failure != null) {
                    exported.failFromRuntime(failure);
                    return;
                }
                armRead(buffer, exported, watchRef);
            });
        }

        public OresFuture<Integer> writeBytesAsync(List<Long> bytes) {
            Objects.requireNonNull(bytes, "bytes");
            if (bytes.size() > MAX_IO_BYTES) {
                return OresFuture.failed(new IllegalArgumentException(
                        "TCP write exceeds bounded operation size"));
            }
            byte[] encoded = new byte[bytes.size()];
            for (int i = 0; i < bytes.size(); ++i) {
                Long value = bytes.get(i);
                if (value == null || value < 0 || value > 255) {
                    return OresFuture.failed(new IllegalArgumentException(
                            "TCP byte values must be in 0..255"));
                }
                encoded[i] = (byte) (long) value;
            }
            if (encoded.length == 0) return OresFuture.completed(0);

            Throwable denial = reserveSocketOperation();
            if (denial != null) return OresFuture.failed(denial);

            AtomicReference<OresFuture<Integer>> watchRef =
                    new AtomicReference<>();
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            OresFuture<Integer> exported = new OresFuture<>(
                    () -> cancelWatch(watchRef),
                    this::releaseSocketOperation);
            track(exported);
            armWrite(buffer, encoded.length, exported, watchRef);
            return exported;
        }

        private void armWrite(
                ByteBuffer buffer,
                int total,
                OresFuture<Integer> exported,
                AtomicReference<OresFuture<Integer>> watchRef) {
            if (exported.isDone()) return;
            try {
                while (buffer.hasRemaining()) {
                    int written = channel.write(buffer);
                    if (written == 0) break;
                }
                if (!buffer.hasRemaining()) {
                    exported.completeFromRuntime(total);
                    return;
                }
            } catch (IOException | RuntimeException failure) {
                exported.failFromRuntime(failure);
                return;
            }

            OresFuture<Integer> watch =
                    readiness.watch(channel, SelectionKey.OP_WRITE);
            watchRef.set(watch);
            if (exported.isDone()) {
                if (watchRef.compareAndSet(watch, null)) watch.cancel(false);
                return;
            }
            watch.whenCompleteRuntime((ready, failure) -> {
                watchRef.compareAndSet(watch, null);
                if (exported.isDone()) return;
                if (failure != null) {
                    exported.failFromRuntime(failure);
                    return;
                }
                armWrite(buffer, total, exported, watchRef);
            });
        }

        public OresFuture<Void> closeAsync() {
            close();
            return connectionClosed;
        }

        @Override
        public void close() {
            final List<OresFuture<?>> pending;
            synchronized (gate) {
                if (terminated) return;
                terminated = true;
                pending = new ArrayList<>(outstanding);
                gate.notifyAll();
            }

            try {
                channel.close();
            } catch (IOException ignored) {
                // Pending readiness/cancellation owns final operation state.
            }
            for (OresFuture<?> future : pending) future.cancel(false);
            completeConnectionCloseIfQuiesced();
        }

        private void completeConnectionCloseIfQuiesced() {
            boolean quiesced;
            synchronized (gate) {
                quiesced = terminated
                        && activeOperations == 0
                        && outstanding.isEmpty();
                if (quiesced) {
                    connections.remove(this);
                    gate.notifyAll();
                }
            }
            if (quiesced) {
                connectionClosed.completeFromRuntime(null);
                reportTerminationIfQuiesced();
            }
        }

        boolean awaitTerminated(long timeout, TimeUnit unit)
                throws InterruptedException {
            Objects.requireNonNull(unit, "unit");
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            synchronized (gate) {
                while (!(terminated
                        && activeOperations == 0
                        && outstanding.isEmpty())) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) return false;
                    TimeUnit.NANOSECONDS.timedWait(gate, left);
                }
                return true;
            }
        }
    }
}
