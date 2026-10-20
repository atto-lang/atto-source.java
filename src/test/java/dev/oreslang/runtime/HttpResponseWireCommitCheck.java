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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Actual HTTP/1.1 wire checks for cancellation relative to headers commit. */
public final class HttpResponseWireCommitCheck {
    static final class Lease {
        boolean terminal, reserved, wireStarted, headersSent;
        synchronized void reserve() {
            if (terminal || reserved) throw new IllegalStateException("cannot reserve response");
            reserved = true;
        }
        synchronized boolean beginHeaders() {
            if (terminal || wireStarted || !reserved) return false;
            wireStarted = true;
            return true;
        }
        synchronized void noteHeadersSent() { headersSent = true; }
        synchronized void cancel() { terminal = true; }
        synchronized boolean cancelled() { return terminal; }
        synchronized boolean wasCommitted() { return wireStarted; }
        synchronized boolean wereHeadersSent() { return headersSent; }
    }

    private static void check(boolean value, String detail) {
        if (!value) throw new AssertionError(detail);
    }

    private static void beforeHeaders() throws Exception {
        Lease state = new Lease();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        CountDownLatch opening = new CountDownLatch(1), proceed = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        var workers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.setExecutor(workers);
        server.createContext("/", raw -> {
            try {
                // HttpClient may transparently retry a headerless response.
                // Refuse any new request once this one-shot fixture is terminal.
                if (state.cancelled()) return;
                HttpResponseDraft response = HttpResponseDraft.streaming(() -> {
                    opens.incrementAndGet();
                    return new InputStream() {
                        @Override public int read() { return -1; }
                        @Override public void close() { closes.incrementAndGet(); }
                    };
                }, 200, List.of());
                state.reserve();
                response.claim();
                response.writeClaimedTo(raw, state::cancelled, ignored -> {}, () -> {
                    opening.countDown();
                    try {
                        if (!proceed.await(3, TimeUnit.SECONDS)) throw new IOException("opening timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw new IOException(interrupted);
                    }
                    return response.openBody();
                }, state::beginHeaders, state::noteHeadersSent);
                serverFailure.set(new AssertionError("cancelled response completed normally"));
            } catch (IOException expected) {
                // No success headers may be sent if cancellation preceded beginHeaders.
            } catch (Throwable unexpected) {
                serverFailure.set(unexpected);
            } finally { raw.close(); stopped.countDown(); }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            var request = client.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            check(opening.await(3, TimeUnit.SECONDS), "precommit opener was never entered");
            state.cancel();
            proceed.countDown();
            try {
                var result = request.get(4, TimeUnit.SECONDS);
                throw new AssertionError("cancel before headers returned HTTP " + result.statusCode());
            } catch (ExecutionException expected) { /* peer closed without headers */ }
            check(stopped.await(3, TimeUnit.SECONDS), "precommit writer failed to stop");
            check(!state.wasCommitted() && !state.wereHeadersSent(), "headers began after cancellation");
            check(serverFailure.get() == null, "server failure: " + serverFailure.get());
            check(opens.get() == 1 && closes.get() == 1, "precommit stream must close once");
        } finally { proceed.countDown(); server.stop(0); workers.shutdownNow(); }
    }

    private static void afterHeaders() throws Exception {
        Lease state = new Lease();
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<InputStream> active = new AtomicReference<>();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        var workers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.setExecutor(workers);
        server.createContext("/", raw -> {
            try {
                // HttpClient may retry a headerless response; do not reuse this lease.
                if (state.cancelled()) return;
                HttpResponseDraft response = HttpResponseDraft.streaming(() -> new InputStream() {
                    @Override public int read() throws IOException {
                        reading.countDown();
                        try {
                            if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("read timeout");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt(); throw new IOException(e);
                        }
                        throw new IOException("aborted streaming read");
                    }
                    @Override public void close() { closes.incrementAndGet(); release.countDown(); }
                }, 200, List.of());
                state.reserve();
                response.claim();
                response.writeClaimedTo(raw, state::cancelled, active::set, response::openBody,
                        state::beginHeaders, state::noteHeadersSent);
                serverFailure.set(new AssertionError("cancelled streaming response completed normally"));
            } catch (IOException expected) { /* incomplete body after 200 headers */ }
            catch (Throwable unexpected) { serverFailure.set(unexpected); }
            finally { raw.close(); stopped.countDown(); }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            var headers = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            check(headers.statusCode() == 200, "headers must precede body read");
            check(reading.await(3, TimeUnit.SECONDS), "body reader did not start");
            state.cancel();
            InputStream input = active.get();
            if (input != null) input.close();
            release.countDown();
            check(stopped.await(3, TimeUnit.SECONDS), "writer failed to abort after headers");
            check(state.wasCommitted() && state.wereHeadersSent(), "headers were already committed");
            check(closes.get() == 1, "postcommit source closed more than once");
            check(serverFailure.get() == null, "server failure: " + serverFailure.get());
            headers.body().close();
        } finally { release.countDown(); server.stop(0); workers.shutdownNow(); }
    }

    public static void main(String[] ignored) throws Exception {
        for (int i = 0; i < 8; i++) { beforeHeaders(); afterHeaders(); }
        System.out.println("PASS: 8 pre-header cancellations and 8 post-header stream aborts");
    }
}
