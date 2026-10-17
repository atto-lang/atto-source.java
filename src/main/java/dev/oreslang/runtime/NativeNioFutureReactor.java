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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
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
 * The experimental Oreslang std/net source facade is loopback/supervisor-only.
 * Fail closed instead of
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
    // CPU-only guest value conversion, never socket I/O or guest evaluation.
    private final Executor completionCpuExecutor;
    private final int maxConnections;
    private final int maxPending;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    // Serializes command publication against the final-shutdown admission fence.
    private final Object admissionGate = new Object();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicInteger pending = new AtomicInteger();
    // Every accepted operation is registered before command publication.
    // Identity avoids surprising equals/hashCode callbacks on reactor threads.
    private final Set<OresFuture<?>> unsettled =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicLong worstCommandQueueNanos = new AtomicLong();
    private final AtomicInteger peakPending = new AtomicInteger();
    // The fields below must be accessed on the dedicated reactor thread only.
    private final ArrayDeque<Cancelable<Connection>> accepts = new ArrayDeque<>();
    private final Set<Connection> connections = new HashSet<>();
    private volatile Throwable terminalFailure;
    private final OresFuture<Void> terminated = new OresFuture<>(() -> false, () -> { });

    public static NativeNioFutureReactor loopback(int port, int backlog,
                                           int maxConnections, int maxPending) throws IOException {
        // Standalone host tests and embeddings use the shared CPU executor.
        // OresContext supplies the VM-owned bounded CONTROL executor instead.
        return loopback(port, backlog, maxConnections, maxPending, ForkJoinPool.commonPool());
    }

    // Internal-only overload: the caller must provide a non-inline bounded
    // scheduler. OresContext uses the VM's CONTROL executor; arbitrary guest
    // code cannot substitute a direct/caller-runs executor.
    static NativeNioFutureReactor loopback(int port, int backlog,
            int maxConnections, int maxPending, Executor completionCpuExecutor) throws IOException {
        java.util.Objects.requireNonNull(completionCpuExecutor, "completionCpuExecutor");
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
            return new NativeNioFutureReactor(selector, server, maxConnections, maxPending, completionCpuExecutor);
        } catch (IOException | RuntimeException | Error failure) {
            // Thread.start() can fail with an Error during native thread
            // exhaustion. Even that bootstrap failure must close the
            // just-created listener and selector descriptors.
            if (server != null) try { server.close(); } catch (IOException ignored) { }
            try { selector.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    private NativeNioFutureReactor(Selector selector, ServerSocketChannel listener,
                                   int maxConnections, int maxPending,
                                   Executor completionCpuExecutor) {
        this.selector = selector;
        this.listener = listener;
        this.maxConnections = maxConnections;
        this.maxPending = maxPending;
        this.completionCpuExecutor = completionCpuExecutor;
        this.thread = Thread.ofPlatform().daemon(true).name("ores-nio-reactor")
                .unstarted(() -> OresVM.runAsNioWorker(this::run));
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

    private boolean submit(OresFuture<?> future, Runnable runnable) {
        synchronized (admissionGate) {
            if (shuttingDown.get() || unsettled.size() >= maxPending) return false;
            unsettled.add(future);
            pending.incrementAndGet();
            peakPending.accumulateAndGet(pending.get(), Math::max);
            try {
                postOwned(runnable);
                return true;
            } catch (RuntimeException | Error failure) {
                // No command was successfully admitted. Restore counters.
                unsettled.remove(future);
                pending.decrementAndGet();
                throw failure;
            }
        }
    }

    private boolean release(OresFuture<?> future) {
        synchronized (admissionGate) {
            if (!unsettled.remove(future)) return false;
            pending.decrementAndGet();
            return true;
        }
    }

    // Caller must hold admissionGate, or be the dedicated reactor thread.
    private void postOwned(Runnable runnable) {
        long queuedAt = System.nanoTime();
        commands.add(() -> {
            worstCommandQueueNanos.accumulateAndGet(
                    Math.max(0, System.nanoTime() - queuedAt), Math::max);
            runnable.run();
        });
        selector.wakeup();
    }

    /**
     * The admission flag arbitrates cancellation against the kernel syscall.
     * Cancel wins: no socket syscall may start. Reactor wins: cancellation is
     * rejected until a non-consuming readiness attempt returns zero/null.
     * Future.cancel() invokes the cleanup hook only after its terminal CAS,
     * so reclamation cannot happen before cancellation becomes observable.
     */
    private final class Cancelable<T> {
        final AtomicBoolean mayConsume = new AtomicBoolean(true);
        final OresFuture<T> future = new OresFuture<>(
                () -> mayConsume.compareAndSet(true, false),
                () -> {
                    synchronized (admissionGate) {
                        if (!shuttingDown.get()) postOwned(() -> forgetCancelled(this));
                    }
                });

        boolean claimForKernel() {
            return mayConsume.compareAndSet(true, false);
        }

        void retryAfterNoProgress() {
            mayConsume.set(true);
        }
    }

    private void forgetCancelled(Cancelable<?> operation) {
        accepts.removeIf(candidate -> candidate == operation);
        for (Connection connection : connections) {
            if (connection.read != null && connection.read.operation() == operation) {
                connection.read = null;
                connection.updateInterest();
            }
        }
        release(operation.future);
        updateAcceptInterest();
    }

    public OresFuture<Connection> acceptAsync() {
        Cancelable<Connection> operation = new Cancelable<>();
        if (!submit(operation.future, () -> {
            if (operation.future.isCancelled()) {
                forgetCancelled(operation);
            } else if (shuttingDown.get()) {
                fail(operation.future, new IOException("TCP reactor closing"));
            } else {
                accepts.add(operation);
                updateAcceptInterest();
            }
        })) return OresFuture.failed(
                new RejectedExecutionException("TCP listener is closed or at capacity"));
        return operation.future;
    }

    public final class Connection {
        private final SocketChannel channel;
        private ReadOp read;
        private WriteOp write;
        private final AtomicBoolean closeRequested = new AtomicBoolean();
        private final OresFuture<Void> closed = new OresFuture<>(() -> false, () -> { });
        private boolean terminated; // reactor-thread only

        private Connection(SocketChannel channel) { this.channel = channel; }

        /** Runtime ownership identity; not exposed as a guest member. */
        public NativeNioFutureReactor ownerReactor() {
            return NativeNioFutureReactor.this;
        }

        /**
         * ABI representation for Oreslang Array<int>: owned immutable numeric data.
         * Conversion occurs on the completing runtime thread; never a guest
         * callback, and no byte array or ByteBuffer crosses the guest boundary.
         */
        public OresFuture<java.util.List<Long>> readBytesAsync(int maximum) {
            OresFuture<byte[]> read = readAsync(maximum);
            // The guest-facing mapped Future must revoke the SAME kernel
            // operation. Propagate cancel admission to the raw Future before
            // guest cancellation becomes visible; the raw cancellation waiter
            // intentionally does not race the exported terminal CAS.
            OresFuture<java.util.List<Long>> exported =
                    new OresFuture<>(() -> read.cancel(false), () -> { });
            read.whenCompleteRuntime((bytes, failure) -> {
                if (read.isCancelled()) return;
                // The callback can arrive on the selector thread. It may only
                // enqueue CPU work there; boxing thousands of guest values
                // or waking guest continuations inline would stall all sockets.
                try {
                    completionCpuExecutor.execute(() -> {
                        // Defensive fail-closed check even for an incorrectly
                        // wired package-private/caller-runs host executor.
                        // Never box guest arrays on the selector thread.
                        if (Thread.currentThread() == thread) {
                            exported.failFromRuntime(new IllegalStateException(
                                    "TCP guest mapping executor ran inline on NIO reactor"));
                            return;
                        }
                        if (exported.isDone()) return;
                        if (failure != null) {
                            exported.failFromRuntime(failure);
                            return;
                        }
                        try {
                            java.util.ArrayList<Long> values = new java.util.ArrayList<>(bytes.length);
                            for (byte b : bytes) values.add((long) (b & 0xff));
                            exported.completeFromRuntime(java.util.List.copyOf(values));
                        } catch (Throwable conversionFailure) {
                            exported.failFromRuntime(conversionFailure);
                        }
                    });
                } catch (RuntimeException rejected) {
                    // A saturated/stopped executor must settle the exported
                    // Future explicitly, never silently strand an await.
                    exported.failFromRuntime(rejected);
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
            Cancelable<byte[]> operation = new Cancelable<>();
            if (!submit(operation.future, () -> {
                if (operation.future.isCancelled()) {
                    forgetCancelled(operation);
                } else if (terminated || shuttingDown.get() || closeRequested.get()) {
                    fail(operation.future, new IOException("connection closed"));
                } else if (read != null) {
                    fail(operation.future, new IllegalStateException("only one pending TCP read permitted"));
                } else {
                    read = new ReadOp(operation, maximum);
                    updateInterest();
                }
            })) return OresFuture.failed(
                    new RejectedExecutionException("TCP read admission exhausted"));
            return operation.future;
        }

        public OresFuture<Integer> writeAsync(byte[] bytes) {
            if (bytes == null || bytes.length > MAX_IO_BYTES)
                return OresFuture.failed(new IllegalArgumentException("invalid write bytes"));
            // Snapshot before crossing threads: no mutable caller-owned buffer
            // survives asynchronous dispatch. Connection ownership remains local.
            byte[] copied = Arrays.copyOf(bytes, bytes.length);
            OresFuture<Integer> future = new OresFuture<>(() -> false, () -> { });
            if (!submit(future, () -> {
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
            })) return OresFuture.failed(
                    new RejectedExecutionException("TCP write admission exhausted"));
            return future;
        }

        public OresFuture<Void> closeAsync() {
            if (closeRequested.compareAndSet(false, true)) {
                synchronized (admissionGate) {
                    // During shutdown the reactor owns close of every tracked
                    // connection. Never enqueue after the final command drain.
                    if (!shuttingDown.get()) {
                        postOwned(() -> closeConnection(this,
                                new IOException("TCP connection closed")));
                    }
                }
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

    private record ReadOp(Cancelable<byte[]> operation, int maximum) { }
    private record WriteOp(OresFuture<Integer> future, ByteBuffer bytes) { }

    private <T> void succeed(OresFuture<T> future, T value) {
        // Admission is returned before exposing completion to continuations.
        if (release(future)) future.completeFromRuntime(value);
    }
    private <T> void fail(OresFuture<T> future, Throwable failure) {
        if (release(future)) future.failFromRuntime(failure);
    }

    private void updateAcceptInterest() {
        SelectionKey key = listener.keyFor(selector);
        if (key != null && key.isValid()) {
            key.interestOps(!accepts.isEmpty() && connections.size() < maxConnections
                    ? SelectionKey.OP_ACCEPT : 0);
        }
    }

    /**
     * Configure and register a freshly accepted connection as one ownership
     * transaction. Even failure before register() must close the accepted FD.
     * Package access permits deterministic selector-failure regression tests.
     */
    static void registerAcceptedChannel(
            SocketChannel channel, Selector selector, Object attachment) throws IOException {
        try {
            channel.configureBlocking(false);
            channel.register(selector, 0, attachment);
        } catch (IOException | RuntimeException failure) {
            try { channel.close(); }
            catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private void acceptReady() throws IOException {
        int accepted = 0;
        while (!accepts.isEmpty() && connections.size() < maxConnections && accepted++ < 64) {
            Cancelable<Connection> operation = accepts.peekFirst();
            if (!operation.claimForKernel()) {
                accepts.removeFirst();
                release(operation.future);
                continue;
            }
            SocketChannel channel = listener.accept();
            if (channel == null) {
                operation.retryAfterNoProgress();
                break;
            }
            accepts.removeFirst();
            Connection connection = new Connection(channel);
            try {
                registerAcceptedChannel(channel, selector, connection);
            } catch (IOException | RuntimeException failure) {
                // Per-connection setup failure must not terminate the listener
                // or strand other outstanding accepts.
                fail(operation.future, failure);
                continue;
            }
            connections.add(connection);
            succeed(operation.future, connection);
        }
        updateAcceptInterest();
    }

    private void socketReady(Connection connection, int ops) throws IOException {
        if (connection.terminated) return;
        if ((ops & SelectionKey.OP_READ) != 0 && connection.read != null) {
            ReadOp op = connection.read;
            if (!op.operation().claimForKernel()) {
                connection.read = null;
                release(op.operation().future);
            } else {
                ByteBuffer buffer = ByteBuffer.allocate(op.maximum());
                int n = connection.channel.read(buffer);
                if (n == 0) {
                    op.operation().retryAfterNoProgress();
                } else {
                    connection.read = null;
                    if (n < 0) succeed(op.operation().future, new byte[0]); // EOF
                    else succeed(op.operation().future, Arrays.copyOf(buffer.array(), n));
                }
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
            fail(connection.read.operation().future, reason);
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
        // A sustained producer flood must not prevent other sockets from
        // getting ready-event service. Pending futures and command publication
        // remain bounded; advance at most one quantum per loop iteration.
        Runnable next;
        int quantum = 0;
        while (quantum++ < 128 && (next = commands.poll()) != null) next.run();
    }

    private void run() {
        try {
            while (!shuttingDown.get()) {
                drainCommands();
                if (shuttingDown.get()) break;
                // If a command quantum left work behind, do not park for an
                // extra second; service readiness and return to commands.
                // A readiness quantum can leave selected keys in the set.
                // Never park while prior ready keys remain unprocessed: that
                // would delay guest TCP Future completions by a select timeout.
                if (commands.isEmpty() && selector.selectedKeys().isEmpty())
                    selector.select(1000);
                else selector.selectNow();
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                int readyQuantum = 0;
                while (readyQuantum++ < 128 && it.hasNext()) {
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
            synchronized (admissionGate) {
                shuttingDown.set(true);
            }
            try {
                // A bad queued command must not prevent all OTHER admitted
                // operations from being failed. Their identity registry is
                // independent of the command queue and selection keys.
                try { drainCommands(); } catch (Throwable drainedFailure) {
                    if (terminalFailure == null) terminalFailure = drainedFailure;
                }
                IOException failure = new IOException("TCP reactor terminated", terminalFailure);
                while (!accepts.isEmpty()) fail(accepts.removeFirst().future, failure);
                for (Connection connection : Set.copyOf(connections)) {
                    try { closeConnection(connection, failure); }
                    catch (RuntimeException cleanupFailure) {
                        if (terminalFailure != null) terminalFailure.addSuppressed(cleanupFailure);
                    }
                }
                // The queues may contain operations whose command itself threw
                // before registering as an active read/write/accept.
                Set<OresFuture<?>> forgotten;
                synchronized (admissionGate) {
                    forgotten = Set.copyOf(unsettled);
                    unsettled.clear();
                    pending.set(0);
                    commands.clear();
                }
                for (OresFuture<?> future : forgotten)
                    future.failFromRuntime(failure);
            } finally {
                try { listener.close(); } catch (IOException ignored) { }
                try { selector.close(); } catch (IOException ignored) { }
                // Publish quiescence before completing guest-visible close:
                // a resumed task may immediately open a new listener, and
                // capacity accounting must already see this reactor stopped.
                stopped.countDown();
                terminated.completeFromRuntime(null);
            }
        }
    }

    public OresFuture<Void> closeAsync() {
        close();
        return terminated;
    }

    boolean isTerminated() { return stopped.getCount() == 0L; }

    boolean awaitTerminated(long timeoutMillis) throws InterruptedException {
        return stopped.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override public void close() {
        synchronized (admissionGate) {
            if (shuttingDown.compareAndSet(false, true)) selector.wakeup();
        }
    }
}
