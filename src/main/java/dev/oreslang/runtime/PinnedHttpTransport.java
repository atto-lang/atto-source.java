package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Blocking native-worker HTTP/1.1 transport whose physical socket connects to
 * an address already authorized by {@link OutboundEgressPolicy}.
 *
 * <p>This class owns no worker threads. Resolution, connect, TLS, headers and
 * body I/O are intentionally blocking operations and therefore MUST execute on
 * {@link AsyncNativeIo}, never on an actor/root carrier. Cancellation closes
 * the currently active socket; the AsyncNativeIo operation remains the final
 * physical-retirement receipt.</p>
 *
 * <p>V1 deliberately sends Connection: close. There is no idle socket pool
 * yet, so every request is a new physical connection and therefore gets a
 * fresh DNS/policy generation. This is slower than pooling but makes DNS
 * rebinding semantics unambiguous while the transport boundary is
 * established.</p>
 */
final class PinnedHttpTransport implements AutoCloseable {
    private static final Pattern HEADER_NAME =
            Pattern.compile("[!#$%&'*+.^_|~0-9A-Za-z-]+");
    private static final Set<String> RESPONSE_HOP = Set.of(
            "connection",
            "keep-alive",
            "proxy-connection",
            "transfer-encoding",
            "te",
            "trailer",
            "upgrade",
            "content-length",
            "proxy-authenticate",
            "proxy-authorization",
            "http2-settings");
    private static final long DEFAULT_MAX_RESPONSE_BYTES =
            64L * 1024L * 1024L;
    private static final int MAX_STATUS_LINE_BYTES = 8 * 1024;
    private static final int MAX_HEADER_LINE_BYTES = 16 * 1024;
    private static final int MAX_HEADER_FIELDS = 100;
    private static final long MAX_HEADER_BYTES = 32_768L;

    @FunctionalInterface
    interface TlsLayer {
        Socket wrap(
                Socket connected,
                OutboundEgressPolicy.ResolvedTarget target,
                long handshakeDeadlineNanos)
                throws IOException;
    }

    private static final class JdkTlsLayer implements TlsLayer {
        @Override
        public Socket wrap(
                Socket connected,
                OutboundEgressPolicy.ResolvedTarget target,
                long handshakeDeadlineNanos)
                throws IOException {
            SSLSocketFactory factory;
            try {
                // Keep TLS initialization lazy: today's guest http.request
                // remains HTTP loopback-only and should not initialize the TLS
                // stack merely because this transport is constructed.
                factory = SSLContext.getDefault().getSocketFactory();
            } catch (GeneralSecurityException failure) {
                throw new IOException(
                        "default TLS context is unavailable",
                        failure);
            }
            // Layer over an ALREADY CONNECTED, policy-pinned TCP socket.
            // The host argument supplies peer identity/SNI context and does
            // not choose the TCP destination.
            SSLSocket tls = (SSLSocket) factory.createSocket(
                    connected,
                    target.host(),
                    target.port(),
                    true);
            try {
                SSLParameters parameters = tls.getSSLParameters();
                configureTlsIdentity(parameters, target.host());
                tls.setSSLParameters(parameters);
                setReadDeadline(
                        tls,
                        handshakeDeadlineNanos,
                        "TLS handshake");
                tls.startHandshake();
                return tls;
            } catch (Throwable failure) {
                try {
                    tls.close();
                } catch (IOException suppressed) {
                    failure.addSuppressed(suppressed);
                }
                if (failure instanceof IOException io) throw io;
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                throw new IOException("TLS handshake failed", failure);
            }
        }
    }

    private static final class OriginGate {
        private final Semaphore permits;
        private int references;

        private OriginGate(int maximum) {
            permits = new Semaphore(maximum, true);
        }
    }

    private final class ConnectionLease {
        private final String origin;
        private final OriginGate originGate;
        private final AtomicBoolean released = new AtomicBoolean();

        private ConnectionLease(String origin, OriginGate originGate) {
            this.origin = origin;
            this.originGate = originGate;
        }

        void release() {
            if (!released.compareAndSet(false, true)) return;
            synchronized (gateLock) {
                originGate.permits.release();
                originGate.references--;
                if (originGate.references == 0
                        && originGate.permits.availablePermits()
                                == limits.maxConnectionsPerOrigin()) {
                    originGates.remove(origin, originGate);
                }
            }
            globalConnections.release();
        }
    }

