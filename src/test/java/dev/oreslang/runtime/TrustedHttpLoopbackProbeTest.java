package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Actual localhost HTTP I/O with host-only admission; NOT guest-to-broker. */
final class TrustedHttpLoopbackProbeTest {
    @Test void realHttpInFlightSlotSurvivesDependentCancellation() throws Exception {
        var received = new CountDownLatch(1);
        var respond = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        try (ServerSocket listener = new ServerSocket(0, 4,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread server = new Thread(() -> {
                try (var accepted = listener.accept()) {
                    accepted.setSoTimeout(3000);
                    var reader = new BufferedReader(new InputStreamReader(
                            accepted.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    received.countDown();
                    if (!respond.await(3, TimeUnit.SECONDS))
                        throw new IllegalStateException("test server response timed out");
                    accepted.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                                    .getBytes(StandardCharsets.US_ASCII));
                    accepted.getOutputStream().flush();
                } catch (Throwable t) { failure.set(t); received.countDown(); }
            }, "quota-loopback-test");
            server.setDaemon(true);
            server.start();
            var gate = new TrustedHierarchicalNetworkGate();
            long startNanos = System.nanoTime();
            var clock = (java.util.function.LongSupplier)
                    () -> Math.max(0L, System.nanoTime() - startNanos);
            var limits = new TrustedUntrustedNetworkQuota.Policy(100, 10, 1, 10, 10, 2);
            var child = gate.createHostScope(limits, clock);
            var parent = gate.createHostScope(limits, clock);
            var tenant = gate.createHostScope(limits, clock);
            try (var broker = new TrustedHttpLoopbackProbe(gate, child, parent, tenant,
                    listener.getLocalPort())) {
                CompletableFuture<Integer> caller = broker.getStatus();
                assertTrue(received.await(3, TimeUnit.SECONDS));
                assertEquals(null, failure.get());
                assertEquals(TrustedUntrustedNetworkQuota.Status.TOO_MANY_IN_FLIGHT,
                        gate.tryStartHttp(child, parent, tenant).status());
                assertTrue(caller.cancel(true));
                // Give transport cancellation propagation time to manifest.
                // The trusted host operation must keep its slot until its
                // OWN response future settles, despite caller cancellation.
                Thread.sleep(150);
                assertEquals(TrustedUntrustedNetworkQuota.Status.TOO_MANY_IN_FLIGHT,
                        gate.tryStartHttp(child, parent, tenant).status());
                respond.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean released = false;
                while (System.nanoTime() < deadline) {
                    var attempt = gate.tryStartHttp(child, parent, tenant);
                    if (attempt.lease() != null) {
                        attempt.lease().close();
                        released = true;
                        break;
                    }
                    if (attempt.status()
                            != TrustedUntrustedNetworkQuota.Status.TOO_MANY_IN_FLIGHT) {
                        throw new AssertionError("unexpected retry status: " + attempt.status());
                    }
                    Thread.sleep(5);
                }
                assertTrue(released);
            } finally {
                respond.countDown();
                server.join(3000);
                assertEquals(null, failure.get());
                child.close(); parent.close(); tenant.close();
            }
        }
    }
    @Test void shutdownFencesAdmissionAndSettlesCancelledHostExchange() throws Exception {
        var acceptedRequest = new CountDownLatch(1);
        var releaseServer = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        try (ServerSocket listener = new ServerSocket(0, 4,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread server = new Thread(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    var reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    acceptedRequest.countDown();
                    if (!releaseServer.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("shutdown probe server timed out");
                    // Closing the server socket without a response is deliberate.
                } catch (Throwable error) {
                    failure.set(error);
                    acceptedRequest.countDown();
                }
            }, "quota-loopback-shutdown-test");
            server.setDaemon(true);
            server.start();
            var gate = new TrustedHierarchicalNetworkGate();
            long epoch = System.nanoTime();
            java.util.function.LongSupplier clock = () -> System.nanoTime() - epoch;
            var policy = new TrustedUntrustedNetworkQuota.Policy(100, 10, 1, 10, 10, 2);
            var child = gate.createHostScope(policy, clock);
            var parent = gate.createHostScope(policy, clock);
            var tenant = gate.createHostScope(policy, clock);
            try (var broker = new TrustedHttpLoopbackProbe(
                    gate, child, parent, tenant, listener.getLocalPort())) {
                CompletableFuture<Integer> pending = broker.getStatus();
                assertTrue(acceptedRequest.await(4, TimeUnit.SECONDS));
                assertEquals(null, failure.get());
                assertEquals(TrustedUntrustedNetworkQuota.Status.TOO_MANY_IN_FLIGHT,
                        gate.tryStartHttp(child, parent, tenant).status());
                broker.close();
                assertTrue(broker.clientTerminatedForHost());
                broker.close(); // idempotent after confirmed JDK termination
                assertTrue(broker.clientTerminatedForHost());
                assertTrue(broker.getStatus().isCompletedExceptionally());
                boolean settled = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (System.nanoTime() < deadline) {
                    var admitted = gate.tryStartHttp(child, parent, tenant);
                    if (admitted.lease() != null) {
                        admitted.lease().close();
                        settled = true;
                        break;
                    }
                    assertEquals(TrustedUntrustedNetworkQuota.Status.TOO_MANY_IN_FLIGHT,
                            admitted.status());
                    Thread.sleep(10);
                }
                assertTrue(settled);
                assertTrue(pending.isCompletedExceptionally());
            } finally {
                releaseServer.countDown();
                server.join(4000);
                assertFalse(server.isAlive());
                assertEquals(null, failure.get());
                child.close();
                parent.close();
                tenant.close();
            }
        }
    }

}
