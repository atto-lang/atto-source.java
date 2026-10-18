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

        AtomicInteger opened = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        var workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try {
                HttpResponseDraft response = switch (exchange.getRequestURI().getPath()) {
                    case "/text" -> HttpResponseDraft.text("hello from Oreslang", 201,
                            List.of(Map.entry("X-Mode", "fetch")));
                    case "/cookie" -> HttpResponseDraft.text("cookie", 200,
                            List.of(Map.entry("Set-Cookie", "a=1"), Map.entry("set-cookie", "b=2")));
                    case "/stream" -> HttpResponseDraft.streaming(() -> {
                        opened.incrementAndGet();
                        return new ByteArrayInputStream("alpha-beta".getBytes(StandardCharsets.UTF_8));
                    }, 200, List.of());
                    case "/empty" -> HttpResponseDraft.empty(204, List.of());
                    default -> HttpResponseDraft.text("head-only", 200, List.of());
                };
                response.writeTo(exchange, () -> false, ignored -> { });
            } finally {
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
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
        System.out.println("PASS: host HttpResponseDraft compilation and HTTP/1.1 wire contract");
    }
}
