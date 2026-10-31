package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One VM-owned nonblocking TCP client connection.
 *
 * <p>The first strict client contract accepts numeric IPv4 literals only.
 * Hostname resolution is deliberately not performed here because the JDK
 * resolver may block. A future resolver service can add bounded asynchronous
 * DNS without weakening this contract.</p>
 */
public final class VmNioTcpClient implements AutoCloseable {
    private static final int MAX_IO_BYTES = 65_536;
    private static final int MAX_CONNECT_TIMEOUTS = 1_024;

    private static final class DeadlineTimer {
        private static final AtomicInteger IDS = new AtomicInteger();
        private static final Semaphore ADMISSION =
                new Semaphore(MAX_CONNECT_TIMEOUTS);
        private static final ScheduledThreadPoolExecutor EXECUTOR = create();

        private static ScheduledThreadPoolExecutor create() {
            ScheduledThreadPoolExecutor timer =
                    new ScheduledThreadPoolExecutor(
                            1,
                            runnable -> Thread.ofPlatform()
                                    .daemon(true)
                                    .name("ores-nio-connect-deadline-"
                                            + IDS.getAndIncrement())
                                    .unstarted(runnable));
            timer.setRemoveOnCancelPolicy(true);
            timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            return timer;
        }
    }

    private final Object gate = new Object();
    private final NioReadinessGroup readiness;
    private final SocketChannel channel;
    private final String endpoint;
    private final Runnable onTerminated;
    private final AtomicReference<OresFuture<Integer>> connectWatch =
            new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> deadline =
            new AtomicReference<>();
    private final AtomicBoolean deadlinePermitReleased =
            new AtomicBoolean();
    private final OresFuture<Connection> connected;

    private Connection connection;
    private volatile boolean closed;
    private boolean terminationReported;

    static VmNioTcpClient openOwned(
            NioReadinessGroup readiness,
            String host,
            int port,
            int timeoutMillis,
            Runnable onTerminated) throws IOException {
        Objects.requireNonNull(readiness, "readiness");
        Objects.requireNonNull(onTerminated, "onTerminated");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(
                    "TCP connect port must be 1..65535");
        }
        if (timeoutMillis < 1 || timeoutMillis > 120_000) {
            throw new IllegalArgumentException(
                    "TCP connect timeout must be 1..120000 ms");
        }

        InetAddress address = numericIpv4(host);
        if (!DeadlineTimer.ADMISSION.tryAcquire()) {
            throw new RejectedExecutionException(
                    "TCP connect deadline capacity exhausted");
        }

        SocketChannel channel = null;
        VmNioTcpClient client = null;
        try {
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            client = new VmNioTcpClient(
                    readiness,
                    channel,
                    host + ":" + port,
                    onTerminated);
            client.armDeadline(timeoutMillis);
            client.beginConnect(new InetSocketAddress(address, port));
            return client;
        } catch (IOException | RuntimeException | Error failure) {
            if (client != null) {
                client.abortConstruction();
            } else {
                DeadlineTimer.ADMISSION.release();
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            throw failure;
        }
    }

    private VmNioTcpClient(
            NioReadinessGroup readiness,
            SocketChannel channel,
            String endpoint,
            Runnable onTerminated) {
        this.readiness = readiness;
        this.channel = channel;
        this.endpoint = endpoint;
        this.onTerminated = onTerminated;
        this.connected = new OresFuture<>(
                this::cancelPendingConnect,
                this::connectNotificationsDrained);
        this.connected.whenCompleteRuntime((value, failure) ->
                cancelDeadlineAndReleaseAdmission());
    }

    OresFuture<Connection> connectedFuture() {
        return connected;
    }

    boolean isTerminated() {
        synchronized (gate) {
            return terminationReported;
        }
    }

    static boolean isNumericIpv4Literal(String host) {
        try {
            numericIpv4(host);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static InetAddress numericIpv4(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    "NIO TCP connect host cannot be blank");
        }

        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            throw new UnsupportedOperationException(
                    "strict NIO TCP connect currently requires a numeric IPv4 literal; "
                            + "blocking hostname resolution is not permitted");
        }

