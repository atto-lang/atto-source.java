package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.net.Socket;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class NioHttpTransportTest {
    @Test void slowBodiesDoNotOccupyIoWorkersAndDisconnectFailsPendingReads() throws Exception {
        CountDownLatch registered = new CountDownLatch(20);
        CountDownLatch disconnected = new CountDownLatch(20);
        NioHttpTransport server = new NioHttpTransport("127.0.0.1", 0, 32, 64, 30000, exchange -> {
            if (exchange.getRequestURI().getPath().equals("/slow")) {
                exchange.read(64, true).whenComplete((bytes, failure) -> {
                    if (failure != null) disconnected.countDown();
                });
                registered.countDown();
            } else exchange.respond(200, "ok".getBytes(StandardCharsets.UTF_8), false);
        });
        var sockets = new ArrayList<Socket>();
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (int i = 0; i < 20; i++) {
                Socket socket = new Socket("127.0.0.1", server.port()); sockets.add(socket);
                socket.getOutputStream().write("POST /slow HTTP/1.1\r\nHost: localhost\r\nContent-Length: 64\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            }
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            assertEquals("ok", client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health"))
                    .timeout(java.time.Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString()).body());
            for (Socket socket : sockets) socket.close();
            assertTrue(disconnected.await(5, TimeUnit.SECONDS));
        } finally {
            for (Socket socket : sockets) socket.close();
            server.stop(0).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void chunksKeepAliveHeadAndOversizedBodiesPreserveFraming() throws Exception {
        NioHttpTransport server = new NioHttpTransport("127.0.0.1", 0, 32, 32, 30000, exchange -> {
            if (exchange.getRequestMethod().equals("POST")) {
                exchange.read(2, false).thenCompose(first -> exchange.read(32, true).thenCompose(rest -> {
                    assertArrayEquals(new byte[]{'a','b'}, first);
                    return exchange.respond(201, rest, false);
                }));
            } else if (exchange.getRequestURI().getPath().equals("/stream")) {
                exchange.start(200, false).thenCompose(v -> exchange.write(new byte[]{'a'}))
                        .thenCompose(v -> exchange.write(new byte[]{'b'})).thenCompose(v -> exchange.finish());
            } else exchange.respond(200, new byte[]{'o','k'}, exchange.getRequestMethod().equals("HEAD"));
        });
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI uri = URI.create("http://127.0.0.1:" + server.port() + "/");
            var post = client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("abcd")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, post.statusCode()); assertEquals("cd", post.body());
            assertEquals("ok", client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body());
            var head = client.send(HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("", head.body()); assertEquals("2", head.headers().firstValue("Content-Length").orElseThrow());
            assertEquals("ab", client.send(HttpRequest.newBuilder(uri.resolve("/stream")).build(), HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(413, client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("x".repeat(33))).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        } finally { server.stop(0).toCompletableFuture().get(5, TimeUnit.SECONDS); }
    }
}
