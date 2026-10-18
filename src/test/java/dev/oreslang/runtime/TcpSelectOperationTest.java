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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

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
    void competingCommittersNeverEnterKernelTwice() throws Exception {
        var entered = new CountDownLatch(1);
        var unblock = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var operation = new TcpSelectOperation.Operation<Integer>() {
            @Override TcpSelectOperation.Commit<Integer> commitKernel() throws java.io.IOException {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    if (!unblock.await(3, TimeUnit.SECONDS))
                        throw new java.io.IOException("mock kernel stalled");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(e);
                }
                return new TcpSelectOperation.Commit<>(TcpSelectOperation.CommitStatus.COMPLETE, 1, 1);
            }
        };
        assertTrue(operation.markReady());
        assertTrue(operation.tryWin());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(operation::commit);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(TcpSelectOperation.Phase.COMMITTING, operation.phase());
            assertFalse(operation.cancelBeforeWin());
            assertThrows(IllegalStateException.class, operation::commit,
                    "a second carrier must not concurrently enter the kernel");
            unblock.countDown();
            assertEquals(1, first.get(3, TimeUnit.SECONDS).value());
            assertEquals(1, calls.get());
            assertEquals(TcpSelectOperation.Phase.COMMITTED, operation.phase());
            assertThrows(IllegalStateException.class, operation::commit);
        } finally {
            unblock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void ambiguousIoFailureNeverReplaysPotentiallyCommittedBytes() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var operation = new TcpSelectOperation.Operation<Integer>() {
            @Override TcpSelectOperation.Commit<Integer> commitKernel() throws java.io.IOException {
                calls.incrementAndGet();
                throw new java.io.IOException("mock partial kernel commit");
            }
        };
        assertTrue(operation.markReady());
        assertTrue(operation.tryWin());
        assertThrows(java.io.IOException.class, operation::commit);
        assertEquals(TcpSelectOperation.Phase.COMMITTED, operation.phase());
        assertFalse(operation.cancelBeforeWin());
        assertThrows(IllegalStateException.class, operation::commit);
        assertEquals(1, calls.get());
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
