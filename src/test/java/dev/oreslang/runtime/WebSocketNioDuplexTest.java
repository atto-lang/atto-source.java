package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real localhost SocketChannel wire tests. No host-only pass is guest proof.
 * Each test has a finite client read timeout and a finite NIO operation deadline.
 */
@Timeout(10)
final class WebSocketNioDuplexTest {
    private record Pair(ServerSocketChannel server, SocketChannel client,
            SocketChannel accepted) implements AutoCloseable {
        @Override public void close() throws IOException {
            try { accepted.close(); }
            finally {
                try { client.close(); }
                finally { server.close(); }
            }
        }
    }

    private static Pair pair() throws Exception {
        ServerSocketChannel server = ServerSocketChannel.open();
        try {
            server.bind(new InetSocketAddress("127.0.0.1", 0), 1);
            SocketChannel client = SocketChannel.open(
                    new InetSocketAddress("127.0.0.1",
                            server.socket().getLocalPort()));
            try {
                SocketChannel accepted = server.accept();
                accepted.configureBlocking(false);
                client.socket().setSoTimeout(2000);
                return new Pair(server, client, accepted);
            } catch (Throwable failure) {
                client.close();
                throw failure;
            }
        } catch (Throwable failure) {
            server.close();
            throw failure;
        }
    }

    @Test
    void writesCanonicalUnmaskedExtendedLengthWithoutOutputStream() throws Exception {
        try (Pair p = pair()) {
            byte[] payload = new byte[200];
            Arrays.fill(payload, (byte) 0x5a);
            WebSocketNioDuplex.writeServerFrame(
                    p.accepted(), true, WebSocketFrameCodec.OPCODE_BINARY,
                    payload, 1024, 750);
            byte[] actual = p.client().socket().getInputStream().readNBytes(204);
            assertEquals(204, actual.length);
            assertEquals(0x82, actual[0] & 0xff);
            assertEquals(126, actual[1] & 0xff);
            assertEquals(0, actual[2] & 0xff);
            assertEquals(200, actual[3] & 0xff);
            assertArrayEquals(payload, Arrays.copyOfRange(actual, 4, 204));
        }
    }

