package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real-socket stream cancellation race: abort cleanup and writer close same body. */
public final class HttpResponseAbortRaceWireCheck {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] unused) throws Exception {
        for (int attempt = 0; attempt < 25; attempt++) {
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicInteger underlyingCloses = new AtomicInteger();
            AtomicReference<InputStream> activeBody = new AtomicReference<>();
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            CountDownLatch bodyRegistered = new CountDownLatch(1);
            CountDownLatch readStarted = new CountDownLatch(1);
            CountDownLatch sourceClosed = new CountDownLatch(1);
            CountDownLatch handlerDone = new CountDownLatch(1);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            var workers = Executors.newCachedThreadPool();
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try {
                    HttpResponseDraft response = HttpResponseDraft.streaming(() -> new InputStream() {
                        @Override public int read() throws IOException {
                            byte[] one = new byte[1];
                            int n = read(one, 0, 1);
                            return n < 0 ? -1 : one[0] & 255;
                        }
                        @Override public int read(byte[] b, int off, int len) throws IOException {
                            readStarted.countDown();
                            try {
                                if (!sourceClosed.await(5, TimeUnit.SECONDS))
                                    throw new IOException("source close timed out");
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IOException("read interrupted", error);
                            }
                            throw new IOException("stream cancelled");
                        }
                        @Override public void close() {
                            underlyingCloses.incrementAndGet();
                            sourceClosed.countDown();
                        }
                    }, 200, List.of());
                    response.writeTo(exchange, cancelled::get, input -> {
                        activeBody.set(input);
                        if (input != null) bodyRegistered.countDown();
                    });
                    serverFailure.set(new AssertionError("cancelled stream completed normally"));
                } catch (IOException expected) {
                    // The HEADERS_COMMITTED response aborts without emitting EOF.
                } catch (Throwable unexpected) {
                    serverFailure.set(unexpected);
                } finally {
                    exchange.close();
                    handlerDone.countDown();
                }
            });
            server.start();
            try (HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3)).build()) {
                URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
                CompletableFuture<HttpResponse<byte[]>> request = client.sendAsync(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                check(bodyRegistered.await(3, TimeUnit.SECONDS), "body registration not observed");
                check(readStarted.await(3, TimeUnit.SECONDS), "body read not observed");
                cancelled.set(true);
                InputStream input = activeBody.get();
                check(input != null, "missing active response input");
                try (var closers = Executors.newFixedThreadPool(4)) {
                    var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                    for (int i = 0; i < 4; i++) jobs.add(closers.submit(() -> {
                        try { input.close(); }
                        catch (IOException error) { throw new AssertionError(error); }
                    }));
                    for (var job : jobs) job.get(3, TimeUnit.SECONDS);
                }
                check(handlerDone.await(3, TimeUnit.SECONDS), "writer cleanup did not complete");
                try { request.get(3, TimeUnit.SECONDS); }
                catch (java.util.concurrent.ExecutionException expected) { /* truncated response */ }
                catch (java.util.concurrent.TimeoutException expected) { request.cancel(true); }
                check(serverFailure.get() == null, "unexpected handler failure: " + serverFailure.get());
                check(underlyingCloses.get() == 1,
                        "underlying source close count: " + underlyingCloses.get());
            } finally {
                server.stop(0);
                workers.shutdownNow();
            }
        }
        System.out.println("PASS: 25 real HTTP cancellation/close races, one underlying close each");
    }
}
