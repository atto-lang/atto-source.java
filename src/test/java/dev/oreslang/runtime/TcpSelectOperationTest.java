package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class TcpSelectOperationTest {
    private static SocketChannel acceptEventually(ServerSocketChannel listener) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        for (;;) {
            SocketChannel channel = listener.accept();
            if (channel != null) {
                channel.configureBlocking(false);
                return channel;
            }
            if (System.nanoTime() >= deadline) fail("timed out accepting loopback connection");
            Thread.sleep(1);
        }
    }

    private static int readEventually(SocketChannel channel, ByteBuffer target) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        for (;;) {
            int count = channel.read(target);
            if (count != 0) return count;
            if (System.nanoTime() >= deadline) fail("timed out reading loopback connection");
            Thread.sleep(1);
        }
    }

    @Test
    void losingReadDescriptorDoesNotConsumeReadyBytes() throws Exception {
        try (var listener = ServerSocketChannel.open();
             var client = SocketChannel.open()) {
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            client.connect(listener.getLocalAddress());
            try (var server = acceptEventually(listener)) {
                byte[] payload = {11, 22, 33, 44};
                client.write(ByteBuffer.wrap(payload));

                var losing = new TcpSelectOperation.Read(server, payload.length);
                assertTrue(losing.markReady());
                assertTrue(losing.cancelBeforeWin());
                assertEquals(TcpSelectOperation.Phase.CANCELLED, losing.phase());
                assertThrows(IllegalStateException.class, losing::commit);

                ByteBuffer direct = ByteBuffer.allocate(payload.length);
                assertEquals(payload.length, readEventually(server, direct));
                assertArrayEquals(payload, direct.array(),
                        "losing selectable read must leave kernel bytes untouched");
            }
        }
    }

    @Test
    void winningReadCommitsOnlyAfterWinnerReservation() throws Exception {
        try (var listener = ServerSocketChannel.open();
             var client = SocketChannel.open()) {
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            client.connect(listener.getLocalAddress());
            try (var server = acceptEventually(listener)) {
                byte[] payload = {1, 2, 3};
                client.write(ByteBuffer.wrap(payload));

                var op = new TcpSelectOperation.Read(server, 16);
                assertThrows(IllegalStateException.class, op::commit);
                assertTrue(op.markReady());
                assertTrue(op.tryWin());
                TcpSelectOperation.Commit<byte[]> committed;
                do {
                    committed = op.commit();
                    if (committed.status() == TcpSelectOperation.CommitStatus.REARM)
                        Thread.sleep(1);
                } while (committed.status() == TcpSelectOperation.CommitStatus.REARM);
                assertArrayEquals(payload, committed.value());
                assertEquals(payload.length, committed.bytesCommitted());
                assertFalse(op.cancelBeforeWin());
                assertEquals(TcpSelectOperation.Phase.COMMITTED, op.phase());
            }
        }
    }

    @Test
    void losingWriteDescriptorEmitsNoBytes() throws Exception {
        try (var listener = ServerSocketChannel.open();
             var client = SocketChannel.open()) {
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            client.connect(listener.getLocalAddress());
            client.configureBlocking(false);
            try (var server = acceptEventually(listener)) {
                byte[] payload = {9, 8, 7, 6};
                var losing = new TcpSelectOperation.Write(server, payload);
                assertTrue(losing.markReady());
                assertTrue(losing.cancelBeforeWin());

                ByteBuffer none = ByteBuffer.allocate(payload.length);
                assertEquals(0, client.read(none),
                        "losing selectable write must not emit bytes");

                var winner = new TcpSelectOperation.Write(server, payload);
                assertTrue(winner.markReady());
                assertTrue(winner.tryWin());
                TcpSelectOperation.Commit<Integer> committed;
                do {
                    committed = winner.commit();
                    if (committed.status() == TcpSelectOperation.CommitStatus.REARM)
                        Thread.sleep(1);
                } while (committed.status() == TcpSelectOperation.CommitStatus.REARM);
                assertEquals(payload.length, committed.value());

                ByteBuffer received = ByteBuffer.allocate(payload.length);
                assertEquals(payload.length, readEventually(client, received));
                assertTrue(Arrays.equals(payload, received.array()));
            }
        }
    }

    @Test
    void losingAcceptDescriptorDoesNotClaimConnection() throws Exception {
        try (var listener = ServerSocketChannel.open()) {
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));

            try (var client = SocketChannel.open()) {
                client.connect(listener.getLocalAddress());
                var losing = new TcpSelectOperation.Accept(listener);
                assertTrue(losing.markReady());
                assertTrue(losing.cancelBeforeWin());
                try (var accepted = acceptEventually(listener)) {
                    assertNotNull(accepted,
                            "losing selectable accept must leave pending connection untouched");
                }
            }

            try (var client = SocketChannel.open()) {
                client.connect(listener.getLocalAddress());
                var winner = new TcpSelectOperation.Accept(listener);
                assertTrue(winner.markReady());
                assertTrue(winner.tryWin());
                TcpSelectOperation.Commit<SocketChannel> committed;
                do {
                    committed = winner.commit();
                    if (committed.status() == TcpSelectOperation.CommitStatus.REARM)
                        Thread.sleep(1);
                } while (committed.status() == TcpSelectOperation.CommitStatus.REARM);
                try (var accepted = committed.value()) {
                    assertNotNull(accepted);
                    assertEquals(TcpSelectOperation.Phase.COMMITTED, winner.phase());
                }
            }
        }
    }
}