    @Test
    void nonblockingReaderAcceptsMaskedFrameSplitAcrossWrites() throws Exception {
        try (Pair p = pair()) {
            var awaited = CompletableFuture.supplyAsync(() -> {
                try {
                    return WebSocketNioDuplex.readClientFrame(p.accepted(), 1024, 1000);
                } catch (IOException ex) {
                    throw new java.util.concurrent.CompletionException(ex);
                }
            });
            byte[] encoded = {
                    (byte) 0x81, (byte) 0x85, 1, 2, 3, 4,
                    (byte) ('h' ^ 1), (byte) ('e' ^ 2),
                    (byte) ('l' ^ 3), (byte) ('l' ^ 4),
                    (byte) ('o' ^ 1)
            };
            p.client().socket().getOutputStream().write(encoded, 0, 4);
            p.client().socket().getOutputStream().flush();
            Thread.sleep(30);
            p.client().socket().getOutputStream().write(encoded, 4, 7);
            var frame = awaited.get(2, TimeUnit.SECONDS);
            assertEquals(WebSocketFrameCodec.OPCODE_TEXT, frame.opcode());
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), frame.payload());
        }
    }

    @Test
    void readDeadlineIsAbsoluteAndDoesNotResetOnPartialFrame() throws Exception {
        try (Pair p = pair()) {
            p.client().socket().getOutputStream().write(new byte[] {
                    (byte) 0x81, (byte) 0x85, 1, 2, 3, 4, (byte) ('h' ^ 1)
            });
            assertThrows(SocketTimeoutException.class,
                    () -> WebSocketNioDuplex.readClientFrame(
                            p.accepted(), 1024, 120));
        }
    }

    @Test
    void expired101DeadlineAndInvalidFramesFailClosedBeforeWriting() throws Exception {
        try (Pair p = pair()) {
            assertThrows(SocketTimeoutException.class, () ->
                    WebSocketNioDuplex.writeOpeningResponse(p.accepted(),
                            "HTTP/1.1 101\r\n\r\n".getBytes(StandardCharsets.US_ASCII),
                            System.nanoTime() - 1));
            assertThrows(IllegalArgumentException.class, () ->
                    WebSocketNioDuplex.writeOpeningResponse(
                            p.accepted(), new byte[4097],
                            WebSocketNioDuplex.deadlineAfterMillis(100)));
            assertThrows(IllegalArgumentException.class, () ->
                    WebSocketNioDuplex.writeServerFrame(
                            p.accepted(), true, WebSocketFrameCodec.OPCODE_TEXT,
                            new byte[1025], 1024, 100));
            assertThrows(IllegalArgumentException.class, () ->
                    WebSocketNioDuplex.writeServerFrame(
                            p.accepted(), false, WebSocketFrameCodec.OPCODE_PING,
                            new byte[0], 1024, 100));
        }
    }

    @Test
    void preInterruptedWorkerNeverPublishesAFrameOrConsumesInput() throws Exception {
        try (Pair p = pair()) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(java.nio.channels.ClosedByInterruptException.class,
                        () -> WebSocketNioDuplex.writeServerFrame(
                                p.accepted(), true, WebSocketFrameCodec.OPCODE_BINARY,
                                new byte[] {1}, 125, 1000));
                assertThrows(java.nio.channels.ClosedByInterruptException.class,
                        () -> WebSocketNioDuplex.readClientFrame(
                                p.accepted(), 125, 1000));
            } finally {
                // Do not poison the JUnit worker or try-with-resources cleanup.
                Thread.interrupted();
            }
            p.client().socket().setSoTimeout(100);
            assertThrows(java.net.SocketTimeoutException.class,
                    () -> p.client().socket().getInputStream().read(),
                    "interrupted write must not have emitted a wire header");
        }
    }

    @Test
    void closingChannelDuringPartialFrameUnblocksReadAndNeverDeliversMessage()
            throws Exception {
        try (Pair p = pair()) {
            var ready = new java.util.concurrent.CountDownLatch(1);
            var failure = new CompletableFuture<Throwable>();
            Thread worker = Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    WebSocketNioDuplex.readClientFrame(p.accepted(), 1024, 3000);
                    failure.complete(new AssertionError("partial frame unexpectedly delivered"));
                } catch (Throwable thrown) {
                    failure.complete(thrown);
                }
            });
            assertTrue(ready.await(1, TimeUnit.SECONDS));
            p.client().socket().getOutputStream().write(
                    new byte[] {(byte) 0x81, (byte) 0x85, 1, 2, 3, 4, 7});
            Thread.sleep(40);
            p.accepted().close();
            Throwable result = failure.get(2, TimeUnit.SECONDS);
            assertInstanceOf(IOException.class, result);
            worker.join(1000);
            assertFalse(worker.isAlive(), "closed read worker must retire");
        }
    }

    @Test
    void closingChannelDuringBackpressuredWriteUnblocksWriter() throws Exception {
        try (Pair p = pair()) {
            // Small kernel send buffer plus an undrained peer makes the
            // complete 4 MiB frame exceed the socket's writable capacity.
            p.accepted().socket().setSendBufferSize(1024);
            var ready = new java.util.concurrent.CountDownLatch(1);
            var failure = new CompletableFuture<Throwable>();
            Thread worker = Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    WebSocketNioDuplex.writeServerFrame(
                            p.accepted(), true, WebSocketFrameCodec.OPCODE_BINARY,
                            new byte[4 * 1024 * 1024], 4 * 1024 * 1024, 3000);
                    failure.complete(new AssertionError("undrained write unexpectedly completed"));
                } catch (Throwable thrown) {
                    failure.complete(thrown);
                }
            });
            assertTrue(ready.await(1, TimeUnit.SECONDS));
            Thread.sleep(40);
            p.accepted().close();
            Throwable result = failure.get(2, TimeUnit.SECONDS);
            assertInstanceOf(IOException.class, result);
            worker.join(1000);
            assertFalse(worker.isAlive(), "closed write worker must retire");
        }
    }

    @Test
    void closedChannelFailsWithoutRevivingPhysicalAuthority() throws Exception {
        try (Pair p = pair()) {
            p.accepted().close();
            assertThrows(IOException.class, () ->
                    WebSocketNioDuplex.writeServerFrame(
                            p.accepted(), true, WebSocketFrameCodec.OPCODE_BINARY,
                            new byte[] {1}, 125, 100));
            assertThrows(IOException.class, () ->
                    WebSocketNioDuplex.readClientFrame(p.accepted(), 125, 100));
        }
    }
}
