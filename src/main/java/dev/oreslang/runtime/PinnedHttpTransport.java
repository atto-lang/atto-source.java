package dev.oreslang.runtime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * Blocking host-side HTTP/1.1 transport for native-I/O workers.
 *
 * <p>The security boundary is an {@link OutboundEgressPolicy.ResolvedTarget}.
 * Every physical connection resolves and authorizes exactly once, then connects
 * its TCP socket directly to one of the returned {@link InetAddress} values.
 * Hostnames are never handed to the TCP connect path.</p>
 *
 * <p>HTTPS wraps the already-connected socket. The canonical hostname remains
 * available only for TLS SNI and endpoint identification. This prevents a
 * lower networking layer from re-resolving DNS after policy admission.</p>
 *
 * <p>Version one intentionally uses {@code Connection: close}: there is no idle
 * connection pool and therefore no stale DNS authorization that can survive
 * across physical connections. Per-origin and global concurrent connection
 * limits are still enforced. Pooling can be added later only if every newly
 * created pooled connection obtains a fresh resolution generation.</p>
 */
final class PinnedHttpTransport implements AutoCloseable {
    private static final Pattern HEADER_NAME =
            Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final int MAX_HEADER_FIELDS = 100;
    private static final int MAX_HEADER_BYTES = 32_768;
    private static final int MAX_LINE_BYTES = 8_192;
    private static final int MAX_INFORMATIONAL = 8;
    private static final int MAX_CHUNK_LINE_BYTES = 128;
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
            "host",
            "http2-settings");

    private final OutboundEgressPolicy.Purpose purpose;
    private final OutboundEgressPolicy.Policy policy;
    private final OutboundEgressPolicy.Resolver resolver;
    private final SSLContext sslContext;
    private final long maxResponseBytes;
    private final ConnectionBudget budget;
    private final Set<Socket> liveSockets =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Response> liveResponses =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicBoolean closed = new AtomicBoolean();

    private static final class OriginLimiter {
        final Semaphore permits;
        int users;

        OriginLimiter(int limit) {
            permits = new Semaphore(limit);
        }
    }

    /**
     * Context-shareable admission state. Individual request transports may be
     * independently revoked without losing global/per-origin accounting.
     */
    static final class ConnectionBudget {
        private final int maxPerOrigin;
        private final int maxGlobal;
        private final Semaphore globalConnections;
        private final Map<String, OriginLimiter> origins = new HashMap<>();

        ConnectionBudget(OutboundEgressPolicy.Limits limits) {
            Objects.requireNonNull(limits, "limits");
            this.maxPerOrigin = limits.maxConnectionsPerOrigin();
            this.maxGlobal = limits.maxConnectionsGlobal();
            this.globalConnections = new Semaphore(maxGlobal);
        }

        void requireCompatible(OutboundEgressPolicy.Limits limits) {
            if (limits.maxConnectionsPerOrigin() != maxPerOrigin
                    || limits.maxConnectionsGlobal() != maxGlobal) {
                throw new IllegalArgumentException(
                        "shared HTTP connection budget does not match policy limits");
            }
        }

        ConnectionLease acquire(String origin) {
            Objects.requireNonNull(origin, "origin");
            if (!globalConnections.tryAcquire()) {
                throw new RejectedExecutionException(
                        "global pinned HTTP connection capacity exhausted");
            }

            synchronized (this) {
                OriginLimiter limiter = origins.computeIfAbsent(
                        origin,
                        ignored -> new OriginLimiter(maxPerOrigin));
                if (!limiter.permits.tryAcquire()) {
                    globalConnections.release();
                    if (limiter.users == 0) origins.remove(origin, limiter);
                    throw new RejectedExecutionException(
                            "per-origin pinned HTTP connection capacity exhausted: "
                                    + origin);
                }
                limiter.users++;
                return new ConnectionLease(this, origin, limiter);
            }
        }

        private void release(
                String origin,
                OriginLimiter limiter) {
            globalConnections.release();
            synchronized (this) {
                limiter.permits.release();
                limiter.users--;
                if (limiter.users == 0
                        && limiter.permits.availablePermits() == maxPerOrigin) {
                    origins.remove(origin, limiter);
                }
            }
        }

        synchronized int activeOrigins() {
            return origins.size();
        }
    }

    static final class ConnectionLease {
        private final ConnectionBudget budget;
        private final String origin;
        private final OriginLimiter limiter;
        private final AtomicBoolean released = new AtomicBoolean();

        ConnectionLease(
                ConnectionBudget budget,
                String origin,
                OriginLimiter limiter) {
            this.budget = budget;
            this.origin = origin;
            this.limiter = limiter;
        }

        void release() {
            if (!released.compareAndSet(false, true)) return;
            budget.release(origin, limiter);
        }
    }

    private enum Framing {
        NONE,
        CONTENT_LENGTH,
        CHUNKED,
        CLOSE_DELIMITED
    }

    private record ParsedHead(
            int status,
            List<Map.Entry<String, String>> headers,
            Framing framing,
            long contentLength) { }

    static final class Response implements AutoCloseable {
        private final PinnedHttpTransport owner;
        private final Socket socket;
        private final BufferedInputStream input;
        private final ConnectionLease lease;
        private final int status;
        private final List<Map.Entry<String, String>> headers;
        private final Framing framing;
        private final long contentLength;
        private final long deadlineNanos;
        private final Duration bodyIdleTimeout;
        private final long maxResponseBytes;
        private final AtomicBoolean consumed = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private Response(
                PinnedHttpTransport owner,
                Socket socket,
                BufferedInputStream input,
                ConnectionLease lease,
                ParsedHead head,
                long deadlineNanos,
                Duration bodyIdleTimeout,
                long maxResponseBytes) {
            this.owner = owner;
            this.socket = socket;
            this.input = input;
            this.lease = lease;
            this.status = head.status();
            this.headers = head.headers();
            this.framing = head.framing();
            this.contentLength = head.contentLength();
            this.deadlineNanos = deadlineNanos;
            this.bodyIdleTimeout = bodyIdleTimeout;
            this.maxResponseBytes = maxResponseBytes;
        }

        int status() {
            return status;
        }

        List<Map.Entry<String, String>> headers() {
            return headers;
        }

        List<String> headerValues(String name) {
            Objects.requireNonNull(name, "name");
            ArrayList<String> values = new ArrayList<>();
            for (Map.Entry<String, String> field : headers) {
                if (field.getKey().equalsIgnoreCase(name)) {
                    values.add(field.getValue());
                }
            }
            return List.copyOf(values);
        }

        long streamBody(OutputStream target, BooleanSupplier cancelled)
                throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(cancelled, "cancelled");
            if (!consumed.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "pinned HTTP response body already consumed");
            }

            long total = 0;
            try {
                switch (framing) {
                    case NONE -> {
                        return 0;
                    }
                    case CONTENT_LENGTH -> {
                        long remaining = contentLength;
                        byte[] buffer = new byte[8192];
                        while (remaining != 0) {
                            checkCancelled(cancelled);
                            configureBodyReadTimeout();
                            int limit = (int) Math.min(buffer.length, remaining);
                            int read = input.read(buffer, 0, limit);
                            if (read < 0) {
                                throw new EOFException(
                                        "upstream closed before Content-Length bytes arrived");
                            }
                            total = addBodyBytes(total, read);
                            target.write(buffer, 0, read);
                            remaining -= read;
                        }
                        return total;
                    }
                    case CHUNKED -> {
                        while (true) {
                            checkCancelled(cancelled);
                            configureBodyReadTimeout();
                            long chunk = readChunkSize(input);
                            if (chunk == 0) {
                                readTrailers(input);
                                return total;
                            }
                            long remaining = chunk;
                            byte[] buffer = new byte[8192];
                            while (remaining != 0) {
                                checkCancelled(cancelled);
                                configureBodyReadTimeout();
                                int limit = (int) Math.min(buffer.length, remaining);
                                int read = input.read(buffer, 0, limit);
                                if (read < 0) {
                                    throw new EOFException(
                                            "upstream closed inside chunked body");
                                }
                                total = addBodyBytes(total, read);
                                target.write(buffer, 0, read);
                                remaining -= read;
                            }
                            expectCrlf(input, "chunk payload");
                        }
                    }
                    case CLOSE_DELIMITED -> {
                        byte[] buffer = new byte[8192];
                        while (true) {
                            checkCancelled(cancelled);
                            configureBodyReadTimeout();
                            int read = input.read(buffer);
                            if (read < 0) return total;
                            total = addBodyBytes(total, read);
                            target.write(buffer, 0, read);
                        }
                    }
                }
                throw new AssertionError("unknown body framing");
            } catch (SocketTimeoutException timeout) {
                throw new IOException(
                        "pinned HTTP response body deadline expired",
                        timeout);
            } finally {
                close();
            }
        }

        private long addBodyBytes(long current, int amount)
                throws IOException {
            if (amount < 0 || amount > maxResponseBytes - current) {
                throw new IOException(
                        "pinned HTTP response body byte budget exceeded");
            }
            return current + amount;
        }

        private void checkCancelled(BooleanSupplier cancelled)
                throws IOException {
            if (closed.get() || owner.closed.get() || cancelled.getAsBoolean()) {
                throw new IOException(
                        "pinned HTTP response body cancelled or transport closed");
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new SocketTimeoutException(
                        "pinned HTTP overall deadline expired");
            }
        }

        private void configureBodyReadTimeout() throws IOException {
            socket.setSoTimeout(timeoutMillis(
                    bodyIdleTimeout,
                    deadlineNanos,
                    "body idle/overall deadline"));
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            IOException failure = null;
            try {
                socket.close();
            } catch (IOException closeFailure) {
                failure = closeFailure;
            } finally {
                owner.liveResponses.remove(this);
                owner.liveSockets.remove(socket);
                lease.release();
            }
            if (failure != null) throw failure;
        }

        private long readChunkSize(InputStream source) throws IOException {
            String line = readCrlfLine(
                    source,
                    MAX_CHUNK_LINE_BYTES,
                    "chunk size");
            if (line.isEmpty() || line.indexOf(';') >= 0) {
                throw new IOException(
                        "unsupported or malformed HTTP chunk size");
            }
            long size = 0;
            for (int i = 0; i < line.length(); i++) {
                int digit = Character.digit(line.charAt(i), 16);
                if (digit < 0 || size > (Long.MAX_VALUE - digit) / 16) {
                    throw new IOException(
                            "malformed or overflowing HTTP chunk size");
                }
                size = size * 16 + digit;
            }
            if (size > maxResponseBytes) {
                throw new IOException(
                        "HTTP chunk exceeds response byte budget");
            }
            return size;
        }

        private void readTrailers(InputStream source) throws IOException {
            int fields = 0;
            int bytes = 0;
            while (true) {
                configureBodyReadTimeout();
                String line = readCrlfLine(
                        source,
                        MAX_LINE_BYTES,
                        "trailer");
                bytes = checkedHeaderBytes(bytes, line);
                if (line.isEmpty()) return;
                if (++fields > MAX_HEADER_FIELDS) {
                    throw new IOException(
                            "HTTP trailer field count exceeds "
                                    + MAX_HEADER_FIELDS);
                }
                Map.Entry<String, String> field = parseHeaderLine(line);
                String lower = field.getKey().toLowerCase(Locale.ROOT);
                if (lower.equals("content-length")
                        || lower.equals("transfer-encoding")
                        || lower.equals("host")
                        || lower.equals("connection")) {
                    throw new IOException(
                            "forbidden framing/control HTTP trailer: "
                                    + field.getKey());
                }
            }
        }
    }

    PinnedHttpTransport(
            OutboundEgressPolicy.Purpose purpose,
            OutboundEgressPolicy.Policy policy,
            OutboundEgressPolicy.Resolver resolver,
            SSLContext sslContext,
            long maxResponseBytes) {
        this(
                purpose,
                policy,
                resolver,
                sslContext,
                maxResponseBytes,
                null);
    }

    PinnedHttpTransport(
            OutboundEgressPolicy.Purpose purpose,
            OutboundEgressPolicy.Policy policy,
            OutboundEgressPolicy.Resolver resolver,
            SSLContext sslContext,
            long maxResponseBytes,
            ConnectionBudget sharedBudget) {
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
        if (maxResponseBytes < 0 || maxResponseBytes > (1L << 30)) {
            throw new IllegalArgumentException(
                    "maxResponseBytes must be 0..1GiB");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.budget = sharedBudget == null
                ? new ConnectionBudget(policy.limits())
                : sharedBudget;
        this.budget.requireCompatible(policy.limits());
    }

    static PinnedHttpTransport systemDefault(
            OutboundEgressPolicy.Purpose purpose,
            OutboundEgressPolicy.Policy policy,
            long maxResponseBytes) {
        return systemDefault(
                purpose,
                policy,
                maxResponseBytes,
                null);
    }

    static PinnedHttpTransport systemDefault(
            OutboundEgressPolicy.Purpose purpose,
            OutboundEgressPolicy.Policy policy,
            long maxResponseBytes,
            ConnectionBudget sharedBudget) {
        try {
            return new PinnedHttpTransport(
                    purpose,
                    policy,
                    OutboundEgressPolicy.Resolver.system(),
                    SSLContext.getDefault(),
                    maxResponseBytes,
                    sharedBudget);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "default TLS context unavailable",
                    failure);
        }
    }

    Response request(
            URI target,
            String method,
            List<Map.Entry<String, String>> requestHeaders)
            throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(requestHeaders, "requestHeaders");
        if (!method.equals("GET") && !method.equals("HEAD")) {
            throw new IllegalArgumentException(
                    "pinned HTTP v1 supports only GET and HEAD");
        }
        validateRequestHeaders(requestHeaders);
        if (closed.get()) {
            throw new RejectedExecutionException(
                    "pinned HTTP transport is closed");
        }

        URI canonical =
                OutboundEgressPolicy.canonicalUri(target.toASCIIString());
        String origin =
                canonical.getScheme() + "://" + canonical.getRawAuthority();
        ConnectionLease lease = acquire(origin);
        Socket socket = null;
        boolean transferred = false;
        long deadlineNanos = deadlineAfter(policy.limits().overallTimeout());
        try {
            // Admission precedes DNS so exhausted origin/global capacity cannot
            // be bypassed into an unbounded resolver stampede.
            OutboundEgressPolicy.ResolvedTarget resolved =
                    OutboundEgressPolicy.resolveForConnection(
                            canonical,
                            purpose,
                            policy,
                            resolver);
            socket = connect(resolved, deadlineNanos);
            if (closed.get()) {
                throw new RejectedExecutionException(
                        "pinned HTTP transport closed during connect");
            }

            BufferedOutputStream output =
                    new BufferedOutputStream(socket.getOutputStream());
            writeRequest(
                    output,
                    resolved,
                    method,
                    requestHeaders);
            output.flush();

            socket.setSoTimeout(timeoutMillis(
                    policy.limits().headerTimeout(),
                    deadlineNanos,
                    "header/overall deadline"));
            BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());
            ParsedHead head = readFinalHead(
                    input,
                    method,
                    maxResponseBytes);

            Response response = new Response(
                    this,
                    socket,
                    input,
                    lease,
                    head,
                    deadlineNanos,
                    policy.limits().bodyIdleTimeout(),
                    maxResponseBytes);
            liveResponses.add(response);
            if (closed.get()) {
                response.close();
                throw new RejectedExecutionException(
                        "pinned HTTP transport closed before response handoff");
            }
            transferred = true;
            return response;
        } catch (SocketTimeoutException timeout) {
            throw new IOException(
                    "pinned HTTP connect/header deadline expired",
                    timeout);
        } finally {
            if (!transferred) {
                if (socket != null) {
                    liveSockets.remove(socket);
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // Preserve the primary admission/transport failure.
                    }
                }
                lease.release();
            }
        }
    }

    private Socket connect(
            OutboundEgressPolicy.ResolvedTarget target,
            long deadlineNanos)
            throws IOException {
        IOException failure = null;
        for (int attempt = 0; attempt < target.addresses().size(); attempt++) {
            if (closed.get()) {
                throw new RejectedExecutionException(
                        "pinned HTTP transport closed before connect");
            }
            Socket active = new Socket();
            liveSockets.add(active);
            try {
                active.setTcpNoDelay(true);
                int timeout = timeoutMillis(
                        policy.limits().connectTimeout(),
                        deadlineNanos,
                        "connect/overall deadline");
                InetSocketAddress address = target.connectAddress(attempt);
                active.connect(address, timeout);
                if (closed.get()) {
                    throw new RejectedExecutionException(
                            "pinned HTTP transport closed during connect");
                }
                if (!target.scheme().equals("https")) {
                    return active;
                }

                Socket raw = active;
                SSLSocket tls = (SSLSocket) sslContext
                        .getSocketFactory()
                        .createSocket(
                                raw,
                                target.host(),
                                target.port(),
                                true);
                active = tls;
                // Publish the wrapper before removing the raw alias so a
                // concurrent close never observes a gap with no revocable
                // handle for this physical connection.
                liveSockets.add(active);
                liveSockets.remove(raw);
                if (closed.get()) {
                    throw new RejectedExecutionException(
                            "pinned HTTP transport closed during TLS wrapping");
                }
                tls.setUseClientMode(true);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (!isIpLiteral(target.host())) {
                    parameters.setServerNames(
                            List.of(new SNIHostName(target.host())));
                }
                tls.setSSLParameters(parameters);
                tls.setSoTimeout(timeoutMillis(
                        policy.limits().connectTimeout(),
                        deadlineNanos,
                        "TLS/overall deadline"));
                tls.startHandshake();
                if (closed.get()) {
                    throw new RejectedExecutionException(
                            "pinned HTTP transport closed during TLS handshake");
                }
                return tls;
            } catch (IOException | RuntimeException connectFailure) {
                liveSockets.remove(active);
                try {
                    active.close();
                } catch (IOException suppressed) {
                    connectFailure.addSuppressed(suppressed);
                }
                if (connectFailure instanceof IOException io) {
                    failure = io;
                } else {
                    throw connectFailure;
                }
            }
        }
        if (failure != null) throw failure;
        throw new IOException(
                "pinned HTTP target had no authorized connect addresses");
    }

    private ConnectionLease acquire(String origin) {
        if (closed.get()) {
            throw new RejectedExecutionException(
                    "pinned HTTP transport is closed");
        }
        ConnectionLease lease = budget.acquire(origin);
        if (closed.get()) {
            lease.release();
            throw new RejectedExecutionException(
                    "pinned HTTP transport closed during admission");
        }
        return lease;
    }

    private static void writeRequest(
            OutputStream output,
            OutboundEgressPolicy.ResolvedTarget target,
            String method,
            List<Map.Entry<String, String>> headers)
            throws IOException {
        String path = target.uri().getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        String query = target.uri().getRawQuery();
        String requestTarget =
                query == null ? path : path + "?" + query;

        writeAscii(output, method + " " + requestTarget + " HTTP/1.1\r\n");
        writeAscii(output, "Host: " + target.authority() + "\r\n");
        writeAscii(output, "Connection: close\r\n");
        for (Map.Entry<String, String> field : headers) {
            writeLatin1(
                    output,
                    field.getKey()
                            + ": "
                            + field.getValue()
                            + "\r\n");
        }
        writeAscii(output, "\r\n");
    }

    private static void writeAscii(OutputStream output, String value)
            throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeLatin1(OutputStream output, String value)
            throws IOException {
        output.write(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static ParsedHead readFinalHead(
            BufferedInputStream input,
            String method,
            long maxResponseBytes)
            throws IOException {
        for (int informational = 0;
                informational <= MAX_INFORMATIONAL;
                informational++) {
            ParsedHead head = readHead(input, method, maxResponseBytes);
            if (head.status() >= 200) return head;
            if (head.status() == 101) {
                throw new IOException(
                        "HTTP protocol upgrade responses are unsupported");
            }
        }
        throw new IOException(
                "too many informational HTTP responses");
    }

    private static ParsedHead readHead(
            BufferedInputStream input,
            String method,
            long maxResponseBytes)
            throws IOException {
        String statusLine =
                readCrlfLine(input, MAX_LINE_BYTES, "status line");
        if (!(statusLine.startsWith("HTTP/1.1 ")
                || statusLine.startsWith("HTTP/1.0 "))) {
            throw new IOException(
                    "unsupported or malformed HTTP status line");
        }
        int firstSpace = statusLine.indexOf(' ');
        int secondSpace = statusLine.indexOf(' ', firstSpace + 1);
        String codeText = secondSpace < 0
                ? statusLine.substring(firstSpace + 1)
                : statusLine.substring(firstSpace + 1, secondSpace);
        if (codeText.length() != 3
                || !codeText.chars().allMatch(Character::isDigit)) {
            throw new IOException(
                    "malformed HTTP status code");
        }
        int status = Integer.parseInt(codeText);
        if (status < 100 || status > 599) {
            throw new IOException(
                    "HTTP status outside 100..599");
        }

        ArrayList<Map.Entry<String, String>> headers = new ArrayList<>();
        int rawBytes = statusLine.length() + 2;
        while (true) {
            String line =
                    readCrlfLine(input, MAX_LINE_BYTES, "header");
            rawBytes = checkedHeaderBytes(rawBytes, line);
            if (line.isEmpty()) break;
            if (headers.size() >= MAX_HEADER_FIELDS) {
                throw new IOException(
                        "HTTP response header count exceeds "
                                + MAX_HEADER_FIELDS);
            }
            headers.add(parseHeaderLine(line));
        }

        List<Map.Entry<String, String>> immutable = List.copyOf(headers);
        Framing framing;
        long length = -1;

        if (method.equals("HEAD")
                || status < 200
                || status == 204
                || status == 205
                || status == 304) {
            framing = Framing.NONE;
        } else {
            List<String> transferEncoding =
                    values(immutable, "transfer-encoding");
            List<String> contentLength =
                    values(immutable, "content-length");
            if (!transferEncoding.isEmpty()
                    && !contentLength.isEmpty()) {
                throw new IOException(
                        "HTTP response contains both Transfer-Encoding and Content-Length");
            }
            if (!transferEncoding.isEmpty()) {
                if (transferEncoding.size() != 1
                        || !transferEncoding.getFirst()
                                .trim()
                                .equalsIgnoreCase("chunked")) {
                    throw new IOException(
                            "unsupported HTTP Transfer-Encoding");
                }
                framing = Framing.CHUNKED;
            } else if (!contentLength.isEmpty()) {
                if (contentLength.size() != 1) {
                    throw new IOException(
                            "multiple HTTP Content-Length fields are rejected");
                }
                String rawLength = contentLength.getFirst().trim();
                if (rawLength.isEmpty()
                        || !rawLength.chars().allMatch(Character::isDigit)) {
                    throw new IOException(
                            "malformed HTTP Content-Length");
                }
                try {
                    length = Long.parseLong(rawLength);
                } catch (NumberFormatException overflow) {
                    throw new IOException(
                            "overflowing HTTP Content-Length",
                            overflow);
                }
                if (length > maxResponseBytes) {
                    throw new IOException(
                            "HTTP Content-Length exceeds response byte budget");
                }
                framing = Framing.CONTENT_LENGTH;
            } else {
                framing = Framing.CLOSE_DELIMITED;
            }
        }

        return new ParsedHead(
                status,
                filteredResponseHeaders(immutable),
                framing,
                length);
    }

    private static List<Map.Entry<String, String>> filteredResponseHeaders(
            List<Map.Entry<String, String>> headers)
            throws IOException {
        Set<String> excluded = new HashSet<>(RESPONSE_HOP);
        for (String connection : values(headers, "connection")) {
            for (String item : connection.split(",", -1)) {
                String token = item.trim();
                if (token.isEmpty()
                        || !HEADER_NAME.matcher(token).matches()) {
                    throw new IOException(
                            "invalid HTTP Connection header token");
                }
                excluded.add(token.toLowerCase(Locale.ROOT));
            }
        }

        ArrayList<Map.Entry<String, String>> retained = new ArrayList<>();
        for (Map.Entry<String, String> field : headers) {
            if (!excluded.contains(
                    field.getKey().toLowerCase(Locale.ROOT))) {
                retained.add(field);
            }
        }
        return List.copyOf(retained);
    }

    private static List<String> values(
            List<Map.Entry<String, String>> headers,
            String name) {
        ArrayList<String> values = new ArrayList<>();
        for (Map.Entry<String, String> field : headers) {
            if (field.getKey().equalsIgnoreCase(name)) {
                values.add(field.getValue());
            }
        }
        return values;
    }

    private static Map.Entry<String, String> parseHeaderLine(String line)
            throws IOException {
        if (line.isEmpty()
                || line.charAt(0) == ' '
                || line.charAt(0) == '\t') {
            throw new IOException(
                    "HTTP obs-fold/whitespace-prefixed header is forbidden");
        }
        int colon = line.indexOf(':');
        if (colon <= 0) {
            throw new IOException(
                    "malformed HTTP header field");
        }
        String name = line.substring(0, colon);
        if (!HEADER_NAME.matcher(name).matches()) {
            throw new IOException(
                    "invalid HTTP header name");
        }
        String value = line.substring(colon + 1).strip();
        for (int i = 0; i < value.length(); i++) {
            int ch = value.charAt(i);
            if (ch < 32 || ch == 127) {
                throw new IOException(
                        "invalid control character in HTTP header value");
            }
        }
        return Map.entry(name, value);
    }

    private static int checkedHeaderBytes(int current, String line)
            throws IOException {
        long next = (long) current + line.length() + 2L;
        if (next > MAX_HEADER_BYTES) {
            throw new IOException(
                    "HTTP header byte budget exceeded");
        }
        return (int) next;
    }

    private static String readCrlfLine(
            InputStream input,
            int maxBytes,
            String what)
            throws IOException {
        ByteArrayOutputStream buffer =
                new ByteArrayOutputStream(Math.min(maxBytes, 256));
        boolean sawCr = false;
        while (true) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException(
                        "upstream closed while reading HTTP " + what);
            }
            if (sawCr) {
                if (value != '\n') {
                    throw new IOException(
                            "HTTP " + what + " used bare CR");
                }
                return buffer.toString(StandardCharsets.ISO_8859_1);
            }
            if (value == '\r') {
                sawCr = true;
                continue;
            }
            if (value == '\n') {
                throw new IOException(
                        "HTTP " + what + " used bare LF");
            }
            if (buffer.size() >= maxBytes) {
                throw new IOException(
                        "HTTP " + what + " exceeds line budget");
            }
            buffer.write(value);
        }
    }

    private static void expectCrlf(
            InputStream input,
            String what)
            throws IOException {
        int cr = input.read();
        int lf = input.read();
        if (cr != '\r' || lf != '\n') {
            throw new IOException(
                    "missing CRLF after HTTP " + what);
        }
    }

    private static void validateRequestHeaders(
            List<Map.Entry<String, String>> headers) {
        if (headers.size() > MAX_HEADER_FIELDS) {
            throw new IllegalArgumentException(
                    "outbound request header count exceeds "
                            + MAX_HEADER_FIELDS);
        }
        long bytes = 0;
        for (Map.Entry<String, String> field : headers) {
            Objects.requireNonNull(field, "request header");
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
        }
    }

    private static long deadlineAfter(Duration duration) {
        long now = System.nanoTime();
        long delta;
        try {
            delta = duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        if (Long.MAX_VALUE - now < delta) {
            return Long.MAX_VALUE;
        }
        return now + delta;
    }

    private static int timeoutMillis(
            Duration phase,
            long deadlineNanos,
            String description)
            throws SocketTimeoutException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException(
                    "pinned HTTP " + description + " expired");
        }
        long phaseNanos;
        try {
            phaseNanos = phase.toNanos();
        } catch (ArithmeticException overflow) {
            phaseNanos = Long.MAX_VALUE;
        }
        long nanos = Math.min(phaseNanos, remaining);
        long millis = Math.max(
                1L,
                (nanos + 999_999L) / 1_000_000L);
        return (int) Math.min(
                millis,
                Integer.MAX_VALUE);
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) return true;
        for (int i = 0; i < host.length(); i++) {
            char ch = host.charAt(i);
            if (ch != '.' && (ch < '0' || ch > '9')) {
                return false;
            }
        }
        return host.indexOf('.') >= 0;
    }

    int activeOrigins() {
        return budget.activeOrigins();
    }

    int activeSockets() {
        return liveSockets.size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        IOException failure = null;
        for (Response response : List.copyOf(liveResponses)) {
            try {
                response.close();
            } catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        // Catch sockets that are between connect() and response publication.
        // Their request owner will release the connection lease when the close
        // unblocks its current write/header/TLS operation.
        for (Socket socket : List.copyOf(liveSockets)) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw new IllegalStateException(
                    "pinned HTTP transport socket shutdown incomplete",
                    failure);
        }
    }
}
