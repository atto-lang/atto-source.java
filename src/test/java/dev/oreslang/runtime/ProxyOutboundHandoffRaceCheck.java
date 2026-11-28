package dev.oreslang.runtime;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** A closed response must not retire its permit while a body writer is still live. */
public final class ProxyOutboundHandoffRaceCheck {
    public static void main(String[] args) throws Exception {
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.setExecutor(workers);
        server.createContext("/x", exchange -> {
            try {
                byte[] data = new byte[8192];
                exchange.sendResponseHeaders(200, 0);
                for (int i=0;i<8;i++) exchange.getResponseBody().write(data);
            } catch (IOException closed) { /* remote body may be closed on purpose */ }
            finally { exchange.close(); }
        });
        server.start();
        try (var client = new ProxyOutboundLoopback(1)) {
            var url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/x");
            var response = client.request(url, "GET").join();
            var sinkEntered = new CountDownLatch(1);
            var sinkResume = new CountDownLatch(1);
            var consumer = workers.submit(() -> {
                try {
                    response.streamBody(new OutputStream() {
                        @Override public void write(int v) { }
                        @Override public void write(byte[] b, int offset, int count) throws IOException {
                            sinkEntered.countDown();
                            try {
                                if (!sinkResume.await(4, TimeUnit.SECONDS)) throw new IOException("sink timed out");
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                throw new IOException(ex);
                            }
                        }
                    }, () -> false);
                    return false;
                } catch (IOException expectedAfterClose) { return true; }
            });
            try {
                if (!sinkEntered.await(4, TimeUnit.SECONDS)) throw new AssertionError("sink not reached");
                response.close();
                if (client.inFlight()!=1)
                    throw new AssertionError("permit retired while an active downstream writer still existed");
                try {
                    client.request(url, "GET").join();
                    throw new AssertionError("capacity over-admitted while stalled writer was active");
                } catch (CompletionException rejected) {
                    if (!(rejected.getCause() instanceof RejectedExecutionException)) throw rejected;
                }
            } finally { sinkResume.countDown(); }
            if (!consumer.get(5, TimeUnit.SECONDS))
                throw new AssertionError("stream continued successfully after forced response close");
            if (client.inFlight()!=0) throw new AssertionError("writer finished but permit stayed occupied");
            client.request(url, "GET").join().close();
            System.out.println("PASS no early permit reuse while downstream writer is still running");
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
