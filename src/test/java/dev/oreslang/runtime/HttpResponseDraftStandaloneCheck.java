package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** No Maven/Graal/JUnit required: javac + real local HTTP/1.1 sockets. */
public final class HttpResponseDraftStandaloneCheck {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void rejected(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("invalid response operation was accepted");
    }

    public static void main(String[] args) throws Exception {
        for (int status : List.of(204, 205, 304)) {
            rejected(() -> HttpResponseDraft.text("", status, List.of()));
            check(!HttpResponseDraft.empty(status, List.of()).hasBody(), "bodyless status");
        }
        for (int status : List.of(0, 100, 199, 600)) {
            rejected(() -> HttpResponseDraft.empty(status, List.of()));
        }
        for (String value : List.of(
                "injected" + ((char) 13) + ((char) 10) + "Header: yes",
                "nul" + ((char) 0) + "byte",
                "wide" + ((char) 256))) {
            rejected(() -> HttpResponseDraft.empty(200, List.of(Map.entry("X-Test", value))));
        }
        for (String name : List.of("Transfer-Encoding", "Content-Length", "Trailer", "Connection", "Bad Name")) {
            rejected(() -> HttpResponseDraft.text("x", 200, List.of(Map.entry(name, "y"))));
        }
        byte[] bytes = {1, 2, 3};
        HttpResponseDraft copied = HttpResponseDraft.binary(bytes, 200, List.of());
        bytes[0] = 99;
        copied.claim();
        check(copied.openBody().read() == 1, "body must be defensively copied");
        rejected(copied::openBody);
        rejected(copied::claim);
        rejected(HttpResponseDraft::withTrailers);

        HttpResponseDraft disputed = HttpResponseDraft.text("one owner");
        AtomicInteger claimsWon = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(12)) {
            var ready = new java.util.concurrent.CountDownLatch(12);
            var release = new java.util.concurrent.CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    release.await();
                    disputed.claim();
                    claimsWon.incrementAndGet();
                } catch (IllegalStateException expected) {
                    // Another carrier won the affine claim.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
            check(ready.await(3, java.util.concurrent.TimeUnit.SECONDS), "carrier start barrier");
            release.countDown();
            for (var future : futures) future.get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
        check(claimsWon.get() == 1, "exactly one concurrent response handoff");

        AtomicInteger opened = new AtomicInteger();
        AtomicInteger cancelledStreamReads = new AtomicInteger();
        AtomicInteger cancelledStreamCloses = new AtomicInteger();
        var responseCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        var cancelledCleanupDone = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger aggregateRejects = new AtomicInteger();
        AtomicInteger preflightRetries = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        var workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try {
                String route = exchange.getRequestURI().getPath();
                if (route.equals("/aggregate-count")) {
                    for (int i = 0; i < 95; i++)
                        exchange.getResponseHeaders().add("X-Existing-" + i, "a");
                }
                if (route.equals("/aggregate-bytes"))
                    exchange.getResponseHeaders().add("X-Existing", "e".repeat(18000));
                HttpResponseDraft response = switch (route) {
                    case "/text" -> HttpResponseDraft.text("hello from Oreslang", 201,
                            List.of(Map.entry("X-Mode", "fetch")));
                    case "/cookie" -> HttpResponseDraft.text("cookie", 200,
                            List.of(Map.entry("Set-Cookie", "a=1"), Map.entry("set-cookie", "b=2")));
                    case "/stream" -> HttpResponseDraft.streaming(() -> {
                        opened.incrementAndGet();
                        return new ByteArrayInputStream("alpha-beta".getBytes(StandardCharsets.UTF_8));
                    }, 200, List.of());
                    case "/preflight-retry" -> HttpResponseDraft.text("after correction", 200, List.of(Map.entry("X-Retry", "yes")));
                    case "/aggregate-count" -> HttpResponseDraft.text("oversize", 200,
                            java.util.stream.IntStream.range(0, 10)
                                    .mapToObj(i -> Map.entry("X-New-" + i, "b")).toList());
                    case "/aggregate-bytes" -> HttpResponseDraft.text("oversize", 200,
                            List.of(Map.entry("X-New", "n".repeat(16000))));
                    case "/empty" -> HttpResponseDraft.empty(204, List.of());
                    case "/cancel" -> HttpResponseDraft.streaming(() -> {
                        opened.incrementAndGet();
                        return new ByteArrayInputStream("never".getBytes(StandardCharsets.UTF_8));
                    }, 200, List.of());
                    case "/cancel-after-open" -> HttpResponseDraft.streaming(() -> new ByteArrayInputStream(
                            "should not be read".getBytes(StandardCharsets.UTF_8)) {
                        @Override public synchronized int read(byte[] buf, int off, int len) {
                            cancelledStreamReads.incrementAndGet();
                            return super.read(buf, off, len);
                        }
                        @Override public void close() throws java.io.IOException {
                            cancelledStreamCloses.incrementAndGet();
                            super.close();
                        }
                    }, 200, List.of());
                    default -> HttpResponseDraft.text("head-only", 200, List.of());
                };
                if (route.equals("/preflight-retry")) {
                    for (int i = 0; i < 101; i++)
                        exchange.getResponseHeaders().add("X-Budget-" + i, "a");
                    rejected(() -> response.preflight(exchange));
                    exchange.getResponseHeaders().clear();
                    response.preflight(exchange);
                    preflightRetries.incrementAndGet();
                }
                response.writeTo(exchange,
                        () -> exchange.getRequestURI().getPath().equals("/cancel") || responseCancelled.get(),
                        input -> {
                            if (exchange.getRequestURI().getPath().equals("/cancel-after-open") && input != null)
                                responseCancelled.set(true);
                        });
            } catch (IllegalArgumentException invalid) {
                if (exchange.getRequestURI().getPath().startsWith("/aggregate"))
                    aggregateRejects.incrementAndGet();
                else throw invalid;
            } finally {
                if (exchange.getRequestURI().getPath().equals("/cancel-after-open"))
                    cancelledCleanupDone.countDown();
                exchange.close();
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpResponse<String> text = client.send(HttpRequest.newBuilder(base.resolve("/text")).build(),
                    HttpResponse.BodyHandlers.ofString());
            check(text.statusCode() == 201 && text.body().equals("hello from Oreslang"), "text response");
            check(text.headers().firstValue("Content-Type").orElse("").contains("text/plain"), "content-type");
            HttpResponse<String> cookie = client.send(HttpRequest.newBuilder(base.resolve("/cookie")).build(),
                    HttpResponse.BodyHandlers.ofString());
            check(cookie.headers().allValues("Set-Cookie").size() == 2, "Set-Cookie must not be comma folded");
            HttpResponse<byte[]> head = client.send(HttpRequest.newBuilder(base.resolve("/head"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            check(head.statusCode() == 200 && head.body().length == 0, "HEAD body suppression");
            check(opened.get() == 0, "stream must open lazily");
            HttpResponse<byte[]> headStream = client.send(HttpRequest.newBuilder(base.resolve("/stream"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            check(headStream.statusCode() == 200 && headStream.body().length == 0, "HEAD streaming suppression");
            check(opened.get() == 0, "HEAD must not open response stream");
            HttpResponse<String> stream = client.send(HttpRequest.newBuilder(base.resolve("/stream")).build(),
                    HttpResponse.BodyHandlers.ofString());
            check(stream.statusCode() == 200 && stream.body().equals("alpha-beta"), "chunked stream");
            check(opened.get() == 1, "stream must open exactly once");
            HttpResponse<byte[]> empty = client.send(HttpRequest.newBuilder(base.resolve("/empty")).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            check(empty.statusCode() == 204 && empty.body().length == 0, "bodyless response");
            try {
                client.send(HttpRequest.newBuilder(base.resolve("/cancel"))
                                .timeout(java.time.Duration.ofSeconds(3)).build(),
                        HttpResponse.BodyHandlers.ofString());
                throw new AssertionError("cancelled response sent HTTP headers");
            } catch (java.io.IOException expected) {
                // Response must not commit headers after cancellation.
            }
            check(opened.get() == 1, "cancelled stream supplier must not run");
            try {
                client.send(HttpRequest.newBuilder(base.resolve("/cancel-after-open"))
                                .timeout(java.time.Duration.ofSeconds(3)).build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException ignored) {
                // The headers might have committed, but body abort is allowed.
            }
            check(cancelledCleanupDone.await(3, java.util.concurrent.TimeUnit.SECONDS), "cancel cleanup ran");
            check(cancelledStreamReads.get() == 0, "do not read source after cancellation");
            check(cancelledStreamCloses.get() == 1, "cancelled source closed exactly once");
            responseCancelled.set(false);
            for (String route : List.of("/aggregate-count", "/aggregate-bytes")) {
                try {
                    client.send(HttpRequest.newBuilder(base.resolve(route))
                                    .timeout(java.time.Duration.ofSeconds(3)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    throw new AssertionError("combined header budget bypass for " + route);
                } catch (java.io.IOException expected) {
                    // Failed closed before HTTP headers were emitted.
                }
            }
            for (int spin = 0; spin < 100 && aggregateRejects.get() < 2; spin++)
                Thread.sleep(10);
            check(aggregateRejects.get() >= 2,
                    "combined header limits enforced, observed=" + aggregateRejects.get());
            HttpResponse<String> retried = client.send(
                    HttpRequest.newBuilder(base.resolve("/preflight-retry")).build(),
                    HttpResponse.BodyHandlers.ofString());
            check(retried.statusCode() == 200 && retried.body().equals("after correction"),
                    "rejected preflight must not consume the response");
            check(preflightRetries.get() == 1, "preflight completed once after correction");
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
        System.out.println("PASS: host HttpResponseDraft compilation and HTTP/1.1 wire contract");
    }
}
