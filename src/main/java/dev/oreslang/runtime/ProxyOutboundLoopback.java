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
public final class ProxyOutboundLoopback {
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> HOP = Set.of("connection", "keep-alive",
            "proxy-connection", "transfer-encoding", "te", "trailer", "upgrade",
            "content-length", "proxy-authenticate", "proxy-authorization",
            "host", "http2-settings");
    private final Semaphore capacity;
    private final HttpClient client;

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
        if (!capacity.tryAcquire())
            return CompletableFuture.failedFuture(new RejectedExecutionException("outbound capacity exhausted"));
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) capacity.release(); };
        CompletableFuture<Response> answer = new CompletableFuture<>();
        CompletableFuture<HttpResponse<InputStream>> upstream;
        try {
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(15))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
            upstream = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (RuntimeException failure) {
            release.run();
            return CompletableFuture.failedFuture(failure);
        }
        upstream.whenComplete((result, failure) -> {
            if (failure != null) {
                release.run();
                answer.completeExceptionally(failure);
                return;
            }
            try {
                Response wrapped = new Response(result.statusCode(), filtered(result.headers()),
                        result.body(), release);
                if (!answer.complete(wrapped)) wrapped.close();
            } catch (Throwable error) {
                try { result.body().close(); } catch (IOException suppressed) { error.addSuppressed(suppressed); }
                release.run();
                answer.completeExceptionally(error);
            }
        });
        answer.whenComplete((value, error) -> {
            if (answer.isCancelled()) upstream.cancel(true);
        });
        return answer;
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
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains("\\") || path.contains("%"))
            throw new IllegalArgumentException("unsafe loopback test request path");
    }

    private static List<Map.Entry<String, String>> filtered(HttpHeaders headers) {
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
        int bytes = 0;
        for (var field : headers.map().entrySet()) {
            String name = field.getKey();
            if (!HEADER_NAME.matcher(name).matches())
                throw new IllegalArgumentException("invalid upstream header name");
            if (excluded.contains(name.toLowerCase(Locale.ROOT))) continue;
            for (String value : field.getValue()) {
                for (int i = 0; i < value.length(); i++) {
                    int ch = value.charAt(i);
                    if (ch < 32 || ch == 127 || ch > 255)
                        throw new IllegalArgumentException("invalid upstream header value");
                }
                bytes += name.length() + value.length() + 4;
                if (bytes > 32768 || result.size() >= 100)
                    throw new IllegalArgumentException("upstream header budget exceeded");
                result.add(Map.entry(name, value));
            }
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
            try (this) {
                byte[] chunk = new byte[8192];
                long total = 0;
                while (true) {
                    if (cancelled.getAsBoolean()) throw new IOException("response streaming cancelled");
                    int n = body.read(chunk);
                    if (n < 0) return total;
                    if (cancelled.getAsBoolean()) throw new IOException("response streaming cancelled");
                    target.write(chunk, 0, n);
                    total += n;
                }
            }
        }

        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            try { body.close(); }
            finally { release.run(); }
        }
    }
}
