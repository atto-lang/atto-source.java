package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Socket-level host/ActorRuntime acceptance proof for the rolling route.
 * This tests real HTTP/1.1 networking and physical SHARED mailbox messages;
 * it does NOT substitute for the separate NativeHttpRollingDispatcher guest
 * source/NativeHttp callback integration gate.
 */
@Timeout(30)
final class ActorRollingHttpSocketTest {
    private static HttpRequest get(int port) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/reload"))
                .timeout(Duration.ofSeconds(10)).GET().build();
    }

    @Test
    void sameListenerStaysBoundWhileOldActorCompletesAndNewActorServes()
            throws Exception {
        long pidBefore = ProcessHandle.current().pid();
        CountDownLatch oldEntered = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch oldHandled = new CountDownLatch(1);
        CountDownLatch newHandled = new CountDownLatch(1);
        try (ActorRuntime actors = new ActorRuntime();
             HttpClient client = HttpClient.newHttpClient();
             var io = Executors.newVirtualThreadPerTaskExecutor()) {
            var g1 = actors.<byte[]>spawnShared(() -> (message, ctx) -> {
                oldEntered.countDown();
                if (!releaseOld.await(10, TimeUnit.SECONDS))
                    throw new AssertionError("held old actor exceeded test deadline");
                oldHandled.countDown();
            });
            var g2 = actors.<byte[]>spawnShared(() -> (message, ctx) -> {
                newHandled.countDown();
            });
            g1.getReady().get(8, TimeUnit.SECONDS);
            g2.getReady().get(8, TimeUnit.SECONDS);

            try (var route = new ActorRollingGenerationRoute<>(745L, g1, 2)) {
                HttpServer listener = HttpServer.create(
                        new InetSocketAddress("127.0.0.1", 0), 8);
                listener.setExecutor(io);
                listener.createContext("/", exchange -> {
                    try (var pinned = route.acquire()) {
                        // Bind the exact message to the epoch captured on
                        // HTTP admission. Do not refund the lease on send().
                        pinned.target().send(new byte[] {1});
                        boolean delivered = pinned.epoch() == 1L
                                ? oldHandled.await(8, TimeUnit.SECONDS)
                                : newHandled.await(8, TimeUnit.SECONDS);
                        if (!delivered) throw new IllegalStateException("actor request not completed");
                        byte[] body = ("G" + pinned.epoch()).getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        try (var out = exchange.getResponseBody()) { out.write(body); }
                    } catch (Exception failure) {
                        try {
                            byte[] body = "request failed".getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(503, body.length);
                            try (var out = exchange.getResponseBody()) { out.write(body); }
                        } catch (Exception ignored) { exchange.close(); }
                    }
                });
                listener.start();
                int boundPort = listener.getAddress().getPort();
                try {
                    CompletableFuture<HttpResponse<String>> oldRequest =
                            client.sendAsync(get(boundPort), HttpResponse.BodyHandlers.ofString());
                    assertTrue(oldEntered.await(8, TimeUnit.SECONDS));
                    assertTrue(route.publishReady(1L, g2));
                    assertFalse(route.admissionsDrained(1L));
                    assertEquals(2L, route.snapshot().activeEpoch());
                    HttpResponse<String> replacementResponse =
                            client.send(get(boundPort), HttpResponse.BodyHandlers.ofString());
                    assertTrue(newHandled.await(8, TimeUnit.SECONDS));
                    assertEquals(200, replacementResponse.statusCode());
                    assertEquals("G2", replacementResponse.body());
                    assertFalse(oldRequest.isDone(),
                            "already-admitted G1 work must remain owned by G1");
                    assertEquals(boundPort, listener.getAddress().getPort());
                    assertEquals(pidBefore, ProcessHandle.current().pid());

                    releaseOld.countDown();
                    HttpResponse<String> completedOld =
                            oldRequest.get(8, TimeUnit.SECONDS);
                    assertEquals(200, completedOld.statusCode());
                    assertEquals("G1", completedOld.body());

                    // Old traffic lease completion does not automatically
                    // prove arena/code/native-callback physical retirement.
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!route.admissionsDrained(1L) && System.nanoTime() < deadline)
                        Thread.sleep(2);
                    assertTrue(route.admissionsDrained(1L));
                    g1.stop();
                    g1.getDone().get(8, TimeUnit.SECONDS);
                    assertThrows(IllegalStateException.class,
                            () -> route.acknowledgePhysicallyRetired(1L),
                            "actor termination without Task/FFI/code proof must not reclaim G1");
                    assertEquals(2, route.snapshot().liveGenerations());
                } finally {
                    releaseOld.countDown();
                    listener.stop(0);
                }
            }
            g2.stop();
            g2.getDone().get(8, TimeUnit.SECONDS);
        } finally {
            releaseOld.countDown();
        }
    }
}
