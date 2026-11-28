package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real HTTP response must not claim 200 when a body producer times out. */
public final class HttpResponseBodyOpenWireCheck {
    public static void main(String[] args) throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger lateCloses = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        var gate = new HttpResponseBodyOpenGate(1, workers);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try {
                if (exchange.getRequestURI().getPath().equals("/ok")) {
                    HttpResponseDraft response = HttpResponseDraft.text("ready");
                    response.writeTo(exchange, () -> false, ignored -> {});
                } else {
                    HttpResponseDraft response = HttpResponseDraft.streaming(() -> {
                        starts.incrementAndGet();
                        while (true) {
                            try { if (release.await(1, TimeUnit.SECONDS)) break; }
                            catch (InterruptedException ignored) { }
                        }
                        return new ByteArrayInputStream(new byte[]{ 1 }) {
                            @Override public void close() throws IOException {
                                lateCloses.incrementAndGet();
                                super.close();
                            }
                        };
                    }, 200, List.of());
                    response.claim();
                    response.writeClaimedTo(exchange, () -> false, ignored -> {},
                            () -> gate.open(response, () -> false, TimeUnit.MILLISECONDS.toNanos(60)));
                }
            } catch (IOException expected) {
                rejections.incrementAndGet();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            try {
                var result = client.send(HttpRequest.newBuilder(base.resolve("/slow"))
                                .timeout(Duration.ofSeconds(3)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                throw new AssertionError("stuck producer emitted HTTP " + result.statusCode());
            } catch (IOException expected) { /* no success response headers */ }
            if (starts.get() != 1 || gate.availableSlots() != 0)
                throw new AssertionError("stuck producer did not retain bounded admission");
            HttpResponse<String> healthy = client.send(HttpRequest.newBuilder(base.resolve("/ok"))
                            .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
            if (healthy.statusCode() != 200 || !healthy.body().equals("ready"))
                throw new AssertionError("buffered HTTP response did not remain available");
            try {
                var result = client.send(HttpRequest.newBuilder(base.resolve("/slow"))
                                .timeout(Duration.ofSeconds(3)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                throw new AssertionError("overloaded producer emitted HTTP " + result.statusCode());
            } catch (IOException expected) { /* fail closed before headers */ }
            if (starts.get() != 1)
                throw new AssertionError("a second supplier started despite full admission");
            release.countDown();
            for (int i = 0; i < 200 && (lateCloses.get() != 1 || gate.availableSlots() != 1); i++)
                Thread.sleep(5);
            if (lateCloses.get() != 1 || gate.availableSlots() != 1 || rejections.get() < 2)
                throw new AssertionError("late stream not closed / admissions not released");
        } finally {
            release.countDown();
            server.stop(0);
            workers.shutdownNow();
        }
        System.out.println("PASS: HTTP timeout, overloaded supplier rejection, buffered health and late close");
    }
}
