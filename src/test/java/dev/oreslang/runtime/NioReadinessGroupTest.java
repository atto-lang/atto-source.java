package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class NioReadinessGroupTest {
    @Test void fivePendingReadsUseOneReactorNotFiveIoThreads() throws Exception {
        try (var group = new NioReadinessGroup(4, 32, 64);
             var listener = ServerSocketChannel.open()) {
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
            var incoming = new ArrayList<SocketChannel>();
            var peers = new ArrayList<Socket>();
            var pending = new ArrayList<OresFuture<Integer>>();
            try {
                for (int i = 0; i < 5; i++) {
                    var peer = new Socket(InetAddress.getLoopbackAddress(), port);
                    peers.add(peer);
                    SocketChannel channel = null;
                    for (int n = 0; n < 1000 && channel == null; n++) {
                        channel = listener.accept();
                        if (channel == null) Thread.sleep(1); // JUnit host thread only
                    }
                    assertNotNull(channel);
                    channel.configureBlocking(false);
                    incoming.add(channel);
                    pending.add(group.watch(channel, SelectionKey.OP_READ));
                }
                assertEquals(1, group.reactorCount());
                assertEquals(5, group.pendingCount());
                assertEquals(0, pending.stream().filter(OresFuture::isDone).count());
                for (var peer : peers) peer.getOutputStream().write(0x41);
                for (var future : pending)
                    assertTrue((future.get(2, TimeUnit.SECONDS) & SelectionKey.OP_READ) != 0);
                assertEquals(0, group.pendingCount());

                // Cancelling a pending watch must not occupy a carrier until
                // the socket becomes readable, nor retain the registration.
                var abandoned = group.watch(incoming.get(0), SelectionKey.OP_READ);
                assertTrue(abandoned.cancel(false));
                for (int n = 0; n < 1000 && group.pendingCount() != 0; n++)
                    Thread.sleep(1);
                assertEquals(0, group.pendingCount());

                var stopped = group.watch(incoming.get(1), SelectionKey.OP_READ);
                group.close();
                assertThrows(ExecutionException.class, () -> stopped.get(2, TimeUnit.SECONDS));
                assertTrue(group.awaitStopped(2, TimeUnit.SECONDS));
            } finally {
                for (var peer : peers) peer.close();
                for (var channel : incoming) channel.close();
            }
        }
    }

    @Test void failClosedOnInvalidAdmissionAndMaxPending() throws Exception {
        try (var group = new NioReadinessGroup(1, 1, 8);
             var listener = ServerSocketChannel.open()) {
            assertThrows(IllegalArgumentException.class,
                    () -> group.watch(listener, SelectionKey.OP_ACCEPT));
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            var waiting = group.watch(listener, SelectionKey.OP_ACCEPT);
            assertFalse(waiting.isDone());
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> group.watch(listener, SelectionKey.OP_READ).get());
            // A second watch on the same channel fails independently of count.
            assertThrows(ExecutionException.class,
                    () -> group.watch(listener, SelectionKey.OP_ACCEPT).get());
            assertTrue(waiting.cancel(false));
            group.close();
            assertTrue(group.awaitStopped(2, TimeUnit.SECONDS));
        }
    }

    @Test void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NioReadinessGroup(0, 10, 8));
        assertThrows(IllegalArgumentException.class, () -> new NioReadinessGroup(5, 10, 8));
        assertThrows(IllegalArgumentException.class, () -> new NioReadinessGroup(1, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> new NioReadinessGroup(1, 10, 7));
    }
}
