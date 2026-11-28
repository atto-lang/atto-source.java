package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class HttpResponseDraftWireTest {
    @Test @Timeout(15)
    void failingBodySupplierNeverEmitsSuccessHeadersAndHeadIsLazy() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try {
                HttpResponseDraft response = HttpResponseDraft.streaming(() -> {
                    opened.incrementAndGet();
                    throw new IllegalStateException("stream supplier failed");
                }, 200, List.of());
                response.writeTo(exchange, () -> false, ignored -> { });
            } catch (IllegalStateException expected) {
                rejected.incrementAndGet();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()) {
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            HttpResponse<byte[]> head = client.send(
                    HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(4))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, head.statusCode());
            assertEquals(0, head.body().length);
            assertEquals(0, opened.get(), "HEAD must not evaluate producer");
            assertThrows(java.io.IOException.class, () -> client.send(
                    HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(4)).build(),
                    HttpResponse.BodyHandlers.ofByteArray()));
            assertTrue(opened.get() >= 1, "GET must try to open supplier");
            assertEquals(opened.get(), rejected.get(),
                    "every failing producer must reject before headers");
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
    }

    @Test @Timeout(20)
    void boundedSupplierAdmissionPreservesHealthyHttpRequests() throws Exception {
        HttpResponseBodyOpenWireCheck.main(new String[0]);
    }

    @Test @Timeout(20)
    void actualHttp11ResponseFramesAndLazyStream() throws Exception {
        AtomicInteger streamOpens = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try {
                HttpResponseDraft response = switch (exchange.getRequestURI().getPath()) {
                    case "/text" -> HttpResponseDraft.text("hello from Oreslang", 201,
                            List.of(Map.entry("X-Actor", "unary")));
                    case "/cookie" -> HttpResponseDraft.text("cookie", 200,
                            List.of(Map.entry("Set-Cookie", "a=1; HttpOnly"),
                                    Map.entry("set-cookie", "b=2; SameSite=Lax")));
                    case "/stream" -> HttpResponseDraft.streaming(() -> {
                        streamOpens.incrementAndGet();
                        return new ByteArrayInputStream("alpha-beta-gamma".getBytes(StandardCharsets.UTF_8));
                    }, 200, List.of(Map.entry("Content-Type", "application/octet-stream")));
                    case "/empty" -> HttpResponseDraft.empty(204, List.of(Map.entry("X-Empty", "true")));
                    default -> HttpResponseDraft.text("head-only", 200, List.of());
                };
                response.writeTo(exchange, () -> false, ignored -> { });
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()) {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpResponse<String> text = client.send(HttpRequest.newBuilder(base.resolve("/text")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, text.statusCode());
            assertEquals("hello from Oreslang", text.body());
            assertEquals("text/plain; charset=utf-8", text.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("unary", text.headers().firstValue("X-Actor").orElseThrow());
            HttpResponse<String> cookies = client.send(HttpRequest.newBuilder(base.resolve("/cookie")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(2, cookies.headers().allValues("Set-Cookie").size());
            HttpResponse<byte[]> head = client.send(HttpRequest.newBuilder(base.resolve("/head"))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, head.statusCode());
            assertEquals(0, head.body().length);
            assertEquals(0, streamOpens.get());
            HttpResponse<byte[]> streamed = client.send(HttpRequest.newBuilder(base.resolve("/stream")).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, streamed.statusCode());
            assertArrayEquals("alpha-beta-gamma".getBytes(StandardCharsets.UTF_8), streamed.body());
            assertEquals(1, streamOpens.get());
            HttpResponse<byte[]> empty = client.send(HttpRequest.newBuilder(base.resolve("/empty")).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(204, empty.statusCode());
            assertEquals(0, empty.body().length);
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
