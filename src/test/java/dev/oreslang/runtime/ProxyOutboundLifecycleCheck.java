package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Real-socket shutdown, pending handoff, and close-once admission regressions. */
public final class ProxyOutboundLifecycleCheck {
    public static void main(String[] args) throws Exception {
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelledEntered = new CountDownLatch(1);
        var cancelledRelease = new CountDownLatch(1);
        var origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        origin.setExecutor(workers);
        origin.createContext("/ready", exchange -> {
            try {
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        origin.createContext("/bulk", exchange -> {
            try {
                byte[] bytes = new byte[8192];
                Arrays.fill(bytes, (byte) 3);
                exchange.sendResponseHeaders(200, 0);
                for (int i = 0; i < 32; i++) exchange.getResponseBody().write(bytes);
            } catch (IOException aborted) {
                // Closing the owned response interrupts the producer.
            } finally { exchange.close(); }
        });
        origin.createContext("/cancel-gate", exchange -> {
            cancelledEntered.countDown();
            try {
                if (!cancelledRelease.await(5, TimeUnit.SECONDS)) return;
                exchange.sendResponseHeaders(200, -1);
            } catch (IOException aborted) {
                // Expected after cancelling the pending client response.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        origin.createContext("/gate", exchange -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) return;
                byte[] body = "late".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException expectedShutdown) {
                // Cancellation before an upstream response may close the socket.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        origin.start();
        try {
            String base = "http://127.0.0.1:" + origin.getAddress().getPort();
            // A body handed off to an owner remains accounted until closure,
            // including when the owner closes the scope without consuming it.
            var owned = new ProxyOutboundLoopback(1);
            var alreadyDelivered = owned.request(URI.create(base + "/ready"), "GET").join();
            if (owned.inFlight()!=1) throw new AssertionError("delivered lease not accounted");
            owned.close();
            owned.close();
            alreadyDelivered.close();
            if (owned.inFlight()!=0) throw new AssertionError("scope close leaked delivered lease");
            try {
                owned.request(URI.create(base + "/ready"), "GET").join();
                throw new AssertionError("closed context accepted a new request");
            } catch (CompletionException expected) {
                if (!(expected.getCause() instanceof RejectedExecutionException)) throw expected;
            }

            // Closing a response while an I/O worker is stalled on a slow
            // downstream output must prevent it from reading/writing more chunks.
            var blockedOwner = new ProxyOutboundLoopback(1);
            var streaming = blockedOwner.request(URI.create(base + "/bulk"), "GET").join();
            var sinkEntered = new CountDownLatch(1);
            var sinkResume = new CountDownLatch(1);
            var copyResult = workers.submit(() -> {
                try {
                    streaming.streamBody(new OutputStream() {
                        @Override public void write(int value) throws IOException { }
                        @Override public void write(byte[] bytes, int off, int len) throws IOException {
                            sinkEntered.countDown();
                            try {
                                if (!sinkResume.await(5, TimeUnit.SECONDS))
                                    throw new IOException("sink gate timeout");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("sink interrupted", interrupted);
                            }
                        }
                    }, () -> false);
                    return false;
                } catch (IOException expectedShutdown) {
                    return expectedShutdown.getMessage().contains("owner closed");
                }
            });
            try {
                if (!sinkEntered.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("stream never reached slow sink");
                blockedOwner.close();
            } finally {
                sinkResume.countDown();
            }
            if (!copyResult.get(5, TimeUnit.SECONDS))
                throw new AssertionError("in-flight streaming ignored scope shutdown");
            if (blockedOwner.inFlight()!=0)
                throw new AssertionError("slow sink cancellation leaked permit");

            // Close while the upstream has accepted the request but no headers
            // are available; future must be cancelled, without a late success.
            var pendingScope = new ProxyOutboundLoopback(1);
            var pending = pendingScope.request(URI.create(base + "/gate"), "GET");
            if (!entered.await(5, TimeUnit.SECONDS))
                throw new AssertionError("upstream did not accept in-flight request");
            pendingScope.close();
            release.countDown();
            try {
                var illegal = pending.join();
                illegal.close();
                throw new AssertionError("a late response escaped scope closure");
            } catch (CancellationException expected) { /* owner shutdown won */ }
            waitUntilRetired(pendingScope);
            if (pendingScope.inFlight()!=0)
                throw new AssertionError("pending handshake permit was not reclaimed");

            // Guest-side cancellation must retire the pending transport call
            // without closing the scope, permitting a later valid request.
            var reusable = new ProxyOutboundLoopback(1);
            var abandoned = reusable.request(URI.create(base + "/cancel-gate"), "GET");
            if (!cancelledEntered.await(5, TimeUnit.SECONDS))
                throw new AssertionError("cancellable upstream was not admitted");
            if (!abandoned.cancel(true))
                throw new AssertionError("cancellable upstream was already completed");
            cancelledRelease.countDown();
            waitUntilRetired(reusable);
            if (reusable.inFlight()!=0)
                throw new AssertionError("explicit cancellation failed to reclaim permit");
            var value = reusable.request(URI.create(base + "/ready"), "GET").join();
            var bytes = new ByteArrayOutputStream();
            if (value.streamBody(bytes, () -> false)!=2 || !bytes.toString().equals("ok"))
                throw new AssertionError("regular stream failed after cancellation case");
            if (reusable.inFlight()!=0) throw new AssertionError("stream completion leaked lease");
            reusable.closeAndJoin();
            if (!reusable.transportTerminated())
                throw new AssertionError("native-worker retirement escaped a live HttpClient");
            System.out.println("PASS owner shutdown, pending headers cancellation, close-once admission and physical HttpClient retirement");
        } finally {
            release.countDown();
            cancelledRelease.countDown();
            origin.stop(0);
            workers.shutdownNow();
        }
    }

    private static void waitUntilRetired(ProxyOutboundLoopback scope) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (scope.inFlight()!=0 && System.nanoTime()<deadline) Thread.sleep(2);
    }
}
