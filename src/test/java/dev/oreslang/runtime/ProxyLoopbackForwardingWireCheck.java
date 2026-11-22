package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executors;

/** Real sockets: client -> host-only loopback adapter -> upstream -> client. */
public final class ProxyLoopbackForwardingWireCheck {
    public static void main(String[] ignored) throws Exception {
        var ioWorkers = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        HttpServer listener = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        upstream.setExecutor(ioWorkers);
        listener.setExecutor(ioWorkers);
        upstream.createContext("/created", exchange -> {
            try {
                byte[] bytes = "through-proxy".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("X-Upstream", "pass");
                exchange.getResponseHeaders().add("Set-Cookie", "one=1");
                exchange.getResponseHeaders().add("Set-Cookie", "two=2");
                exchange.getResponseHeaders().add("Connection", "X-Discard");
                exchange.getResponseHeaders().add("X-Discard", "secret");
                exchange.sendResponseHeaders(201, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        upstream.createContext("/missing", exchange -> {
            try {
                byte[] bytes = "gone".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        upstream.createContext("/blob", exchange -> {
            try {
                byte[] chunk = new byte[8192];
                Arrays.fill(chunk, (byte) 79);
                exchange.sendResponseHeaders(200, 0);
                for (int i = 0; i < 256; i++) exchange.getResponseBody().write(chunk);
            } finally { exchange.close(); }
        });
        upstream.createContext("/redirect", exchange -> {
            try {
                exchange.getResponseHeaders().set("Location", "http://example.org/");
                exchange.sendResponseHeaders(302, -1);
            } finally { exchange.close(); }
        });
        ProxyOutboundLoopback transport = new ProxyOutboundLoopback(2);
        listener.createContext("/", exchange -> {
            try {
                // Test-only authority is assembled from an existing trusted loopback
                // socket, never an untrusted Host/request-target supplied authority.
                URI target = URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()
                        + exchange.getRequestURI().getRawPath());
                try (var response = transport.request(target, exchange.getRequestMethod()).join()) {
                    for (var field : response.headers())
                        exchange.getResponseHeaders().add(field.getKey(), field.getValue());
                    if (response.status() == 204 || response.status() == 304) {
                        exchange.sendResponseHeaders(response.status(), -1);
                    } else {
                        exchange.sendResponseHeaders(response.status(), 0);
                        response.streamBody(exchange.getResponseBody(), () -> false);
                    }
                }
            } catch (RuntimeException | IOException rejected) {
                // Do not send 200 after an upstream failure; a committed stream
                // simply aborts. This fixture never makes remote connections.
                if (exchange.getResponseCode() == -1) exchange.sendResponseHeaders(502, -1);
            } finally { exchange.close(); }
        });
        upstream.start();
        listener.start();
        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            String base = "http://127.0.0.1:" + listener.getAddress().getPort();
            var created = client.send(HttpRequest.newBuilder(URI.create(base + "/created")).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (created.statusCode()!=201 || !created.body().equals("through-proxy") ||
                    !created.headers().firstValue("X-Upstream").orElse("").equals("pass") ||
                    created.headers().allValues("Set-Cookie").size()!=2 ||
                    !created.headers().allValues("X-Discard").isEmpty())
                throw new AssertionError("upstream 201/body/headers not preserved end-to-end");
            var missing = client.send(HttpRequest.newBuilder(URI.create(base + "/missing")).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (missing.statusCode()!=404 || !missing.body().equals("gone"))
                throw new AssertionError("upstream 404 not forwarded");
            var big = client.send(HttpRequest.newBuilder(URI.create(base + "/blob")).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (big.statusCode()!=200 || big.body().length!=8192*256)
                throw new AssertionError("upstream streaming response truncated");
            var redirect = client.send(HttpRequest.newBuilder(URI.create(base + "/redirect")).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (redirect.statusCode()!=302) throw new AssertionError("redirect followed by proxy");
            System.out.println("PASS host-only loopback reverse forwarding: 201/404/302, cookies, hop filtering, 2 MiB");
        } finally {
            listener.stop(0);
            upstream.stop(0);
            ioWorkers.shutdownNow();
        }
    }
}
