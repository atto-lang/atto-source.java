package dev.oreslang.runtime;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * One selector-owned nonblocking TCP connection with optional SSLEngine TLS.
 *
 * <p>The caller may still execute this state machine on a native-I/O worker,
 * but no socket connect/read/write/handshake operation uses a blocking socket
 * call. Every wait is selector-driven and bounded by both a phase deadline and
 * the request-wide overall deadline.</p>
 */
final class SelectorTransportConnection implements AutoCloseable {
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    private final SocketChannel channel;
    private final Selector selector;
    private final SelectionKey key;
    private final long overallDeadlineNanos;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Input input = new Input();
    private final Output output = new Output();

    private volatile Duration readTimeout = Duration.ofSeconds(30);
    private volatile Duration writeTimeout = Duration.ofSeconds(15);

    private SSLEngine engine;
    private ByteBuffer netIn;
    private ByteBuffer netOut;
    private ByteBuffer appIn;

    private SelectorTransportConnection(
            SocketChannel channel,
            Selector selector,
            SelectionKey key,
            long overallDeadlineNanos) {
        this.channel = channel;
        this.selector = selector;
        this.key = key;
        this.overallDeadlineNanos = overallDeadlineNanos;
    }

    static SelectorTransportConnection open(
            long overallDeadlineNanos)
            throws IOException {
        SocketChannel channel = SocketChannel.open();
        Selector selector = null;
        try {
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            selector = Selector.open();
            SelectionKey key = channel.register(selector, 0);
            return new SelectorTransportConnection(
                    channel,
                    selector,
                    key,
                    overallDeadlineNanos);
        } catch (Throwable failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            if (selector != null) {
                try {
                    selector.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure instanceof IOException io) throw io;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IOException(
                    "nonblocking TCP setup failed",
                    failure);
        }
    }

    void connect(
            InetSocketAddress address,
            Duration connectTimeout)
            throws IOException {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        requireOpen();
        long connectDeadline =
                phaseDeadline(connectTimeout);
        if (!channel.connect(address)) {
            while (!channel.finishConnect()) {
                waitForUntil(
                        SelectionKey.OP_CONNECT,
                        connectDeadline,
                        "connect");
            }
        }
        key.interestOps(0);
    }

    void startTls(
            SSLContext sslContext,
            String host,
            int port,
            boolean sendSni,
            Duration handshakeTimeout)
            throws IOException {
        Objects.requireNonNull(sslContext, "sslContext");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        requireOpen();
        if (engine != null) {
            throw new IllegalStateException(
                    "TLS already initialized");
        }

        SSLEngine created = sslContext.createSSLEngine(host, port);
        created.setUseClientMode(true);
        SSLParameters parameters = created.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (sendSni) {
            parameters.setServerNames(
                    List.of(new SNIHostName(host)));
        }
        created.setSSLParameters(parameters);

        SSLSession session = created.getSession();
        netIn = emptyReadBuffer(session.getPacketBufferSize());
        netOut = ByteBuffer.allocate(session.getPacketBufferSize());
        appIn = emptyReadBuffer(session.getApplicationBufferSize());
        engine = created;

        created.beginHandshake();
        driveHandshake(
                created.getHandshakeStatus(),
                phaseDeadline(handshakeTimeout));
    }

    InputStream input() {
        return input;
    }

    OutputStream output() {
        return output;
    }

    void setReadTimeout(Duration timeout) {
        requirePositive(timeout, "read timeout");
        readTimeout = timeout;
    }

    void setWriteTimeout(Duration timeout) {
        requirePositive(timeout, "write timeout");
        writeTimeout = timeout;
    }

    boolean tls() {
        return engine != null;
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        selector.wakeup();
        IOException failure = null;
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure = closeFailure;
        }
        try {
            selector.close();
        } catch (IOException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }

    private final class Input extends InputStream {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(
                byte[] target,
                int offset,
                int length)
                throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) return 0;
            requireOpen();
            ByteBuffer destination =
                    ByteBuffer.wrap(target, offset, length);
            long deadline = phaseDeadline(readTimeout);
            if (engine == null) {
                return readPlain(destination, deadline);
            }
            return readTls(destination, deadline);
        }
    }

    private final class Output extends OutputStream {
        @Override
        public void write(int value) throws IOException {
            byte[] one = { (byte) value };
            write(one, 0, 1);
        }

        @Override
        public void write(
                byte[] source,
                int offset,
                int length)
                throws IOException {
            Objects.checkFromIndexSize(offset, length, source.length);
            if (length == 0) return;
            requireOpen();
            ByteBuffer input =
                    ByteBuffer.wrap(source, offset, length);
            long deadline = phaseDeadline(writeTimeout);
            if (engine == null) {
                writeNetFully(input, deadline, "request write");
            } else {
                writeTls(input, deadline);
            }
        }
    }

    private int readPlain(
            ByteBuffer destination,
            long deadline)
            throws IOException {
        while (true) {
            int read = channel.read(destination);
            if (read != 0) return read;
            waitForUntil(
                    SelectionKey.OP_READ,
                    deadline,
                    "read");
        }
    }

    private void writeTls(
            ByteBuffer source,
            long deadline)
            throws IOException {
        while (source.hasRemaining()) {
            netOut.clear();
            SSLEngineResult result =
                    engine.wrap(source, netOut);
            switch (result.getStatus()) {
                case OK -> {
                    netOut.flip();
                    writeNetFully(
                            netOut,
                            deadline,
                            "TLS request write");
                    driveHandshakeIfNeeded(
                            result.getHandshakeStatus(),
                            deadline);
                }
                case BUFFER_OVERFLOW -> {
                    netOut = grow(
                            netOut,
                            engine.getSession()
                                    .getPacketBufferSize());
                }
                case BUFFER_UNDERFLOW ->
                    throw new SSLException(
                            "SSLEngine wrap reported BUFFER_UNDERFLOW");
                case CLOSED ->
                    throw new SSLException(
                            "TLS engine closed during request write");
            }
        }
    }

    private int readTls(
            ByteBuffer destination,
            long deadline)
            throws IOException {
        if (appIn.hasRemaining()) {
            return copy(appIn, destination);
        }

        while (true) {
            if (!netIn.hasRemaining()) {
                int network = readNetwork(
                        deadline,
                        "TLS response read");
                if (network < 0) {
                    closeInbound();
                    return -1;
                }
            }

            appIn.compact();
            SSLEngineResult result =
                    engine.unwrap(netIn, appIn);
            appIn.flip();
            switch (result.getStatus()) {
                case OK -> {
                    driveHandshakeIfNeeded(
                            result.getHandshakeStatus(),
                            deadline);
                    if (appIn.hasRemaining()) {
                        return copy(appIn, destination);
                    }
                    if (result.bytesConsumed() == 0) {
                        int network = readNetwork(
                                deadline,
                                "TLS response read");
                        if (network < 0) {
                            closeInbound();
                            return -1;
                        }
                    }
                }
                case BUFFER_UNDERFLOW -> {
                    int network = readNetwork(
                            deadline,
                            "TLS response read");
                    if (network < 0) {
                        closeInbound();
                        return -1;
                    }
                }
                case BUFFER_OVERFLOW -> {
                    appIn = growReadBuffer(
                            appIn,
                            engine.getSession()
                                    .getApplicationBufferSize());
                }
                case CLOSED -> {
                    if (appIn.hasRemaining()) {
                        return copy(appIn, destination);
                    }
                    return -1;
                }
            }
        }
    }

    private void driveHandshakeIfNeeded(
            SSLEngineResult.HandshakeStatus status,
            long deadline)
            throws IOException {
        if (status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                && status != SSLEngineResult.HandshakeStatus.FINISHED) {
            driveHandshake(status, deadline);
        }
    }

    private void driveHandshake(
            SSLEngineResult.HandshakeStatus initial,
            long deadline)
            throws IOException {
        SSLEngineResult.HandshakeStatus status = initial;
        while (true) {
            requireOpen();
            switch (status) {
                case FINISHED, NOT_HANDSHAKING -> {
                    return;
                }
                case NEED_TASK -> {
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    status = engine.getHandshakeStatus();
                }
                case NEED_WRAP -> {
                    netOut.clear();
                    SSLEngineResult result =
                            engine.wrap(EMPTY, netOut);
                    if (result.getStatus()
                            == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        netOut = grow(
                                netOut,
                                engine.getSession()
                                        .getPacketBufferSize());
                        continue;
                    }
                    if (result.getStatus()
                            == SSLEngineResult.Status.CLOSED) {
                        throw new SSLException(
                                "TLS engine closed during handshake wrap");
                    }
                    if (result.getStatus()
                            != SSLEngineResult.Status.OK) {
                        throw new SSLException(
                                "unexpected TLS handshake wrap status "
                                        + result.getStatus());
                    }
                    netOut.flip();
                    writeNetFully(
                            netOut,
                            deadline,
                            "TLS handshake write");
                    status = result.getHandshakeStatus();
                }
                case NEED_UNWRAP -> {
                    if (!netIn.hasRemaining()) {
                        int network = readNetwork(
                                deadline,
                                "TLS handshake read");
                        if (network < 0) {
                            throw new EOFException(
                                    "peer closed during TLS handshake");
                        }
                    }

                    appIn.compact();
                    SSLEngineResult result =
                            engine.unwrap(netIn, appIn);
                    appIn.flip();
                    switch (result.getStatus()) {
                        case OK -> {
                            status = result.getHandshakeStatus();
                        }
                        case BUFFER_UNDERFLOW -> {
                            int network = readNetwork(
                                    deadline,
                                    "TLS handshake read");
                            if (network < 0) {
                                throw new EOFException(
                                        "peer closed during TLS handshake");
                            }
                        }
                        case BUFFER_OVERFLOW -> {
                            appIn = growReadBuffer(
                                    appIn,
                                    engine.getSession()
                                            .getApplicationBufferSize());
                        }
                        case CLOSED ->
                            throw new SSLException(
                                    "TLS engine closed during handshake unwrap");
                    }
                }
                case NEED_UNWRAP_AGAIN -> {
                    appIn.compact();
                    SSLEngineResult result =
                            engine.unwrap(netIn, appIn);
                    appIn.flip();
                    if (result.getStatus()
                            == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        appIn = growReadBuffer(
                                appIn,
                                engine.getSession()
                                        .getApplicationBufferSize());
                        continue;
                    }
                    if (result.getStatus()
                            == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        int network = readNetwork(
                                deadline,
                                "TLS handshake read");
                        if (network < 0) {
                            throw new EOFException(
                                    "peer closed during TLS handshake");
                        }
                        status = engine.getHandshakeStatus();
                        continue;
                    }
                    if (result.getStatus()
                            == SSLEngineResult.Status.CLOSED) {
                        throw new SSLException(
                                "TLS engine closed during handshake unwrap");
                    }
                    status = result.getHandshakeStatus();
                }
            }
        }
    }

    private int readNetwork(
            long deadline,
            String what)
            throws IOException {
        while (true) {
            netIn.compact();
            int read;
            try {
                read = channel.read(netIn);
            } finally {
                netIn.flip();
            }
            if (read != 0) return read;
            waitForUntil(
                    SelectionKey.OP_READ,
                    deadline,
                    what);
        }
    }

    private void writeNetFully(
            ByteBuffer source,
            long deadline,
            String what)
            throws IOException {
        while (source.hasRemaining()) {
            int written = channel.write(source);
            if (written == 0) {
                waitForUntil(
                        SelectionKey.OP_WRITE,
                        deadline,
                        what);
            }
        }
    }

    private void waitForUntil(
            int operation,
            long deadlineNanos,
            String what)
            throws IOException {
        while (true) {
            requireOpen();
            long remaining =
                    deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                throw new SocketTimeoutException(
                        "pinned HTTP "
                                + what
                                + " deadline expired");
            }

            key.interestOps(operation);
            long millis = Math.max(
                    1L,
                    Math.min(
                            Integer.MAX_VALUE,
                            (remaining + 999_999L)
                                    / 1_000_000L));
            int ready = selector.select(millis);
            if (ready == 0) continue;

            boolean matched = false;
            Iterator<SelectionKey> selected =
                    selector.selectedKeys().iterator();
            while (selected.hasNext()) {
                SelectionKey candidate = selected.next();
                selected.remove();
                if (candidate == key
                        && candidate.isValid()
                        && (candidate.readyOps()
                                & operation) != 0) {
                    matched = true;
                }
            }
            if (matched) {
                key.interestOps(0);
                return;
            }
        }
    }

    private long phaseDeadline(Duration phase) {
        long now = System.nanoTime();
        long delta;
        try {
            delta = phase.toNanos();
        } catch (ArithmeticException overflow) {
            delta = Long.MAX_VALUE;
        }
        long phaseDeadline =
                delta == Long.MAX_VALUE
                        || Long.MAX_VALUE - now < delta
                        ? Long.MAX_VALUE
                        : now + delta;
        if (overallDeadlineNanos == Long.MAX_VALUE) {
            return phaseDeadline;
        }
        return Math.min(
                phaseDeadline,
                overallDeadlineNanos);
    }

    private void requireOpen()
            throws ClosedChannelException {
        if (closed.get()
                || !channel.isOpen()) {
            throw new ClosedChannelException();
        }
    }

    private void closeInbound() throws SSLException {
        if (engine == null) return;
        engine.closeInbound();
    }

    private static int copy(
            ByteBuffer source,
            ByteBuffer destination) {
        int count = Math.min(
                source.remaining(),
                destination.remaining());
        int limit = source.limit();
        source.limit(source.position() + count);
        destination.put(source);
        source.limit(limit);
        return count;
    }

    private static ByteBuffer emptyReadBuffer(
            int capacity) {
        ByteBuffer buffer =
                ByteBuffer.allocate(Math.max(1, capacity));
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer grow(
            ByteBuffer original,
            int suggestedCapacity) {
        int capacity = Math.max(
                suggestedCapacity,
                Math.max(
                        original.capacity() + 1,
                        original.capacity() * 2));
        ByteBuffer replacement =
                ByteBuffer.allocate(capacity);
        original.flip();
        replacement.put(original);
        return replacement;
    }

    private static ByteBuffer growReadBuffer(
            ByteBuffer original,
            int suggestedCapacity) {
        int capacity = Math.max(
                suggestedCapacity,
                Math.max(
                        original.capacity() + 1,
                        original.capacity() * 2));
        ByteBuffer replacement =
                ByteBuffer.allocate(capacity);
        replacement.put(original);
        replacement.flip();
        return replacement;
    }

    private static void requirePositive(
            Duration value,
            String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero()
                || value.isNegative()) {
            throw new IllegalArgumentException(
                    name + " must be positive");
        }
    }
}