    enum BodyMode {
        NONE,
        FIXED,
        CHUNKED,
        UNTIL_CLOSE
    }

    private record ParsedHead(
            int status,
            List<Map.Entry<String, String>> visibleHeaders,
            BodyMode bodyMode,
            long contentLength) { }

    final class Call {
        private final String rawUrl;
        private final String method;
        private final List<Map.Entry<String, String>> requestHeaders;
        private final AtomicBoolean executed = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean retired = new AtomicBoolean();
        private final AtomicReference<Socket> activeSocket =
                new AtomicReference<>();
        private volatile ConnectionLease lease;
        private volatile Response response;
        private volatile long resolutionGeneration;

        private Call(
                String rawUrl,
                String method,
                List<Map.Entry<String, String>> requestHeaders) {
            this.rawUrl = rawUrl;
            this.method = method;
            this.requestHeaders = requestHeaders;
        }

        /** Execute synchronously on a native-I/O worker. */
        Response execute() throws IOException {
            if (!executed.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "pinned HTTP call may execute only once");
            }
            if (cancelled.get()) {
                throw new CancellationException(
                        "pinned HTTP call cancelled before execution");
            }
            if (closed.get()) {
                throw new RejectedExecutionException(
                        "pinned HTTP transport is closed");
            }

            long started = System.nanoTime();
            long overallDeadline = deadlineAfter(
                    started,
                    limits.overallTimeout());

            OutboundEgressPolicy.ResolvedTarget target =
                    OutboundEgressPolicy.resolveForConnection(
                            rawUrl,
                            purpose,
                            policy,
                            resolver);
            resolutionGeneration = target.resolutionGeneration();
            checkDeadline(overallDeadline, "DNS/policy admission");
            requireNotCancelled("after DNS/policy admission");

            lease = acquire(target);
            register(this);
            try {
                Socket socket = connect(
                        this,
                        target,
                        overallDeadline);
                requireNotCancelled("after TCP connect");

                if (target.scheme().equals("https")) {
                    long handshakeDeadline = phaseDeadline(
                            limits.headerTimeout(),
                            overallDeadline);
                    Socket secured = tlsLayer.wrap(
                            socket,
                            target,
                            handshakeDeadline);
                    activeSocket.set(secured);
                    socket = secured;
                    requireNotCancelled("after TLS handshake");
                }

                writeRequest(socket, target, this);
                long headerDeadline = phaseDeadline(
                        limits.headerTimeout(),
                        overallDeadline);
                ParsedHead head = readHead(
                        socket,
                        method,
                        overallDeadline,
                        headerDeadline);
                requireNotCancelled("after response headers");

                Response created = new Response(
                        this,
                        socket,
                        target,
                        head,
                        overallDeadline);
                response = created;
                if (cancelled.get() || closed.get()) {
                    created.close();
                    throw new CancellationException(
                            "pinned HTTP call revoked before response publication");
                }
                return created;
            } catch (Throwable failure) {
                if (response == null) {
                    closeActiveSocket();
                    retire();
                }
                if (failure instanceof IOException io) throw io;
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                throw new IOException("pinned HTTP transport failed", failure);
            }
        }

        void cancel() {
            cancelled.set(true);
            Response delivered = response;
            if (delivered != null) {
                try {
                    delivered.close();
                } catch (IOException ignored) {
                    // AsyncNativeIo worker completion remains the final receipt.
                }
            } else {
                closeActiveSocket();
            }
        }

        boolean cancelled() {
            return cancelled.get();
        }

        long resolutionGeneration() {
            return resolutionGeneration;
        }

        private void requireNotCancelled(String phase) {
            if (cancelled.get() || closed.get()) {
                throw new CancellationException(
                        "pinned HTTP call cancelled " + phase);
            }
        }

