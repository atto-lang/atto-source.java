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
    private final Semaphore capacity;
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
        if (maxInflight < 1 || maxInflight > 1024)
            throw new IllegalArgumentException("maxInflight must be 1..1024");
        capacity = new Semaphore(maxInflight);
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
        try { validateTarget(target, method); }
        catch (RuntimeException badTarget) { return CompletableFuture.failedFuture(badTarget); }
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
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(15))
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
                Response wrapped = new Response(result.statusCode(), filtered(result.headers()),
                        result.body(), call::retire);
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
        List<Call> retiring;
        synchronized (this) {
            if (closed) return;
            closed = true;
            retiring = List.copyOf(live);
        }
        // No synchronized monitor held while closing a blocking body stream.
        for (Call call : retiring) call.abort();
        // Prevent idle pool/socket resources from outliving the owning context.
        // shutdownNow is nonblocking; the context does not join I/O on a carrier.
        client.shutdownNow();
    }

    private static void validateTarget(URI target, String method) {
        Objects.requireNonNull(target, "target");
        if (!("GET".equals(method) || "HEAD".equals(method)))
            throw new IllegalArgumentException("loopback harness only supports GET or HEAD");
        if (!"http".equals(target.getScheme()) || !"127.0.0.1".equals(target.getHost())
                || target.getPort() < 1 || target.getPort() > 65535
                || target.getRawUserInfo() != null || target.getRawFragment() != null
                || target.getRawAuthority() == null
                || !target.getRawAuthority().equals("127.0.0.1:" + target.getPort()))
            throw new IllegalArgumentException("only canonical literal IPv4 loopback HTTP authorities allowed");
        String path = target.getRawPath();
        if (path == null || !path.startsWith("/") || path.contains("//")
                || path.contains("/./") || path.contains("/../")
                || path.endsWith("/.") || path.endsWith("/..")
                || path.contains("\\") || path.contains("%") || target.getRawQuery() != null)
            throw new IllegalArgumentException("unsafe loopback test request path");
    }

    /** Package-private to let exact-source tests inject adversarial header sets. */
    static List<Map.Entry<String, String>> filtered(HttpHeaders headers) {
        // Header admission must happen BEFORE stripping hop-by-hop fields.
        // Otherwise enormous Connection/Proxy-Connection fields can evade the
        // retained-header budget and consume CPU/memory in the trusted parser.
        int rawBytes = 0;
        int rawFields = 0;
        for (var field : headers.map().entrySet()) {
            String name = field.getKey();
            if (!HEADER_NAME.matcher(name).matches())
                throw new IllegalArgumentException("invalid upstream header name");
            for (String value : field.getValue()) {
                rawFields++;
                if (rawFields > 100 || name.length() + value.length() + 4 > 32768 - rawBytes)
                    throw new IllegalArgumentException("upstream header budget exceeded");
                rawBytes += name.length() + value.length() + 4;
                for (int i = 0; i < value.length(); i++) {
                    int ch = value.charAt(i);
                    if (ch < 32 || ch == 127 || ch > 255)
                        throw new IllegalArgumentException("invalid upstream header value");
                }
            }
        }
        Set<String> excluded = new HashSet<>(HOP);
        for (String value : headers.allValues("connection")) {
            for (String item : value.split(",", -1)) {
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
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean consumed = new AtomicBoolean();
        // A response can be closed while a downstream write is still blocked.
        // Capacity belongs to the physical stream until its worker returns.
        private final AtomicBoolean streamingActive = new AtomicBoolean();
        private final AtomicBoolean closeFinished = new AtomicBoolean();

        private Response(int status, List<Map.Entry<String, String>> headers,
                         InputStream body, Runnable release) {
            this.status = status;
            this.headers = headers;
            this.body = Objects.requireNonNull(body);
            this.release = release;
        }

        public int status() { return status; }
        public List<Map.Entry<String, String>> headers() { return headers; }

        /** Copy using fixed-size chunks on a transport I/O worker, never an actor carrier. */
        public long streamBody(OutputStream target, BooleanSupplier cancelled) throws IOException {
            Objects.requireNonNull(target);
            Objects.requireNonNull(cancelled);
            if (!consumed.compareAndSet(false, true))
                throw new IllegalStateException("body stream already consumed");
            streamingActive.set(true);
            try {
                byte[] chunk = new byte[8192];
                long total = 0;
                while (true) {
                    if (closed.get() || cancelled.getAsBoolean())
                        throw new IOException("response streaming cancelled or owner closed");
                    int n = body.read(chunk);
                    if (n < 0) return total;
                    if (closed.get() || cancelled.getAsBoolean())
                        throw new IOException("response streaming cancelled or owner closed");
                    target.write(chunk, 0, n);
                    total += n;
                }
            } finally {
                streamingActive.set(false);
                try { close(); }
                finally { if (closeFinished.get()) release.run(); }
            }
        }

        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            try { body.close(); }
            finally {
                closeFinished.set(true);
                if (!streamingActive.get()) release.run();
            }
        }
    }
}
