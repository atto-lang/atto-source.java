package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Experimental NONBLOCKING TCP -> OresFuture bridge. All socket operations,
 * selector registrations and close are owned by one dedicated reactor thread.
 * Guest code is NEVER invoked by this thread; OresFuture only schedules its
 * registered runtime continuations on their owning Oreslang scheduler.
 *
 * No HTTP or Oreslang-visible net API exists yet. Fail closed instead of
 * silently falling back to blocking Java socket streams or HttpServer.
 *
 * Bounds: active connections, accepted/read/write futures, per-operation byte
 * buffers and queued submission commands. The reactor may park in select()
 * only on its own host thread, never on an actor/root carrier.
 */
public final class NativeNioFutureReactor implements AutoCloseable {
    static final int MAX_IO_BYTES = 65536;
    private final Selector selector;
    private final ServerSocketChannel listener;
    private final Thread thread;
    private final int maxConnections;
    private final int maxPending;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong worstCommandQueueNanos = new AtomicLong();
    private final AtomicInteger peakPending = new AtomicInteger();
    // The fields below must be accessed on the dedicated reactor thread only.
    private final ArrayDeque<OresFuture<Connection>> accepts = new ArrayDeque<>();
    private final Set<Connection> connections = new HashSet<>();
    private volatile Throwable terminalFailure;
    private final OresFuture<Void> terminated = new OresFuture<>(() -> false, () -> { });