        byte[] address = new byte[4];
        for (int i = 0; i < parts.length; ++i) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                throw new UnsupportedOperationException(
                        "strict NIO TCP connect currently requires a numeric IPv4 literal");
            }
            int value = 0;
            for (int j = 0; j < part.length(); ++j) {
                char ch = part.charAt(j);
                if (ch < '0' || ch > '9') {
                    throw new UnsupportedOperationException(
                            "strict NIO TCP connect currently requires a numeric IPv4 literal");
                }
                value = value * 10 + (ch - '0');
            }
            if (value > 255) {
                throw new UnsupportedOperationException(
                        "strict NIO TCP connect currently requires a numeric IPv4 literal");
            }
            address[i] = (byte) value;
        }

        try {
            return InetAddress.getByAddress(address);
        } catch (UnknownHostException impossible) {
            throw new IllegalArgumentException(
                    "invalid numeric IPv4 address", impossible);
        }
    }

    private void abortConstruction() {
        cancelConnectWatch();
        closeChannel();
        cancelDeadlineAndReleaseAdmission();
        connected.cancel(false);
        reportTerminated();
    }

    private void armDeadline(int timeoutMillis) {
        try {
            ScheduledFuture<?> scheduled = DeadlineTimer.EXECUTOR.schedule(
                    () -> {
                        if (connected.isDone()) return;
                        failConnect(new SocketTimeoutException(
                                "TCP connect deadline exceeded for "
                                        + endpoint));
                    },
                    timeoutMillis,
                    TimeUnit.MILLISECONDS);
            if (!deadline.compareAndSet(null, scheduled)) {
                scheduled.cancel(false);
                throw new IllegalStateException(
                        "TCP connect deadline armed twice");
            }
        } catch (RuntimeException | Error failure) {
            // openOwned still owns the admission permit until construction
            // succeeds; its failure path releases exactly once.
            throw failure;
        }
    }

    private void cancelDeadlineAndReleaseAdmission() {
        ScheduledFuture<?> scheduled = deadline.getAndSet(null);
        if (scheduled != null) scheduled.cancel(false);
        releaseDeadlineAdmission();
    }

    private void releaseDeadlineAdmission() {
        if (deadlinePermitReleased.compareAndSet(false, true)) {
            DeadlineTimer.ADMISSION.release();
        }
    }

    private void beginConnect(InetSocketAddress address) {
        if (connected.isDone()) return;
        try {
            if (channel.connect(address)) {
                completeConnected();
                return;
            }
        } catch (IOException | RuntimeException failure) {
            failConnect(failure);
            return;
        }
        armConnectWatch();
    }

    private void armConnectWatch() {
        if (connected.isDone()) return;
        OresFuture<Integer> watch =
                readiness.watch(channel, SelectionKey.OP_CONNECT);
        connectWatch.set(watch);
        if (connected.isDone()) {
            if (connectWatch.compareAndSet(watch, null)) {
                watch.cancel(false);
            }
            return;
        }

        watch.whenCompleteRuntime((ready, failure) -> {
            connectWatch.compareAndSet(watch, null);
            if (connected.isDone()) return;
            if (failure != null) {
                failConnect(failure);
                return;
            }
            finishConnect();
        });
    }

    private void finishConnect() {
        if (connected.isDone()) return;
        try {
            if (channel.finishConnect()) {
                completeConnected();
            } else {
                armConnectWatch();
            }
        } catch (IOException | RuntimeException failure) {
            failConnect(failure);
        }
    }

    private void completeConnected() {
        final Connection established;
        synchronized (gate) {
            if (closed || connected.isDone()) {
                closeChannel();
                return;
            }
            established = new Connection();
            connection = established;
        }
        if (!connected.completeFromRuntime(established)) {
            established.close();
        }
    }

    private void failConnect(Throwable failure) {
        cancelConnectWatch();
        closeChannel();
        connected.failFromRuntime(failure);
    }

    private void cancelPendingConnect() {
        cancelConnectWatch();
        closeChannel();
    }

    private void cancelConnectWatch() {
        OresFuture<Integer> watch = connectWatch.getAndSet(null);
        if (watch != null) watch.cancel(false);
    }

    private void closeChannel() {
        try {
            channel.close();
        } catch (IOException ignored) {
            // A Future terminal state remains the public result.
        }
    }

    private void connectNotificationsDrained() {
        boolean hasConnection;
        synchronized (gate) {
            hasConnection = connection != null;
        }
        if (!hasConnection) reportTerminated();
    }

    private void reportTerminated() {
        boolean report = false;
        synchronized (gate) {
            if (!terminationReported) {
                terminationReported = true;
                gate.notifyAll();
                report = true;
            }
        }
        if (report) onTerminated.run();
    }

    @Override
    public void close() {
        final Connection established;
        synchronized (gate) {
            if (closed) return;
            closed = true;
            established = connection;
            gate.notifyAll();
        }

        if (established != null) {
            established.close();
        } else {
            connected.cancel(false);
            if (!connected.isDone()) {
                cancelPendingConnect();
                reportTerminated();
            }
        }
    }

    boolean awaitTerminated(long timeout, TimeUnit unit)
            throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        synchronized (gate) {
            while (!terminationReported) {
                long left = deadlineNanos - System.nanoTime();
                if (left <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(gate, left);
            }
            return true;
        }
    }

    public final class Connection implements AutoCloseable {
        private final Object connectionGate = new Object();
        private final Set<OresFuture<?>> outstanding = new HashSet<>();
        private final OresFuture<Void> closedFuture = new OresFuture<>();
        private boolean terminated;
        private int activeOperations;

        public String endpoint() {
            return endpoint;
        }

        private Throwable reserveOperation() {
            synchronized (connectionGate) {
                if (terminated || closed || !channel.isOpen()) {
                    return new IOException("TCP connection closed");
                }
                if (activeOperations != 0) {
                    return new IllegalStateException(
                            "connection already has an outstanding socket operation");
                }
                ++activeOperations;
                return null;
            }
        }

        private void releaseOperation() {
            boolean closeReady;
            synchronized (connectionGate) {
                if (activeOperations <= 0) {
                    throw new IllegalStateException(
                            "TCP client operation reservation underflow");
                }
                --activeOperations;
                closeReady = terminated
                        && activeOperations == 0
                        && outstanding.isEmpty();
                connectionGate.notifyAll();
            }
            if (closeReady) finishClose();
        }

        private <T> OresFuture<T> track(OresFuture<T> future) {
            boolean shutdown;
            synchronized (connectionGate) {
                shutdown = terminated;
                if (!shutdown && !future.isDone()) {
                    outstanding.add(future);
                }
            }

            future.whenCompleteRuntime((value, failure) -> {
                boolean closeReady;
                synchronized (connectionGate) {
                    outstanding.remove(future);
                    closeReady = terminated
                            && activeOperations == 0
                            && outstanding.isEmpty();
                    connectionGate.notifyAll();
                }
                if (closeReady) finishClose();
            });

            if (shutdown && !future.isDone()) future.cancel(false);
            return future;
        }

        public OresFuture<List<Long>> readBytesAsync(int maximum) {
            if (maximum < 1 || maximum > MAX_IO_BYTES) {
                return OresFuture.failed(new IllegalArgumentException(
                        "invalid TCP read bound"));
            }
            Throwable denial = reserveOperation();
            if (denial != null) return OresFuture.failed(denial);

            ByteBuffer buffer = ByteBuffer.allocate(maximum);
            AtomicReference<OresFuture<Integer>> watchRef =
                    new AtomicReference<>();
            OresFuture<List<Long>> result = new OresFuture<>(
                    () -> cancelWatch(watchRef),
                    this::releaseOperation);
            track(result);
            armRead(buffer, result, watchRef);
            return result;
        }

        private void armRead(
                ByteBuffer buffer,
                OresFuture<List<Long>> result,
                AtomicReference<OresFuture<Integer>> watchRef) {
            if (result.isDone()) return;
            try {
                int read = channel.read(buffer);
                if (read < 0) {
                    result.completeFromRuntime(List.of());
                    return;
                }
                if (read > 0) {
                    buffer.flip();
                    ArrayList<Long> bytes = new ArrayList<>(read);
                    while (buffer.hasRemaining()) {
                        bytes.add((long) (buffer.get() & 0xff));
                    }
                    result.completeFromRuntime(List.copyOf(bytes));
                    return;
                }
            } catch (IOException | RuntimeException failure) {
                result.failFromRuntime(failure);
                return;
            }

            OresFuture<Integer> watch =
                    readiness.watch(channel, SelectionKey.OP_READ);
            watchRef.set(watch);
            if (result.isDone()) {
                if (watchRef.compareAndSet(watch, null)) {
                    watch.cancel(false);
                }
                return;
            }

            watch.whenCompleteRuntime((ready, failure) -> {
                watchRef.compareAndSet(watch, null);
                if (result.isDone()) return;
                if (failure != null) {
                    result.failFromRuntime(failure);
                    return;
                }
                armRead(buffer, result, watchRef);
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

            Throwable denial = reserveOperation();
            if (denial != null) return OresFuture.failed(denial);

            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            AtomicReference<OresFuture<Integer>> watchRef =
                    new AtomicReference<>();
            OresFuture<Integer> result = new OresFuture<>(
                    () -> cancelWatch(watchRef),
                    this::releaseOperation);
            track(result);
            armWrite(buffer, encoded.length, result, watchRef);
            return result;
        }

        private void armWrite(
                ByteBuffer buffer,
                int total,
                OresFuture<Integer> result,
                AtomicReference<OresFuture<Integer>> watchRef) {
            if (result.isDone()) return;
            try {
                while (buffer.hasRemaining()) {
                    int written = channel.write(buffer);
                    if (written == 0) break;
                }
                if (!buffer.hasRemaining()) {
                    result.completeFromRuntime(total);
                    return;
                }
            } catch (IOException | RuntimeException failure) {
                result.failFromRuntime(failure);
                return;
            }

            OresFuture<Integer> watch =
                    readiness.watch(channel, SelectionKey.OP_WRITE);
            watchRef.set(watch);
            if (result.isDone()) {
                if (watchRef.compareAndSet(watch, null)) {
                    watch.cancel(false);
                }
                return;
            }

            watch.whenCompleteRuntime((ready, failure) -> {
                watchRef.compareAndSet(watch, null);
                if (result.isDone()) return;
                if (failure != null) {
                    result.failFromRuntime(failure);
                    return;
                }
                armWrite(buffer, total, result, watchRef);
            });
        }

        private void cancelWatch(
                AtomicReference<OresFuture<Integer>> watchRef) {
            OresFuture<Integer> watch = watchRef.getAndSet(null);
            if (watch != null) watch.cancel(false);
        }

        public OresFuture<Void> closeAsync() {
            close();
            return closedFuture;
        }

        @Override
        public void close() {
            final List<OresFuture<?>> pending;
            synchronized (connectionGate) {
                if (terminated) return;
                terminated = true;
                pending = new ArrayList<>(outstanding);
                connectionGate.notifyAll();
            }

            closeChannel();
            for (OresFuture<?> future : pending) future.cancel(false);
            finishCloseIfReady();
        }

        private void finishCloseIfReady() {
            boolean ready;
            synchronized (connectionGate) {
                ready = terminated
                        && activeOperations == 0
                        && outstanding.isEmpty();
            }
            if (ready) finishClose();
        }

        private void finishClose() {
            if (closedFuture.completeFromRuntime(null)) {
                reportTerminated();
            }
        }

        boolean awaitTerminated(long timeout, TimeUnit unit)
                throws InterruptedException {
            Objects.requireNonNull(unit, "unit");
            long deadlineNanos =
                    System.nanoTime() + unit.toNanos(timeout);
            synchronized (connectionGate) {
                while (!(terminated
                        && activeOperations == 0
                        && outstanding.isEmpty())) {
                    long left = deadlineNanos - System.nanoTime();
                    if (left <= 0) return false;
                    TimeUnit.NANOSECONDS.timedWait(
                            connectionGate, left);
                }
                return true;
            }
        }
    }
}
