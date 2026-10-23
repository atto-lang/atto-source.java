package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Stream-start/owner-close state must linearize without permit leaks. */
public final class ProxyOutboundStartCloseRaceCheck {
    public static void main(String[] ignored) throws Exception {
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.setExecutor(workers);
        server.createContext("/ready", exchange -> {
            try {
                byte[] bytes = new byte[1024];
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        try (var client = new ProxyOutboundLoopback(1)) {
            var target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ready");
            // First close always wins: a later streamBody must not reenter or
            // occupy a permit that has already been released for reuse.
            var alreadyClosed = client.request(target, "GET").join();
            alreadyClosed.close();
            if (client.inFlight() != 0) throw new AssertionError("close leaked its lease");
            try {
                alreadyClosed.streamBody(new ByteArrayOutputStream(), () -> false);
                throw new AssertionError("stream reopened an already-closed response");
            } catch (IOException expected) {
                if (!expected.getMessage().contains("closed")) throw expected;
            }
            for (int turn = 0; turn < 150; turn++) {
                var response = client.request(target, "GET").join();
                var gate = new CountDownLatch(1);
                var stream = workers.submit(() -> {
                    gate.await();
                    try {
                        response.streamBody(new ByteArrayOutputStream(), () -> false);
                    } catch (IOException expectedCloseRace) {
                        if (!responseIsClosedMessage(expectedCloseRace)) throw expectedCloseRace;
                    }
                    return true;
                });
                var close = workers.submit(() -> {
                    gate.await();
                    response.close();
                    return true;
                });
                gate.countDown();
                if (!stream.get(5, TimeUnit.SECONDS) || !close.get(5, TimeUnit.SECONDS))
                    throw new AssertionError("race futures did not finish");
                if (client.inFlight() != 0)
                    throw new AssertionError("start/close race leaked an admission permit on turn " + turn);
            }
            var afterRaces = client.request(target, "GET").join();
            afterRaces.close();
            if (client.inFlight() != 0) throw new AssertionError("capacity not reusable after stress");
            System.out.println("PASS 150 concurrent stream-start/close races plus closed-stream denial");
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
    }

    private static boolean responseIsClosedMessage(IOException failure) {
        String message = failure.getMessage();
        return message != null && (message.contains("closed") || message.contains("cancelled"));
    }
}