    public static NativeNioFutureReactor loopback(int port, int backlog,
                                           int maxConnections, int maxPending) throws IOException {
        if (port < 0 || port > 65535 || backlog <= 0 || backlog > 65535
                || maxConnections < 1 || maxPending < 1 || maxPending > 65536) {
            throw new IllegalArgumentException("invalid reactor limits");
        }
        Selector selector = Selector.open();
        ServerSocketChannel server = null;
        try {
            server = ServerSocketChannel.open();
            server.configureBlocking(false);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), backlog);
            server.register(selector, 0);
            return new NativeNioFutureReactor(selector, server, maxConnections, maxPending);
        } catch (IOException | RuntimeException failure) {
            if (server != null) try { server.close(); } catch (IOException ignored) { }
            try { selector.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    private NativeNioFutureReactor(Selector selector, ServerSocketChannel listener,
                                   int maxConnections, int maxPending) {
        this.selector = selector;
        this.listener = listener;
        this.maxConnections = maxConnections;
        this.maxPending = maxPending;
        this.thread = Thread.ofPlatform().daemon(true).name("ores-nio-reactor").unstarted(this::run);
        thread.start();
    }

    public int localPort() {
        try {
            return ((InetSocketAddress) listener.getLocalAddress()).getPort();
        } catch (IOException failure) {
            throw new IllegalStateException("TCP listener is closed", failure);
        }
    }

    int outstandingFutures() { return pending.get(); }
    int peakOutstandingFutures() { return peakPending.get(); }
    long maxCommandQueueNanos() { return worstCommandQueueNanos.get(); }

    private boolean reserve() {
        for (;;) {
            if (shuttingDown.get()) return false;
            int count = pending.get();
            if (count >= maxPending) return false;
            if (pending.compareAndSet(count, count + 1)) {
                peakPending.accumulateAndGet(count + 1, Math::max);
                return true;
            }
        }
    }

    private void post(Runnable runnable) {
        long queuedAt = System.nanoTime();
        commands.add(() -> {
            worstCommandQueueNanos.accumulateAndGet(
                    Math.max(0, System.nanoTime() - queuedAt), Math::max);
            runnable.run();
        });
        selector.wakeup();
    }

    public OresFuture<Connection> acceptAsync() {
        if (!reserve()) return OresFuture.failed(
                new RejectedExecutionException("TCP listener is closed or at capacity"));
        // Cancellation is deliberately declined until the operation's
        // kernel-registration/completion single-winner protocol is finalized.
        OresFuture<Connection> future = new OresFuture<>(() -> false, () -> { });
        post(() -> {
            if (shuttingDown.get()) {
                fail(future, new IOException("TCP reactor closing"));
                return;
            }
            accepts.add(future);
            updateAcceptInterest();
        });
        return future;
    }

    public final class Connection {
        private final SocketChannel channel;
        private ReadOp read;
        private WriteOp write;
        private final AtomicBoolean closeRequested = new AtomicBoolean();
        private final OresFuture<Void> closed = new OresFuture<>(() -> false, () -> { });
        private boolean terminated; // reactor-thread only

        private Connection(SocketChannel channel) { this.channel = channel; }

        /**
         * ABI representation for Oreslang Array<int>: owned immutable numeric data.
         * Conversion occurs on the completing runtime thread; never a guest
         * callback, and no byte array or ByteBuffer crosses the guest boundary.
         */
        public OresFuture<java.util.List<Long>> readBytesAsync(int maximum) {
            OresFuture<byte[]> read = readAsync(maximum);
            OresFuture<java.util.List<Long>> exported =
                    new OresFuture<>(() -> false, () -> { });
            read.whenCompleteRuntime((bytes, failure) -> {
                if (failure != null) {
                    exported.failFromRuntime(failure);
                } else {
                    java.util.ArrayList<Long> values = new java.util.ArrayList<>(bytes.length);
                    for (byte b : bytes) values.add((long) (b & 0xff));
                    exported.completeFromRuntime(java.util.List.copyOf(values));
                }
            });
            return exported;
        }

        public OresFuture<Integer> writeBytesAsync(java.util.List<?> bytes) {
            if (bytes == null || bytes.size() > MAX_IO_BYTES)
                return OresFuture.failed(new IllegalArgumentException("invalid TCP write bytes"));
            byte[] snapshot = new byte[bytes.size()];
            for (int i = 0; i < bytes.size(); i++) {
                Object item = bytes.get(i);
                if (!(item instanceof Long) && !(item instanceof Integer))
                    return OresFuture.failed(new IllegalArgumentException("TCP bytes must be integers"));
                long number = ((Number) item).longValue();
                if (number < 0 || number > 255)
                    return OresFuture.failed(new IllegalArgumentException("TCP byte outside 0..255"));
                snapshot[i] = (byte) number;
            }
            return writeAsync(snapshot);
        }

        public OresFuture<byte[]> readAsync(int maximum) {
            if (maximum < 1 || maximum > MAX_IO_BYTES)
                return OresFuture.failed(new IllegalArgumentException("invalid read size"));
            if (!reserve()) return OresFuture.failed(
                    new RejectedExecutionException("TCP read admission exhausted"));
            OresFuture<byte[]> future = new OresFuture<>(() -> false, () -> { });
            post(() -> {
                if (terminated || shuttingDown.get() || closeRequested.get()) {
                    fail(future, new IOException("connection closed"));
                } else if (read != null) {
                    fail(future, new IllegalStateException("only one pending TCP read permitted"));
                } else {
                    read = new ReadOp(future, maximum);
                    updateInterest();
                }
            });
            return future;
        }

        public OresFuture<Integer> writeAsync(byte[] bytes) {
            if (bytes == null || bytes.length > MAX_IO_BYTES)
                return OresFuture.failed(new IllegalArgumentException("invalid write bytes"));
            // Snapshot before crossing threads: no mutable caller-owned buffer
            // survives asynchronous dispatch. Connection ownership remains local.
            byte[] copied = Arrays.copyOf(bytes, bytes.length);
            if (!reserve()) return OresFuture.failed(
                    new RejectedExecutionException("TCP write admission exhausted"));
            OresFuture<Integer> future = new OresFuture<>(() -> false, () -> { });
            post(() -> {
                if (terminated || shuttingDown.get() || closeRequested.get()) {
                    fail(future, new IOException("connection closed"));
                } else if (write != null) {
                    fail(future, new IllegalStateException("only one pending TCP write permitted"));
                } else if (copied.length == 0) {
                    succeed(future, 0);
                } else {
                    write = new WriteOp(future, ByteBuffer.wrap(copied));
                    updateInterest();
                }
            });
            return future;
        }

        public OresFuture<Void> closeAsync() {
            if (closeRequested.compareAndSet(false, true)) {
                post(() -> closeConnection(this, new IOException("TCP connection closed")));
            }
            return closed;
        }

        private void updateInterest() {
            SelectionKey key = channel.keyFor(selector);
            if (key != null && key.isValid() && !terminated) {
                int ops = (read == null ? 0 : SelectionKey.OP_READ)
                        | (write == null ? 0 : SelectionKey.OP_WRITE);
                key.interestOps(ops);
            }
        }
    }

    private record ReadOp(OresFuture<byte[]> future, int maximum) { }
    private record WriteOp(OresFuture<Integer> future, ByteBuffer bytes) { }

    private <T> void succeed(OresFuture<T> future, T value) {
        // Release admission *before* publishing completion. Awaiting guest
        // continuations can immediately submit their next operation; they must
        // not race an already-completed predecessor's stale quota count.
        pending.decrementAndGet();
        future.completeFromRuntime(value);
    }
    private <T> void fail(OresFuture<T> future, Throwable failure) {
        pending.decrementAndGet();
        future.failFromRuntime(failure);
    }

    private void updateAcceptInterest() {
        SelectionKey key = listener.keyFor(selector);
        if (key != null && key.isValid()) {
            key.interestOps(!accepts.isEmpty() && connections.size() < maxConnections
                    ? SelectionKey.OP_ACCEPT : 0);
        }
    }

    private void acceptReady() throws IOException {
        int accepted = 0;
        while (!accepts.isEmpty() && connections.size() < maxConnections && accepted++ < 64) {
            SocketChannel channel = listener.accept();
            if (channel == null) break;
            channel.configureBlocking(false);
            Connection connection = new Connection(channel);
            try {
                channel.register(selector, 0, connection);
            } catch (IOException | RuntimeException failure) {
                channel.close();
                throw failure;
            }
            connections.add(connection);
            succeed(accepts.removeFirst(), connection);
        }
        updateAcceptInterest();
    }

    private void socketReady(Connection connection, int ops) throws IOException {
        if (connection.terminated) return;
        if ((ops & SelectionKey.OP_READ) != 0 && connection.read != null) {
            ReadOp op = connection.read;
            ByteBuffer buffer = ByteBuffer.allocate(op.maximum());
            int n = connection.channel.read(buffer);
            if (n != 0) {
                connection.read = null;
                if (n < 0) succeed(op.future(), new byte[0]); // EOF, never zero-byte read
                else succeed(op.future(), Arrays.copyOf(buffer.array(), n));
            }
        }
        if ((ops & SelectionKey.OP_WRITE) != 0 && connection.write != null) {
            WriteOp op = connection.write;
            int total = op.bytes().capacity();
            connection.channel.write(op.bytes()); // may write 0 or a short prefix
            if (!op.bytes().hasRemaining()) {
                connection.write = null;
                succeed(op.future(), total);
            }
        }
        connection.updateInterest();
    }

    private void closeConnection(Connection connection, Throwable reason) {
        if (connection.terminated) return;
        connection.terminated = true;
        connections.remove(connection);
        SelectionKey key = connection.channel.keyFor(selector);
        if (key != null) key.cancel();
        try { connection.channel.close(); } catch (IOException ignored) { }
        if (connection.read != null) {
            fail(connection.read.future(), reason);
            connection.read = null;
        }
        if (connection.write != null) {
            fail(connection.write.future(), reason);
            connection.write = null;
        }
        connection.closed.completeFromRuntime(null);
        updateAcceptInterest();
    }

    private void drainCommands() {
        Runnable next;
        while ((next = commands.poll()) != null) next.run();
    }

    private void run() {
        try {
            while (!shuttingDown.get()) {
                drainCommands();
                if (shuttingDown.get()) break;
                selector.select(1000); // exclusively the dedicated host reactor
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) continue;
                    try {
                        if (key.channel() == listener) acceptReady();
                        else socketReady((Connection) key.attachment(), key.readyOps());
                    } catch (IOException socketFailure) {
                        if (key.channel() == listener) throw socketFailure;
                        closeConnection((Connection) key.attachment(), socketFailure);
                    } catch (CancelledKeyException ignored) {
                        // Closed concurrently by a prior reactor command.
                    }
                }
            }
        } catch (Throwable terminal) {
            terminalFailure = terminal;
        } finally {
            shuttingDown.set(true);
            try { drainCommands(); } catch (Throwable ignored) { }
            IOException failure = new IOException("TCP reactor terminated", terminalFailure);
            while (!accepts.isEmpty()) fail(accepts.removeFirst(), failure);
            for (Connection connection : Set.copyOf(connections))
                closeConnection(connection, failure);
            try { listener.close(); } catch (IOException ignored) { }
            try { selector.close(); } catch (IOException ignored) { }
            terminated.completeFromRuntime(null);
            stopped.countDown();
        }
    }

    public OresFuture<Void> closeAsync() {
        close();
        return terminated;
    }

    boolean awaitTerminated(long timeoutMillis) throws InterruptedException {
        return stopped.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override public void close() {
        if (shuttingDown.compareAndSet(false, true)) selector.wakeup();
    }
}
