package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent no-Maven live-socket preflight/commitment regression. */
public final class HttpResponsePreflightCheck {
    public static void main(String[] ignored) throws Exception {
        AtomicInteger failedPreflights = new AtomicInteger();
        AtomicInteger successfulRetries = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        var workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try {
                HttpResponseDraft response = HttpResponseDraft.text("after correction", 200,
                        List.of(Map.entry("X-Retry", "yes")));
                for (int i = 0; i < 101; i++)
                    exchange.getResponseHeaders().add("X-Budget-" + i, "a");
                try {
                    response.preflight(exchange);
                    throw new AssertionError("oversize headers accepted");
                } catch (IllegalArgumentException expected) {
                    failedPreflights.incrementAndGet();
                }
                exchange.getResponseHeaders().clear();
                response.preflight(exchange);
                response.writeTo(exchange, () -> false, input -> {});
                try {
                    response.writeClaimedTo(exchange, () -> false, input -> {});
                    throw new AssertionError("second wire emission accepted");
                } catch (IllegalStateException expected) {
                    // One emission even though the response was already claimed.
                }
                successfulRetries.incrementAndGet();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            HttpResponse<String> result = client.send(HttpRequest.newBuilder(uri).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (result.statusCode() != 200 || !result.body().equals("after correction"))
                throw new AssertionError("preflight retry did not emit expected response");
            if (!result.headers().firstValue("X-Retry").orElse("").equals("yes"))
                throw new AssertionError("response headers not preserved");
            if (failedPreflights.get() != 1 || successfulRetries.get() != 1)
                throw new AssertionError("preflight did not preserve unclaimed envelope");
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
        System.out.println("PASS: invalid preflight leaves response unclaimed and retry succeeds");
    }
}
