package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Exact-class no-Maven loopback socket and admission regression. */
public final class ProxyOutboundLoopbackCheck {
    public static void main(String[] args) throws Exception {
        var workers = Executors.newCachedThreadPool();
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        origin.setExecutor(workers);
        origin.createContext("/created", exchange -> {
            try {
                exchange.getResponseHeaders().add("Set-Cookie", "a=1; Path=/");
                exchange.getResponseHeaders().add("Set-Cookie", "b=2; Path=/");
                exchange.getResponseHeaders().add("Connection", "X-Remove");
                exchange.getResponseHeaders().add("X-Remove", "not-forwarded");
                exchange.getResponseHeaders().add("X-Origin", "ok");
                byte[] body = "created".getBytes(StandardCharsets.UTF_8);
                if ("HEAD".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
                    exchange.sendResponseHeaders(201, -1);
                } else {
                    exchange.sendResponseHeaders(201, body.length);
                    exchange.getResponseBody().write(body);
                }
            } finally { exchange.close(); }
        });
        origin.createContext("/stream", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                byte[] chunk = new byte[8192];
                Arrays.fill(chunk, (byte) 31);
                for (int i=0;i<256;i++) exchange.getResponseBody().write(chunk);
            } catch (IOException closedEarly) { /* client can cancel the stream */ }
            finally { exchange.close(); }
        });
        origin.createContext("/notfound", exchange -> {
            try {
                byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        origin.createContext("/no-content", exchange -> {
            try { exchange.sendResponseHeaders(204, -1); }
            finally { exchange.close(); }
        });
        origin.createContext("/redirect", exchange -> {
            try {
                exchange.getResponseHeaders().add("Location", "http://example.com/");
                exchange.sendResponseHeaders(302, -1);
            } finally { exchange.close(); }
        });
        origin.start();
        try {
            String base = "http://127.0.0.1:" + origin.getAddress().getPort();
            var client = new ProxyOutboundLoopback(1);
            for (String bad : new String[] {"http://localhost:8080/x", "http://127.0.0.2:8080/x",
                    "http://user@127.0.0.1:8080/x", "https://127.0.0.1:8080/x",
                    base + "/a%2Fb", base + "/x#fragment", base + "/a/../created",
                    base + "/a/./created", base + "/a//created", base + "/created?redirect=true",
                    base + "/é"}) {
                try {
                    client.request(URI.create(bad), "GET").join();
                    throw new AssertionError("unsafe origin accepted: " + bad);
                } catch (CompletionException expected) {
                    if (!(expected.getCause() instanceof IllegalArgumentException)) throw expected;
                }
            }
            var created = client.request(URI.create(base + "/created"), "GET").join();
            if (created.status()!=201) throw new AssertionError("lost 201 response status");
            if (created.headers().stream().filter(x -> x.getKey().equalsIgnoreCase("set-cookie")).count()!=2)
                throw new AssertionError("lost duplicate Set-Cookie");
            if (created.headers().stream().anyMatch(x -> x.getKey().equalsIgnoreCase("connection") ||
                    x.getKey().equalsIgnoreCase("x-remove") || x.getKey().equalsIgnoreCase("content-length")))
                throw new AssertionError("forwarded hop-by-hop or transport framing header");
            try {
                client.request(URI.create(base + "/created"), "GET").join();
                throw new AssertionError("outbound admission exceeded capacity");
            } catch (CompletionException expected) {
                if (!(expected.getCause() instanceof RejectedExecutionException)) throw expected;
            }
            ByteArrayOutputStream first = new ByteArrayOutputStream();
            if (created.streamBody(first, () -> false) != 7 || !first.toString(StandardCharsets.UTF_8).equals("created"))
                throw new AssertionError("wrong 201 body");
            created.close();
            var streamed = client.request(URI.create(base + "/stream"), "GET").join();
            ByteArrayOutputStream big = new ByteArrayOutputStream();
            if (streamed.streamBody(big, () -> false) != 8192L*256 || big.size()!=8192*256)
                throw new AssertionError("large body was truncated");
            var missing = client.request(URI.create(base + "/notfound"), "GET").join();
            if (missing.status()!=404) throw new AssertionError("lost 404 status");
            ByteArrayOutputStream absent = new ByteArrayOutputStream();
            if (missing.streamBody(absent, () -> false)!=9 || !absent.toString(StandardCharsets.UTF_8).equals("not found"))
                throw new AssertionError("missing 404 payload");
            var empty = client.request(URI.create(base + "/no-content"), "GET").join();
            if (empty.status()!=204) throw new AssertionError("lost 204 status");
            if (empty.streamBody(new ByteArrayOutputStream(), () -> false)!=0)
                throw new AssertionError("unexpected body on 204");
            var cancelled = client.request(URI.create(base + "/stream"), "GET").join();
            try {
                cancelled.streamBody(new ByteArrayOutputStream(), () -> true);
                throw new AssertionError("pre-stream cancellation was ignored");
            } catch (IOException expected) {
                if (!expected.getMessage().contains("cancelled")) throw expected;
            }
            var reclaimed = client.request(URI.create(base + "/created"), "HEAD").join();
            if (reclaimed.status()!=201) throw new AssertionError("cancellation did not return permit");
            if (reclaimed.streamBody(new ByteArrayOutputStream(), () -> false)!=0)
                throw new AssertionError("HEAD unexpectedly yielded a body");
            var redirect = client.request(URI.create(base + "/redirect"), "GET").join();
            if (redirect.status()!=302) throw new AssertionError("unsafe redirect followed");
            redirect.close();
            client.close();

            try (var limited = new ProxyOutboundLoopback(1, 1024)) {
                var tooLarge = limited.request(URI.create(base + "/stream"), "GET").join();
                try {
                    tooLarge.streamBody(new ByteArrayOutputStream(), () -> false);
                    throw new AssertionError("response byte budget was ignored");
                } catch (IOException expected) {
                    if (!expected.getMessage().contains("byte budget")) throw expected;
                }
                var afterBudgetFailure = limited.request(URI.create(base + "/created"), "HEAD").join();
                if (afterBudgetFailure.status()!=201)
                    throw new AssertionError("byte-budget failure did not reclaim admission");
                afterBudgetFailure.close();
            }
            System.out.println("PASS loopback HTTP status, duplicate headers, byte-bounded streams, admission and redirect denial");
        } finally {
            origin.stop(0);
            workers.shutdownNow();
        }
    }
}
