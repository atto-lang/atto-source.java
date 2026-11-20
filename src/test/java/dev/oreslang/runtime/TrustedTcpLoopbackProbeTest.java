package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Real TCP loopback host quota checks, not protected guest networking. */
final class TrustedTcpLoopbackProbeTest {
    private static TrustedUntrustedNetworkQuota.Policy limits() {
        return new TrustedUntrustedNetworkQuota.Policy(100, 10, 10, 1, 100, 10);
    }

    @Test void actualTcpSocketHoldsAllThreeConnectionSlotsUntilClosed() throws Exception {
        var ready = new CountDownLatch(1);
        var releaseServer = new CountDownLatch(1);
        var serverFailure = new AtomicReference<Throwable>();
        try (var server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            Thread accept = new Thread(() -> {
                try (var client = server.accept()) {
                    ready.countDown();
                    if (!releaseServer.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("TCP test server timed out");
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                    ready.countDown();
                }
            }, "tcp-quota-host-test");
            accept.setDaemon(true);
            accept.start();
            var gate = new TrustedHierarchicalNetworkGate();
            long epoch = System.nanoTime();
            java.util.function.LongSupplier clock = () -> System.nanoTime() - epoch;
            var child = gate.createHostScope(limits(), clock);
            var parent = gate.createHostScope(limits(), clock);
            var tenant = gate.createHostScope(limits(), clock);
            try (var broker = new TrustedTcpLoopbackProbe(
                    gate, child, parent, tenant, server.getLocalPort())) {
                var connection = broker.connect().get(3, TimeUnit.SECONDS);
                assertTrue(ready.await(3, TimeUnit.SECONDS));
                assertEquals(null, serverFailure.get());
                assertTrue(connection.isOpen());
                assertEquals(TrustedUntrustedNetworkQuota.Status.TOO_MANY_CONNECTIONS,
                        gate.tryOpenTcp(child, parent, tenant).status());
                assertTrue(broker.connect().isCompletedExceptionally());
                connection.close();
                connection.close(); // exact-once lease settlement
                assertFalse(connection.isOpen());
                var retry = gate.tryOpenTcp(child, parent, tenant);
                assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, retry.status());
                retry.lease().close();
                var again = gate.tryOpenTcp(child, parent, tenant);
                assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, again.status());
                again.lease().close();
            } finally {
                releaseServer.countDown();
                accept.join(3000);
                assertFalse(accept.isAlive());
                assertEquals(null, serverFailure.get());
                child.close(); parent.close(); tenant.close();
            }
        }
    }

    @Test void supervisorShutdownClosesRealHostTcpAndFencesNewDial() throws Exception {
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var serverFailure = new AtomicReference<Throwable>();
        try (var server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            Thread accept = new Thread(() -> {
                try (var socket = server.accept()) {
                    ready.countDown();
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("shutdown test server timed out");
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                    ready.countDown();
                }
            }, "tcp-quota-shutdown-test");
            accept.setDaemon(true);
            accept.start();
            var gate = new TrustedHierarchicalNetworkGate();
            long epoch = System.nanoTime();
            java.util.function.LongSupplier clock = () -> System.nanoTime() - epoch;
            var child = gate.createHostScope(limits(), clock);
            var parent = gate.createHostScope(limits(), clock);
            var tenant = gate.createHostScope(limits(), clock);
            try (var broker = new TrustedTcpLoopbackProbe(
                    gate, child, parent, tenant, server.getLocalPort())) {
                var connection = broker.connect().get(3, TimeUnit.SECONDS);
                assertTrue(ready.await(3, TimeUnit.SECONDS));
                broker.close();
                broker.close(); // retry shutdown must remain safe
                assertFalse(connection.isOpen());
                assertTrue(broker.connect().isCompletedExceptionally());
                var retry = gate.tryOpenTcp(child, parent, tenant);
                assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, retry.status());
                retry.lease().close();
            } finally {
                release.countDown();
                accept.join(3000);
                assertFalse(accept.isAlive());
                assertEquals(null, serverFailure.get());
                child.close(); parent.close(); tenant.close();
            }
        }
    }

    @Test void failedConnectReleasesActualHostTcpSlot() throws Exception {
        int unusedPort;
        try (var reserved = new ServerSocket(0, 1,
                InetAddress.getByName("127.0.0.1"))) {
            unusedPort = reserved.getLocalPort();
        }
        var gate = new TrustedHierarchicalNetworkGate();
        long epoch = System.nanoTime();
        java.util.function.LongSupplier clock = () -> System.nanoTime() - epoch;
        var child = gate.createHostScope(limits(), clock);
        var parent = gate.createHostScope(limits(), clock);
        var tenant = gate.createHostScope(limits(), clock);
        try (var broker = new TrustedTcpLoopbackProbe(
                gate, child, parent, tenant, unusedPort)) {
            CompletableFuture<TrustedTcpLoopbackProbe.Connection> caller = broker.connect();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> caller.get(3, TimeUnit.SECONDS));
            var retry = gate.tryOpenTcp(child, parent, tenant);
            assertEquals(TrustedUntrustedNetworkQuota.Status.ADMITTED, retry.status());
            retry.lease().close();
        } finally {
            child.close(); parent.close(); tenant.close();
        }
    }
}
