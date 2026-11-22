package dev.oreslang.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Trusted host-side, loopback-only outbound HTTP proof. No guest ABI, open
 * proxy, CONNECT, DNS names, redirects, or actor-carrier blocking is allowed.
 * Network authority and worker ownership must be added before production use.
 */
public final class ProxyOutboundLoopback implements AutoCloseable {
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> HOP = Set.of("connection", "keep-alive",
            "proxy-connection", "transfer-encoding", "te", "trailer", "upgrade",
            "content-length", "proxy-authenticate", "proxy-authorization",
            "host", "http2-settings");
    private static final Set<String> REQUEST_CONTROL = Set.of(
            "connection", "keep-alive", "proxy-connection", "transfer-encoding",
            "te", "trailer", "upgrade", "content-length", "host", "expect",
            "proxy-authenticate", "proxy-authorization", "http2-settings");
    private static final long DEFAULT_MAX_RESPONSE_BYTES = 64L * 1024 * 1024;
    private final Semaphore capacity;
    private final long maxResponseBytes;
    private final HttpClient client;
    // The trusted runtime/context owns this transport. Never publish a response
    // after scope closure; close all delivered but unretired body handles.
    private final Set<Call> live = new HashSet<>();
    private boolean closed;

    private final class Call {
        final CompletableFuture<Response> answer = new CompletableFuture<>();
        final AtomicBoolean released = new AtomicBoolean();
        volatile CompletableFuture<HttpResponse<InputStream>> upstream;
        volatile Response response;

        void retire() {
            if (!released.compareAndSet(false, true)) return;
            synchronized (ProxyOutboundLoopback.this) { live.remove(this); }
            capacity.release();
        }

        void abort() {
            answer.cancel(true);
            var remote = upstream;
            if (remote != null) remote.cancel(true);
            var delivered = response;
            if (delivered != null) {
                try { delivered.close(); } catch (IOException ignored) { /* shutdown */ }
            }
        }
    }

    public ProxyOutboundLoopback(int maxInflight) {
        this(maxInflight, DEFAULT_MAX_RESPONSE_BYTES);
    }