        private void closeActiveSocket() {
            Socket socket = activeSocket.getAndSet(null);
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Worker completion carries the final physical receipt.
                }
            }
        }

        private void retire() {
            if (!retired.compareAndSet(false, true)) return;
            activeSocket.set(null);
            live.remove(this);
            ConnectionLease acquired = lease;
            if (acquired != null) acquired.release();
        }
    }

    final class Response implements AutoCloseable {
        private final Call call;
        private final Socket socket;
        private final OutboundEgressPolicy.ResolvedTarget target;
        private final int status;
        private final List<Map.Entry<String, String>> headers;
        private final BodyMode bodyMode;
        private final long contentLength;
        private final long overallDeadlineNanos;
        private final InputStream input;
        private final AtomicBoolean consumed = new AtomicBoolean();
        private final AtomicBoolean responseClosed = new AtomicBoolean();

        private Response(
                Call call,
                Socket socket,
                OutboundEgressPolicy.ResolvedTarget target,
                ParsedHead head,
                long overallDeadlineNanos)
                throws IOException {
            this.call = call;
            this.socket = socket;
            this.target = target;
            status = head.status();
            headers = head.visibleHeaders();
            bodyMode = head.bodyMode();
            contentLength = head.contentLength();
            this.overallDeadlineNanos = overallDeadlineNanos;
            input = socket.getInputStream();
        }

        int status() {
            return status;
        }

        List<Map.Entry<String, String>> headers() {
            return headers;
        }

        String host() {
            return target.host();
        }

        InetAddress connectedAddress() {
            return socket.getInetAddress();
        }

        long resolutionGeneration() {
            return target.resolutionGeneration();
        }

        /** Consume the response body exactly once on a native-I/O worker. */
        long streamBody(
                OutputStream output,
                BooleanSupplier externalCancelled)
                throws IOException {
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(
                    externalCancelled,
                    "externalCancelled");
            if (!consumed.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "pinned HTTP response body already consumed");
            }

            try {
                return switch (bodyMode) {
                    case NONE -> 0L;
                    case FIXED -> streamFixed(
                            output,
                            externalCancelled,
                            contentLength);
                    case CHUNKED -> streamChunked(
                            output,
                            externalCancelled);
                    case UNTIL_CLOSE -> streamUntilClose(
                            output,
                            externalCancelled);
                };
            } finally {
                close();
            }
        }

        @Override
        public void close() throws IOException {
            if (!responseClosed.compareAndSet(false, true)) return;
            IOException failure = null;
            try {
                socket.close();
            } catch (IOException closeFailure) {
                failure = closeFailure;
            } finally {
                call.retire();
            }
            if (failure != null) throw failure;
        }

        private long streamFixed(
                OutputStream output,
                BooleanSupplier externalCancelled,
                long length)
                throws IOException {
            long total = 0;
            byte[] buffer = new byte[8192];
            while (total < length) {
                requireBodyActive(externalCancelled);
                int wanted = (int) Math.min(
                        buffer.length,
                        length - total);
                int count = readBody(buffer, 0, wanted);
                if (count < 0) {
                    throw new EOFException(
                            "upstream closed before Content-Length bytes arrived");
                }
                output.write(buffer, 0, count);
                total = checkedBodyTotal(total, count);
            }
            return total;
        }

        private long streamUntilClose(
                OutputStream output,
                BooleanSupplier externalCancelled)
                throws IOException {
            long total = 0;
            byte[] buffer = new byte[8192];
            while (true) {
                requireBodyActive(externalCancelled);
                int count = readBody(buffer, 0, buffer.length);
                if (count < 0) return total;
                output.write(buffer, 0, count);
                total = checkedBodyTotal(total, count);
            }
        }

        private long streamChunked(
                OutputStream output,
                BooleanSupplier externalCancelled)
                throws IOException {
            long total = 0;
            byte[] buffer = new byte[8192];
            while (true) {
                requireBodyActive(externalCancelled);
                String sizeLine = readBodyLine(
                        MAX_HEADER_LINE_BYTES);
                for (int i = 0; i < sizeLine.length(); i++) {
                    int ch = sizeLine.charAt(i);
                    if (ch < 32 || ch > 126) {
                        throw new IOException(
                                "invalid HTTP chunk extension byte");
                    }
                }
                int extension = sizeLine.indexOf(';');
                String sizeText = (extension < 0
                        ? sizeLine
                        : sizeLine.substring(0, extension)).trim();
                if (sizeText.isEmpty()) {
                    throw new IOException(
                            "empty HTTP chunk size");
                }
                long chunkSize = parseChunkSize(sizeText);
                if (chunkSize == 0) {
                    readTrailers();
                    return total;
                }
                if (chunkSize > maxResponseBytes - total) {
                    throw new IOException(
                            "response body byte budget exceeded");
                }

                long remaining = chunkSize;
                while (remaining != 0) {
                    requireBodyActive(externalCancelled);
                    int wanted = (int) Math.min(
                            buffer.length,
                            remaining);
                    int count = readBody(buffer, 0, wanted);
                    if (count < 0) {
                        throw new EOFException(
                                "upstream closed inside chunk");
                    }
                    output.write(buffer, 0, count);
                    total = checkedBodyTotal(total, count);
                    remaining -= count;
                }
                expectBodyCrlf();
            }
        }

        private int readBody(
                byte[] buffer,
                int offset,
                int length)
                throws IOException {
            setBodyIdleTimeout(
                    socket,
                    limits.bodyIdleTimeout(),
                    overallDeadlineNanos);
            return input.read(buffer, offset, length);
        }

        private String readBodyLine(int maximum)
                throws IOException {
            ByteArrayOutputStream line =
                    new ByteArrayOutputStream();
            while (true) {
                setBodyIdleTimeout(
                        socket,
                        limits.bodyIdleTimeout(),
                        overallDeadlineNanos);
                int value = input.read();
                if (value < 0) {
                    throw new EOFException(
                            "upstream closed inside chunk framing");
                }
                if (value == '\r') {
                    setBodyIdleTimeout(
                            socket,
                            limits.bodyIdleTimeout(),
                            overallDeadlineNanos);
                    int lf = input.read();
                    if (lf != '\n') {
                        throw new IOException(
                                "HTTP chunk framing requires CRLF");
                    }
                    return line.toString(
                            StandardCharsets.ISO_8859_1);
                }
                if (value == '\n') {
                    throw new IOException(
                            "HTTP chunk framing requires CRLF");
                }
                if (line.size() >= maximum) {
                    throw new IOException(
                            "HTTP chunk line exceeds byte budget");
                }
                line.write(value);
            }
        }

        private void expectBodyCrlf() throws IOException {
            setBodyIdleTimeout(
                    socket,
                    limits.bodyIdleTimeout(),
                    overallDeadlineNanos);
            int cr = input.read();
            setBodyIdleTimeout(
                    socket,
                    limits.bodyIdleTimeout(),
                    overallDeadlineNanos);
            int lf = input.read();
            if (cr != '\r' || lf != '\n') {
                throw new IOException(
                        "HTTP chunk payload is not terminated by CRLF");
            }
        }

        private void readTrailers() throws IOException {
            int fields = 0;
            long bytes = 0;
            while (true) {
                String line = readBodyLine(
                        MAX_HEADER_LINE_BYTES);
                if (line.isEmpty()) return;
                if (line.charAt(0) == ' '
                        || line.charAt(0) == '\t') {
                    throw new IOException(
                            "obsolete folded HTTP trailer is rejected");
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    throw new IOException(
                            "malformed HTTP trailer");
                }
                String name = line.substring(0, colon);
                String value = trimOws(
                        line.substring(colon + 1));
                validateHeaderField(name, value);
                if (RESPONSE_HOP.contains(
                        name.toLowerCase(Locale.ROOT))) {
                    throw new IOException(
                            "HTTP trailer may not redefine framing/control fields");
                }
                fields++;
                bytes += (long) name.length()
                        + value.length() + 4L;
                if (fields > MAX_HEADER_FIELDS
                        || bytes > MAX_HEADER_BYTES) {
                    throw new IOException(
                            "HTTP trailer budget exceeded");
                }
            }
        }

        private void requireBodyActive(
                BooleanSupplier externalCancelled)
                throws IOException {
            if (responseClosed.get()
                    || call.cancelled()
                    || externalCancelled.getAsBoolean()
                    || PinnedHttpTransport.this.closed.get()) {
                throw new CancellationException(
                        "pinned HTTP response body cancelled");
            }
            checkDeadline(
                    overallDeadlineNanos,
                    "response body");
        }

        private long checkedBodyTotal(long total, int count)
                throws IOException {
            if (count > maxResponseBytes - total) {
                throw new IOException(
                        "response body byte budget exceeded");
            }
            return total + count;
        }
    }

    private final OutboundEgressPolicy.Purpose purpose;
    private final OutboundEgressPolicy.Policy policy;
    private final OutboundEgressPolicy.Resolver resolver;
    private final OutboundEgressPolicy.Limits limits;
    private final TlsLayer tlsLayer;
    private final long maxResponseBytes;
    private final Semaphore globalConnections;
    private final Object gateLock = new Object();
    private final Map<String, OriginGate> originGates =
            new LinkedHashMap<>();
    private final Set<Call> live =
            ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed =
            new AtomicBoolean();

    PinnedHttpTransport(
            OutboundEgressPolicy.Purpose purpose,
            OutboundEgressPolicy.Policy policy,
            OutboundEgressPolicy.Resolver resolver) {
        this(
                purpose,
                policy,
                resolver,
                new JdkTlsLayer(),
                DEFAULT_MAX_RESPONSE_BYTES);
    }

    PinnedHttpTransport(
            OutboundEgressPolicy.Purpose purpose,
            OutboundEgressPolicy.Policy policy,
            OutboundEgressPolicy.Resolver resolver,
            TlsLayer tlsLayer,
            long maxResponseBytes) {
        this.purpose = Objects.requireNonNull(
                purpose,
                "purpose");
        this.policy = Objects.requireNonNull(
                policy,
                "policy");
        this.resolver = Objects.requireNonNull(
                resolver,
                "resolver");
        this.tlsLayer = Objects.requireNonNull(
                tlsLayer,
                "tlsLayer");
        limits = policy.limits();
        if (maxResponseBytes < 0
                || maxResponseBytes > (1L << 30)) {
            throw new IllegalArgumentException(
                    "maxResponseBytes must be 0..1GiB");
        }
        this.maxResponseBytes = maxResponseBytes;
        globalConnections = new Semaphore(
                limits.maxConnectionsGlobal(),
                true);
    }

    Call newCall(
            String rawUrl,
            String method,
            List<Map.Entry<String, String>> requestHeaders) {
        Objects.requireNonNull(rawUrl, "rawUrl");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(
                requestHeaders,
                "requestHeaders");
        if (!method.equals("GET")
                && !method.equals("HEAD")) {
            throw new IllegalArgumentException(
                    "pinned HTTP transport v1 supports GET or HEAD");
        }

        ArrayList<Map.Entry<String, String>> copied =
                new ArrayList<>(requestHeaders.size());
        long bytes = 0;
        if (requestHeaders.size() > MAX_HEADER_FIELDS) {
            throw new IllegalArgumentException(
                    "outbound request header count exceeds 100");
        }
        for (Map.Entry<String, String> field
                : requestHeaders) {
            Objects.requireNonNull(
                    field,
                    "request header");
            ProxyOutboundLoopback.validateRequestHeader(
                    field.getKey(),
                    field.getValue());
            long fieldBytes =
                    (long) field.getKey().length()
                    + field.getValue().length()
                    + 4L;
            if (fieldBytes > MAX_HEADER_BYTES - bytes) {
                throw new IllegalArgumentException(
                        "outbound request header byte budget exceeded");
            }
            bytes += fieldBytes;
            copied.add(Map.entry(
                    field.getKey(),
                    field.getValue()));
        }
        return new Call(
                rawUrl,
                method,
                List.copyOf(copied));
    }

    int inFlight() {
        return live.size();
    }

    int trackedOrigins() {
        synchronized (gateLock) {
            return originGates.size();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Call call : List.copyOf(live)) {
            call.cancel();
        }
    }

    private ConnectionLease acquire(
            OutboundEgressPolicy.ResolvedTarget target) {
        if (closed.get()) {
            throw new RejectedExecutionException(
                    "pinned HTTP transport is closed");
        }
        if (!globalConnections.tryAcquire()) {
            throw new RejectedExecutionException(
                    "global outbound connection limit exhausted");
        }

        String origin = target.scheme()
                + "://" + target.authority();
        OriginGate gate;
        synchronized (gateLock) {
            gate = originGates.computeIfAbsent(
                    origin,
                    ignored -> new OriginGate(
                            limits.maxConnectionsPerOrigin()));
            gate.references++;
        }
        if (!gate.permits.tryAcquire()) {
            synchronized (gateLock) {
                gate.references--;
                if (gate.references == 0
                        && gate.permits.availablePermits()
                                == limits.maxConnectionsPerOrigin()) {
                    originGates.remove(origin, gate);
                }
            }
            globalConnections.release();
            throw new RejectedExecutionException(
                    "per-origin outbound connection limit exhausted");
        }
        return new ConnectionLease(origin, gate);
    }

    private void register(Call call) {
        if (closed.get()) {
            call.retire();
            throw new RejectedExecutionException(
                    "pinned HTTP transport closed during admission");
        }
        live.add(call);
        if (closed.get()) {
            call.cancel();
            call.retire();
            throw new RejectedExecutionException(
                    "pinned HTTP transport closed during admission");
        }
    }

    private Socket connect(
            Call call,
            OutboundEgressPolicy.ResolvedTarget target,
            long overallDeadline)
            throws IOException {
        long connectDeadline = phaseDeadline(
                limits.connectTimeout(),
                overallDeadline);
        IOException last = null;
        for (int attempt = 0;
                attempt < target.addresses().size();
                attempt++) {
            checkDeadline(
                    connectDeadline,
                    "TCP connect");
            call.requireNotCancelled("before TCP connect");
            Socket socket = new Socket();
            call.activeSocket.set(socket);
            try {
                socket.setTcpNoDelay(true);
                socket.connect(
                        target.connectAddress(attempt),
                        timeoutMillis(
                                connectDeadline,
                                "TCP connect"));
                return socket;
            } catch (IOException failure) {
                last = failure;
                call.activeSocket.compareAndSet(
                        socket,
                        null);
                try {
                    socket.close();
                } catch (IOException suppressed) {
                    failure.addSuppressed(suppressed);
                }
                if (call.cancelled.get()) {
                    throw new CancellationException(
                            "pinned HTTP call cancelled during TCP connect");
                }
            }
        }
        if (last != null) throw last;
        throw new IOException(
                "no policy-authorized outbound address");
    }

    private static void writeRequest(
            Socket socket,
            OutboundEgressPolicy.ResolvedTarget target,
            Call call)
            throws IOException {
        OutputStream output = socket.getOutputStream();
        StringBuilder request = new StringBuilder();
        request.append(call.method)
                .append(' ')
                .append(requestTarget(target))
                .append(" HTTP/1.1\r\n")
                .append("Host: ")
                .append(target.authority())
                .append("\r\n")
                .append("Connection: close\r\n");
        for (Map.Entry<String, String> field
                : call.requestHeaders) {
            request.append(field.getKey())
                    .append(": ")
                    .append(field.getValue())
                    .append("\r\n");
        }
        request.append("\r\n");
        output.write(
                request.toString().getBytes(
                        StandardCharsets.ISO_8859_1));
        output.flush();
    }

    private ParsedHead readHead(
            Socket socket,
            String method,
            long overallDeadline,
            long headerDeadline)
            throws IOException {
        InputStream input = socket.getInputStream();
        String statusLine = readHeaderLine(
                input,
                socket,
                headerDeadline,
                MAX_STATUS_LINE_BYTES);
        int status = parseStatus(statusLine);

        ArrayList<Map.Entry<String, String>> raw =
                new ArrayList<>();
        long headerBytes = 0;
        while (true) {
            String line = readHeaderLine(
                    input,
                    socket,
                    headerDeadline,
                    MAX_HEADER_LINE_BYTES);
            if (line.isEmpty()) break;
            if (line.charAt(0) == ' '
                    || line.charAt(0) == '\t') {
                throw new IOException(
                        "obsolete folded HTTP header is rejected");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException(
                        "malformed upstream HTTP header");
            }
            String name = line.substring(0, colon);
            String value = trimOws(
                    line.substring(colon + 1));
            validateHeaderField(name, value);
            raw.add(Map.entry(name, value));
            headerBytes += (long) name.length()
                    + value.length() + 4L;
            if (raw.size() > MAX_HEADER_FIELDS
                    || headerBytes > MAX_HEADER_BYTES) {
                throw new IOException(
                        "upstream header budget exceeded");
            }
        }
        checkDeadline(
                overallDeadline,
                "response headers");
        return classifyResponse(method, status, raw);
    }

    private ParsedHead classifyResponse(
            String method,
            int status,
            List<Map.Entry<String, String>> raw)
            throws IOException {
        ArrayList<String> transferEncodings =
                new ArrayList<>();
        ArrayList<String> lengths =
                new ArrayList<>();
        Set<String> excluded =
                new HashSet<>(RESPONSE_HOP);

        for (Map.Entry<String, String> field : raw) {
            String lower = field.getKey()
                    .toLowerCase(Locale.ROOT);
            if (lower.equals("transfer-encoding")) {
                for (String token
                        : field.getValue().split(",", -1)) {
                    String normalized =
                            token.trim()
                                    .toLowerCase(
                                            Locale.ROOT);
                    if (normalized.isEmpty()) {
                        throw new IOException(
                                "empty Transfer-Encoding token");
                    }
                    transferEncodings.add(
                            normalized);
                }
            } else if (lower.equals("content-length")) {
                lengths.add(
                        field.getValue().trim());
            } else if (lower.equals("connection")) {
                for (String token
                        : field.getValue().split(",", -1)) {
                    String normalized =
                            token.trim();
                    if (!HEADER_NAME.matcher(
                            normalized).matches()) {
                        throw new IOException(
                                "invalid upstream Connection token");
                    }
                    excluded.add(
                            normalized.toLowerCase(
                                    Locale.ROOT));
                }
            }
        }

        if (!transferEncodings.isEmpty()
                && !lengths.isEmpty()) {
            throw new IOException(
                    "upstream response has both Transfer-Encoding and Content-Length");
        }

        BodyMode mode;
        long contentLength = -1L;
        if (method.equals("HEAD")
                || status == 204
                || status == 304) {
            mode = BodyMode.NONE;
        } else if (!transferEncodings.isEmpty()) {
            if (transferEncodings.size() != 1
                    || !transferEncodings.getFirst()
                            .equals("chunked")) {
                throw new IOException(
                        "unsupported upstream Transfer-Encoding");
            }
            mode = BodyMode.CHUNKED;
        } else if (!lengths.isEmpty()) {
            Long parsed = null;
            for (String value : lengths) {
                long candidate = parseContentLength(
                        value);
                if (parsed != null
                        && parsed.longValue()
                                != candidate) {
                    throw new IOException(
                            "conflicting upstream Content-Length values");
                }
                parsed = candidate;
            }
            contentLength = parsed == null
                    ? -1L
                    : parsed;
            if (contentLength > maxResponseBytes) {
                throw new IOException(
                        "response Content-Length exceeds byte budget");
            }
            mode = BodyMode.FIXED;
        } else {
            mode = BodyMode.UNTIL_CLOSE;
        }

        ArrayList<Map.Entry<String, String>> visible =
                new ArrayList<>();
        for (Map.Entry<String, String> field : raw) {
            if (!excluded.contains(
                    field.getKey()
                            .toLowerCase(Locale.ROOT))) {
                visible.add(field);
            }
        }
        return new ParsedHead(
                status,
                List.copyOf(visible),
                mode,
                contentLength);
    }

    private static int parseStatus(String line)
            throws IOException {
        if (!(line.startsWith("HTTP/1.1 ")
                || line.startsWith("HTTP/1.0 "))) {
            throw new IOException(
                    "unsupported upstream HTTP status line");
        }
        int firstSpace = line.indexOf(' ');
        if (firstSpace < 0
                || line.length() < firstSpace + 4) {
            throw new IOException(
                    "malformed upstream HTTP status line");
        }
        String code = line.substring(
                firstSpace + 1,
                firstSpace + 4);
        for (int i = 0; i < code.length(); i++) {
            if (!Character.isDigit(code.charAt(i))) {
                throw new IOException(
                        "malformed upstream HTTP status code");
            }
        }
        int codeEnd = firstSpace + 4;
        if (line.length() > codeEnd
                && line.charAt(codeEnd) != ' ') {
            throw new IOException(
                    "malformed upstream HTTP status delimiter");
        }
        int status = Integer.parseInt(code);
        if (status < 200 || status > 599) {
            throw new IOException(
                    "unsupported non-final upstream status");
        }
        return status;
    }

    private static long parseContentLength(
            String value)
            throws IOException {
        if (value.isEmpty()) {
            throw new IOException(
                    "empty upstream Content-Length");
        }
        long result = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                throw new IOException(
                        "invalid upstream Content-Length");
            }
            int digit = ch - '0';
            if (result > (Long.MAX_VALUE - digit) / 10L) {
                throw new IOException(
                        "upstream Content-Length overflow");
            }
            result = result * 10L + digit;
        }
        return result;
    }

    private static long parseChunkSize(
            String value)
            throws IOException {
        long result = 0;
        for (int i = 0; i < value.length(); i++) {
            int digit = Character.digit(
                    value.charAt(i),
                    16);
            if (digit < 0) {
                throw new IOException(
                        "invalid HTTP chunk size");
            }
            if (result > (Long.MAX_VALUE - digit) / 16L) {
                throw new IOException(
                        "HTTP chunk size overflow");
            }
            result = result * 16L + digit;
        }
        return result;
    }

    private static String readHeaderLine(
            InputStream input,
            Socket socket,
            long deadline,
            int maximum)
            throws IOException {
        ByteArrayOutputStream line =
                new ByteArrayOutputStream();
        while (true) {
            setReadDeadline(
                    socket,
                    deadline,
                    "response headers");
            int value = input.read();
            if (value < 0) {
                throw new EOFException(
                        "upstream closed during response headers");
            }
            if (value == '\r') {
                setReadDeadline(
                        socket,
                        deadline,
                        "response headers");
                int lf = input.read();
                if (lf != '\n') {
                    throw new IOException(
                            "HTTP headers require CRLF");
                }
                return line.toString(
                        StandardCharsets.ISO_8859_1);
            }
            if (value == '\n') {
                throw new IOException(
                        "HTTP headers require CRLF");
            }
            if (line.size() >= maximum) {
                throw new IOException(
                        "HTTP header line exceeds byte budget");
            }
            line.write(value);
        }
    }

    static void configureTlsIdentity(
            SSLParameters parameters,
            String host) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(host, "host");
        parameters.setEndpointIdentificationAlgorithm(
                "HTTPS");
        if (!isIpLiteral(host)) {
            parameters.setServerNames(
                    List.of(new SNIHostName(host)));
        } else {
            // Never inherit stale SNI from a reused/custom SSLParameters
            // instance when the canonical authority is an IP literal.
            parameters.setServerNames(List.of());
        }
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) return true;
        if (host.isEmpty()) return false;
        boolean dot = false;
        for (int i = 0; i < host.length(); i++) {
            char ch = host.charAt(i);
            if (ch == '.') {
                dot = true;
            } else if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return dot;
    }

    private static String requestTarget(
            OutboundEgressPolicy.ResolvedTarget target) {
        String ascii = target.uri().toASCIIString();
        String prefix = target.scheme()
                + "://" + target.authority();
        if (!ascii.startsWith(prefix)) {
            throw new IllegalStateException(
                    "canonical URI authority mismatch");
        }
        String result = ascii.substring(
                prefix.length());
        return result.isEmpty() ? "/" : result;
    }

    private static void validateHeaderField(
            String name,
            String value)
            throws IOException {
        if (!HEADER_NAME.matcher(name).matches()) {
            throw new IOException(
                    "invalid upstream header name");
        }
        for (int i = 0; i < value.length(); i++) {
            int ch = value.charAt(i);
            if (ch < 32 || ch == 127 || ch > 255) {
                throw new IOException(
                        "invalid upstream header value");
            }
        }
    }

    private static String trimOws(String value) {
        int start = 0;
        int end = value.length();
        while (start < end
                && (value.charAt(start) == ' '
                        || value.charAt(start) == '\t')) {
            start++;
        }
        while (end > start
                && (value.charAt(end - 1) == ' '
                        || value.charAt(end - 1) == '\t')) {
            end--;
        }
        return value.substring(start, end);
    }

    private static long deadlineAfter(
            long start,
            Duration duration) {
        long nanos;
        try {
            nanos = duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        if (Long.MAX_VALUE - start < nanos) {
            return Long.MAX_VALUE;
        }
        return start + nanos;
    }

    private static long phaseDeadline(
            Duration duration,
            long overallDeadline) {
        long phase = deadlineAfter(
                System.nanoTime(),
                duration);
        return Math.min(
                phase,
                overallDeadline);
    }

    private static void checkDeadline(
            long deadline,
            String phase)
            throws SocketTimeoutException {
        if (System.nanoTime() >= deadline) {
            throw new SocketTimeoutException(
                    phase + " deadline exceeded");
        }
    }

    private static int timeoutMillis(
            long deadline,
            String phase)
            throws SocketTimeoutException {
        long remaining = deadline
                - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException(
                    phase + " deadline exceeded");
        }
        long millis = Math.max(
                1L,
                (remaining + 999_999L)
                        / 1_000_000L);
        return (int) Math.min(
                Integer.MAX_VALUE,
                millis);
    }

    private static void setReadDeadline(
            Socket socket,
            long deadline,
            String phase)
            throws IOException {
        socket.setSoTimeout(
                timeoutMillis(
                        deadline,
                        phase));
    }

    private static void setBodyIdleTimeout(
            Socket socket,
            Duration idle,
            long overallDeadline)
            throws IOException {
        long idleDeadline = phaseDeadline(
                idle,
                overallDeadline);
        setReadDeadline(
                socket,
                idleDeadline,
                "response body");
    }
}
