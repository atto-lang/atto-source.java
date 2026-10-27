package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class VmNioTcpConnectionTest {
    @Test
    void closeAsyncWaitsForPendingReadRetirementAndRunsTerminalHookOnce()
            throws Exception {
        try (var group = new NioReadinessGroup(1, 32, 16);
             var listener = ServerSocketChannel.open()) {
            InetAddress loopback =
                    InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
            listener.bind(new InetSocketAddress(loopback, 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            var connecting = group.connectAsync(new InetSocketAddress(loopback, port));
            try (var peer = listener.accept()) {
                var client = connecting.get(3, TimeUnit.SECONDS);
                var terminalHooks = new AtomicInteger();
                var connection = new VmNioTcpConnection(
                        group, client, 4, terminalHooks::incrementAndGet);

                var pendingRead = connection.readBytesAsync(8);
                assertFalse(pendingRead.isDone(),
                        "silent peer must leave strict read pending");

                var closed = connection.closeAsync();
                assertTrue(closed.get(3, TimeUnit.SECONDS) == null);
                assertTrue(pendingRead.isDone());
                assertThrows(CancellationException.class, pendingRead::get);

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (group.activeReadLeases() != 0
                        && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertEquals(0, group.activeReadLeases(),
                        "close receipt must include physical read-watch retirement");
                assertTrue(connection.isTerminated());
                assertEquals(1, terminalHooks.get(),
                        "context retirement hook must be exactly once");

                // Idempotent close must neither re-run the hook nor publish a
                // second resource-retirement receipt.
                assertSame(closed, connection.closeAsync());
                assertEquals(1, terminalHooks.get());
            }

            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void connectionAllowsOneReadAndOneWriteButRejectsDuplicateRead()
            throws Exception {
        try (var group = new NioReadinessGroup(1, 32, 16);
             var listener = ServerSocketChannel.open()) {
            InetAddress loopback =
                    InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
            listener.bind(new InetSocketAddress(loopback, 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            var connecting = group.connectAsync(new InetSocketAddress(loopback, port));
            try (var peer = listener.accept()) {
                peer.configureBlocking(true);
                var connection = new VmNioTcpConnection(
                        group, connecting.get(3, TimeUnit.SECONDS), 4, () -> { });

                var pendingRead = connection.readBytesAsync(4);
                assertFalse(pendingRead.isDone());

                var duplicateRead = connection.readBytesAsync(1);
                var duplicateFailure = assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        duplicateRead::get);
                assertInstanceOf(IllegalStateException.class,
                        duplicateFailure.getCause());
                assertTrue(duplicateFailure.getCause().getMessage()
                                .contains("outstanding read"),
                        duplicateFailure.getCause().getMessage());

                var write = connection.writeBytesAsync(
                        java.util.List.of(80L, 73L, 78L, 71L));
                assertEquals(4, write.get(3, TimeUnit.SECONDS));
                var inbound = java.nio.ByteBuffer.allocate(4);
                while (inbound.hasRemaining())
                    assertTrue(peer.read(inbound) > 0);
                assertArrayEquals(new byte[] {80, 73, 78, 71}, inbound.array());

                assertFalse(pendingRead.isDone(),
                        "write completion must not settle sibling read");
                peer.write(java.nio.ByteBuffer.wrap(new byte[] {80, 79, 78, 71}));
                assertEquals(java.util.List.of(80L, 79L, 78L, 71L),
                        pendingRead.get(3, TimeUnit.SECONDS));

                connection.closeAsync().get(3, TimeUnit.SECONDS);
                assertTrue(connection.isTerminated());
            }

            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void closedOutboundConnectionFailsFurtherIoWithoutReadinessAdmission()
            throws Exception {
        try (var group = new NioReadinessGroup(1, 16, 8);
             var listener = ServerSocketChannel.open()) {
            InetAddress loopback =
                    InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
            listener.bind(new InetSocketAddress(loopback, 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();

            var connecting = group.connectAsync(new InetSocketAddress(loopback, port));
            try (var peer = listener.accept()) {
                var connection = new VmNioTcpConnection(
                        group, connecting.get(3, TimeUnit.SECONDS), 2, () -> { });
                connection.closeAsync().get(3, TimeUnit.SECONDS);

                var read = connection.readBytesAsync(1);
                var write = connection.writeBytesAsync(java.util.List.of(1L));
                assertTrue(read.isCompletedExceptionally());
                assertTrue(write.isCompletedExceptionally());
                assertEquals(0, group.activeReadLeases());
                assertEquals(0, group.activeWriteLeases());
            }

            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
        }
    }
}
