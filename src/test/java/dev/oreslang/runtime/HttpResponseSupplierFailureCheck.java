package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** A failing stream producer must not emit an HTTP success status. */
public final class HttpResponseSupplierFailureCheck {
    public static void main(String[] ignored) throws Exception {
        AtomicInteger attempted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                HttpResponseDraft response = HttpResponseDraft.streaming(() -> {
                    attempted.incrementAndGet();
                    throw new IllegalStateException("producer could not open");
                }, 200, List.of());
                response.writeTo(exchange, () -> false, ignoredBody -> {});
            } catch (IllegalStateException expected) {
                rejected.incrementAndGet();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            var head = client.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(3))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (head.statusCode() != 200 || head.body().length != 0 || attempted.get() != 0)
                throw new AssertionError("HEAD must not open stream supplier");
            try {
                var get = client.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(3)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                throw new AssertionError("failed producer was reported as HTTP " + get.statusCode());
            } catch (java.io.IOException expected) { /* closed before response headers */ }
            if (attempted.get() < 1 || rejected.get() != attempted.get())
                throw new AssertionError("every attempt must fail before headers, opened=" + attempted.get() + ", rejected=" + rejected.get());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
        System.out.println("PASS: failed stream supplier commits no HTTP success headers, HEAD stays lazy");
    }
}