    ProxyOutboundLoopback(int maxInflight, long maxResponseBytes) {
        if (maxInflight < 1 || maxInflight > 1024)
            throw new IllegalArgumentException("maxInflight must be 1..1024");
        if (maxResponseBytes < 0 || maxResponseBytes > (1L << 30))
            throw new IllegalArgumentException("maxResponseBytes must be 0..1GiB");
        capacity = new Semaphore(maxInflight);
        this.maxResponseBytes = maxResponseBytes;
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI ignored) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI ignored, SocketAddress address,
                                                        IOException failure) { }
                })
                .build();
    }

    /** A nonblocking start; consumption of Response must run on an I/O worker. */
    public CompletableFuture<Response> request(URI target, String method) {
        return request(target, method, List.of());
    }

    CompletableFuture<Response> request(
            URI target,
            String method,
            List<Map.Entry<String, String>> requestHeaders) {
        try {
            validateTarget(target, method);
            validateRequestHeaders(requestHeaders);
        } catch (RuntimeException badTarget) {
            return CompletableFuture.failedFuture(badTarget);
        }
        Call call;
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(
                    new RejectedExecutionException("outbound scope closed"));
            if (!capacity.tryAcquire()) return CompletableFuture.failedFuture(
                    new RejectedExecutionException("outbound capacity exhausted"));
            call = new Call();
            live.add(call);
        }
        CompletableFuture<HttpResponse<InputStream>> upstream;
        try {
            // The scope may close between admission and launch; cancellation
            // must still join any in-flight response and close the body.
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(15));
            for (Map.Entry<String, String> field : requestHeaders) {
                requestBuilder.header(field.getKey(), field.getValue());
            }
            HttpRequest request = requestBuilder
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
            if (isClosed()) {
                call.abort();
                call.retire();
                return call.answer;
            }
            upstream = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            call.upstream = upstream;
        } catch (RuntimeException failure) {
            call.retire();
            call.answer.completeExceptionally(failure);
            return call.answer;
        }
        upstream.whenComplete((result, failure) -> {
            if (failure != null) {
                call.retire();
                call.answer.completeExceptionally(failure);
                return;
            }
            try {
                validateFinalStatus(result.statusCode());
                Response wrapped = new Response(result.statusCode(), filtered(result.headers()),
                        result.body(), maxResponseBytes, call::retire);
                call.response = wrapped;
                if (isClosed() || !call.answer.complete(wrapped)) wrapped.close();
            } catch (Throwable error) {
                try { result.body().close(); } catch (IOException suppressed) { error.addSuppressed(suppressed); }
                call.retire();
                call.answer.completeExceptionally(error);
            }
        });
        call.answer.whenComplete((value, failure) -> {
            if (call.answer.isCancelled()) upstream.cancel(true);
        });
        if (isClosed()) call.abort();
        return call.answer;
    }

    private synchronized boolean isClosed() { return closed; }

    /** Package-private lifecycle inspection for deterministic host tests. */
    synchronized int inFlight() { return live.size(); }

    @Override public void close() {
        List<Call> retiring = null;
        synchronized (this) {
            if (!closed) {
                closed = true;
                retiring = List.copyOf(live);
            }
        }
        if (retiring != null) {
            // No synchronized monitor held while closing a blocking body stream.
            for (Call call : retiring) call.abort();
            // Request immediate shutdown. close() itself remains nonjoining so
            // host/control callers do not accidentally park a carrier.
            client.shutdownNow();
        }
    }

    /**
     * Trusted native-worker retirement barrier. The actor-integrated bridge
     * must use this instead of close(): an AsyncNativeIo operation is not
     * physically retired until the JDK HttpClient has terminated all request
     * operations and transport resources.
     */
    void closeAndJoin() {
        close();
        boolean interrupted = false;
        while (!client.isTerminated()) {
            try {
                if (client.awaitTermination(Duration.ofMillis(50))) break;
            } catch (InterruptedException wakeup) {
                interrupted = true;
                // Interruption is a revocation hint, not a retirement receipt.
                // Keep the native worker alive until the client itself reports
                // termination; otherwise actor DONE could race live transport.
                client.shutdownNow();
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** Package-private physical-termination receipt for deterministic tests. */
    boolean transportTerminated() { return client.isTerminated(); }

    static void validateTarget(URI target, String method) {
        Objects.requireNonNull(target, "target");
        if (!("GET".equals(method) || "HEAD".equals(method)))
            throw new IllegalArgumentException("loopback harness only supports GET or HEAD");

        if (!"http".equals(target.getScheme()) || !"127.0.0.1".equals(target.getHost())
                || target.getPort() < 1 || target.getPort() > 65535
                || target.getRawUserInfo() != null || target.getRawFragment() != null
                || target.getRawAuthority() == null
                || !target.getRawAuthority().equals("127.0.0.1:" + target.getPort()))
            throw new IllegalArgumentException("only canonical literal IPv4 loopback HTTP authorities allowed");

        // The current proof transport uses a literal address, so its physical
        // HttpClient connection cannot re-resolve behind this pin. Keep this
        // as the first consumer of the shared egress policy without widening
        // the loopback-only ABI. Hostname egress remains disabled until the
        // production transport can connect to a pinned InetAddress directly.
        OutboundEgressPolicy.resolveForConnection(
                target,
                OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                OutboundEgressPolicy.Policy.loopbackOnly(),
                host -> {
                    throw new AssertionError(
                            "literal loopback target unexpectedly reached DNS: " + host);
                });

        String path = target.getRawPath();
        if (path == null || !path.startsWith("/") || path.contains("//")
                || path.contains("/./") || path.contains("/../")
                || path.endsWith("/.") || path.endsWith("/..")
                || path.contains("\\") || path.contains("%") || target.getRawQuery() != null
                || path.chars().anyMatch(ch -> ch <= 0x20 || ch >= 0x7f))
            throw new IllegalArgumentException("unsafe loopback test request path");
    }

    static void validateRequestHeader(String name, String value) {
        Objects.requireNonNull(name, "header name");
        Objects.requireNonNull(value, "header value");
        if (!HEADER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid outbound request header name");
        }
        if (REQUEST_CONTROL.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "outbound framing/control header is transport-owned: " + name);
        }
        for (int i = 0; i < value.length(); i++) {
            int ch = value.charAt(i);
            if (ch < 32 || ch == 127 || ch > 255) {
                throw new IllegalArgumentException(
                        "invalid outbound request header value");
            }
        }
    }

    private static void validateRequestHeaders(
            List<Map.Entry<String, String>> headers) {
        Objects.requireNonNull(headers, "request headers");
        if (headers.size() > 100) {
            throw new IllegalArgumentException(
                    "outbound request header count exceeds 100");
        }
        long bytes = 0;
        for (Map.Entry<String, String> field : headers) {
            Objects.requireNonNull(field, "request header");
            validateRequestHeader(field.getKey(), field.getValue());
            long fieldBytes = (long) field.getKey().length()
                    + field.getValue().length() + 4L;
            if (fieldBytes > 32_768L - bytes) {
                throw new IllegalArgumentException(
                        "outbound request header byte budget exceeded");
            }
            bytes += fieldBytes;
        }
    }

    static void validateFinalStatus(int status) {
        if (status < 200 || status > 599)
            throw new IllegalArgumentException("unsupported non-final/invalid upstream status");
    }

    /** Package-private to let exact-source tests inject adversarial header sets. */
    static List<Map.Entry<String, String>> filtered(HttpHeaders headers) {
        // Header admission must happen BEFORE stripping hop-by-hop fields.
        // Otherwise enormous Connection/Proxy-Connection fields can evade the
        // retained-header budget and consume CPU/memory in the trusted parser.
        long rawBytes = 0;
        int rawFields = 0;
        for (var field : headers.map().entrySet()) {
            String name = field.getKey();
            if (!HEADER_NAME.matcher(name).matches())
                throw new IllegalArgumentException("invalid upstream header name");
            for (String value : field.getValue()) {
                rawFields++;
                long fieldBytes = (long) name.length() + value.length() + 4L;
                if (rawFields > 100 || fieldBytes > 32768L - rawBytes)
                    throw new IllegalArgumentException("upstream header budget exceeded");
                rawBytes += fieldBytes;
                for (int i = 0; i < value.length(); i++) {
                    int ch = value.charAt(i);
                    if (ch < 32 || ch == 127 || ch > 255)
                        throw new IllegalArgumentException("invalid upstream header value");
                }
            }
        }
        Set<String> excluded = new HashSet<>(HOP);
        int connectionOptions = 0;
        for (String value : headers.allValues("connection")) {
            for (String item : value.split(",", -1)) {
                // One field can name many options, so the field budget alone misses this.
                if (++connectionOptions > 100)
                    throw new IllegalArgumentException("upstream Connection option budget exceeded");
                String token = item.trim();
                if (!HEADER_NAME.matcher(token).matches())
                    throw new IllegalArgumentException("invalid upstream Connection token");
                excluded.add(token.toLowerCase(Locale.ROOT));
            }
        }
        ArrayList<Map.Entry<String, String>> result = new ArrayList<>();
        for (var field : headers.map().entrySet()) {
            if (excluded.contains(field.getKey().toLowerCase(Locale.ROOT))) continue;
            for (String value : field.getValue()) result.add(Map.entry(field.getKey(), value));
        }
        return List.copyOf(result);
    }

    public static final class Response implements AutoCloseable {
        private final int status;
        private final List<Map.Entry<String, String>> headers;
        private final InputStream body;
        private final long maxBodyBytes;
        private final Runnable release;
        // One response-state monitor linearizes stream start against close.
        // No monitor is held across a socket read/write or body.close().
        private boolean closed;
        private boolean consumed;
        private boolean streamingActive;
        private boolean closeFinished;
        private boolean retired;

        private Response(int status, List<Map.Entry<String, String>> headers,
                         InputStream body, long maxBodyBytes, Runnable release) {
            this.status = status;
            this.headers = headers;
            this.body = Objects.requireNonNull(body);
            this.maxBodyBytes = maxBodyBytes;
            this.release = release;
        }

        public int status() { return status; }
        public List<Map.Entry<String, String>> headers() { return headers; }

        /** Copy using fixed-size chunks on a transport I/O worker, never an actor carrier. */
        public long streamBody(OutputStream target, BooleanSupplier cancelled) throws IOException {
            Objects.requireNonNull(target);
            Objects.requireNonNull(cancelled);
            synchronized (this) {
                if (closed) throw new IOException("response owner already closed");
                if (consumed) throw new IllegalStateException("body stream already consumed");
                consumed = true;
                streamingActive = true;
            }
            try {
                byte[] chunk = new byte[8192];
                long total = 0;
                while (true) {
                    if (isClosed() || cancelled.getAsBoolean())
                        throw new IOException("response streaming cancelled or owner closed");
                    int n = body.read(chunk);
                    if (n < 0) return total;
                    if (isClosed() || cancelled.getAsBoolean())
                        throw new IOException("response streaming cancelled or owner closed");
                    if (n > maxBodyBytes - total)
                        throw new IOException("response body byte budget exceeded");
                    target.write(chunk, 0, n);
                    total += n;
                }
            } finally {
                try { close(); }
                finally {
                    boolean releaseNow;
                    synchronized (this) {
                        streamingActive = false;
                        releaseNow = closeFinished && !retired;
                        if (releaseNow) retired = true;
                    }
                    if (releaseNow) release.run();
                }
            }
        }

        private synchronized boolean isClosed() { return closed; }

        @Override public void close() throws IOException {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            try { body.close(); }
            finally {
                boolean releaseNow;
                synchronized (this) {
                    closeFinished = true;
                    releaseNow = !streamingActive && !retired;
                    if (releaseNow) retired = true;
                }
                if (releaseNow) release.run();
            }
        }
    }
}
