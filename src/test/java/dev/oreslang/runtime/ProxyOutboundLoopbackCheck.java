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
                exchange.sendResponseHeaders(201, body.length);
                exchange.getResponseBody().write(body);
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
                    base + "/a%2Fb", base + "/x#fragment"}) {
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
            var redirect = client.request(URI.create(base + "/redirect"), "GET").join();
            if (redirect.status()!=302) throw new AssertionError("unsafe redirect followed");
            redirect.close();
            System.out.println("PASS loopback HTTP status, duplicate headers, bounded streams, admission and redirect denial");
        } finally {
            origin.stop(0);
            workers.shutdownNow();
        }
    }
}
