package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.ClosedChannelException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import javax.net.ssl.SSLContext;

@Timeout(15)
final class SelectorTransportConnectionTest {
    @Test
    void concurrentCloseDuringConnectNeverLeaksUncheckedNioFailure()
            throws Exception {
        for (int iteration = 0; iteration < 64; iteration++) {
            exerciseCloseDuringConnect();
        }
    }

    private static void exerciseCloseDuringConnect()
            throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch releasePeer = new CountDownLatch(1);
        AtomicReference<Throwable> peerFailure =
                new AtomicReference<>();

        try (ServerSocket server = new ServerSocket(
                0,
                50,
                InetAddress.getLoopbackAddress())) {
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    accepted.countDown();
                    releasePeer.await();
                } catch (Throwable failure) {
                    peerFailure.set(failure);
                }
            });

            long overallDeadline =
                    System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(5);
            SelectorTransportConnection connection =
                    SelectorTransportConnection.open(overallDeadline);
            AtomicReference<Throwable> connectorFailure =
                    new AtomicReference<>();
            Thread connector = Thread.ofVirtual().start(() -> {
                try {
                    connection.connect(
                            new InetSocketAddress(
                                    InetAddress.getLoopbackAddress(),
                                    server.getLocalPort()),
                            Duration.ofSeconds(2));
                } catch (Throwable failure) {
                    connectorFailure.set(failure);
                }
            });

            try {
                assertTrue(accepted.await(2, TimeUnit.SECONDS));
                connection.close();
                connector.join(TimeUnit.SECONDS.toMillis(2));
                assertFalse(
                        connector.isAlive(),
                        "connect waiter must retire promptly on close");
                Throwable failure = connectorFailure.get();
                if (failure != null) {
                    assertInstanceOf(
                            IOException.class,
                            failure,
                            "connect close race must stay on the checked I/O path");
                }
            } finally {
                connection.close();
                releasePeer.countDown();
                connector.join(TimeUnit.SECONDS.toMillis(2));
                peer.join(TimeUnit.SECONDS.toMillis(2));
            }

            Throwable failure = peerFailure.get();
            if (failure != null
                    && !(failure instanceof InterruptedException)) {
                fail("loopback peer failed", failure);
            }
        }
    }

    @Test
    void concurrentCloseDuringSelectorReadNeverLeaksUncheckedNioFailure()
            throws Exception {
        for (int iteration = 0; iteration < 16; iteration++) {
            exerciseCloseDuringRead();
        }
    }

    private static void exerciseCloseDuringRead()
            throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch releasePeer = new CountDownLatch(1);
        AtomicReference<Throwable> peerFailure =
                new AtomicReference<>();

        try (ServerSocket server = new ServerSocket(
                0,
                50,
                InetAddress.getLoopbackAddress())) {
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    accepted.countDown();
                    releasePeer.await();
                } catch (Throwable failure) {
                    peerFailure.set(failure);
                }
            });

            long overallDeadline =
                    System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(5);
            SelectorTransportConnection connection =
                    SelectorTransportConnection.open(overallDeadline);
            try {
                connection.connect(
                        new InetSocketAddress(
                                InetAddress.getLoopbackAddress(),
                                server.getLocalPort()),
                        Duration.ofSeconds(2));
                assertTrue(accepted.await(2, TimeUnit.SECONDS));
                connection.setReadTimeout(Duration.ofSeconds(5));

                AtomicReference<Throwable> readerFailure =
                        new AtomicReference<>();
                CountDownLatch readerStarted =
                        new CountDownLatch(1);
                Thread reader = Thread.ofVirtual().start(() -> {
                    readerStarted.countDown();
                    try {
                        connection.input().read();
                    } catch (Throwable failure) {
                        readerFailure.set(failure);
                    }
                });

                assertTrue(
                        readerStarted.await(
                                2,
                                TimeUnit.SECONDS));
                Thread.sleep(10);
                connection.close();

                reader.join(TimeUnit.SECONDS.toMillis(2));
                assertFalse(
                        reader.isAlive(),
                        "selector waiter must retire promptly on close");
                Throwable failure = readerFailure.get();
                assertNotNull(
                        failure,
                        "close must revoke the blocked read");
                assertInstanceOf(
                        IOException.class,
                        failure,
                        "close race must stay on the checked I/O path");
            } finally {
                connection.close();
                releasePeer.countDown();
                peer.join(TimeUnit.SECONDS.toMillis(2));
            }

            Throwable failure = peerFailure.get();
            if (failure != null
                    && !(failure instanceof InterruptedException)) {
                fail("loopback peer failed", failure);
            }
        }
    }

    @Test
    void concurrentReadWriteAndPhaseChangesCannotAliasSelectorState()
            throws Exception {
        CountDownLatch releasePeer = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(
                0, 50, InetAddress.getLoopbackAddress())) {
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    releasePeer.await();
                } catch (Exception ignored) {
                    // Socket shutdown is permitted while the peer is waiting.
                }
            });
            SelectorTransportConnection connection =
                    SelectorTransportConnection.open(
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(6));
            AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
            Thread owner = null;
            try {
                InetSocketAddress address = new InetSocketAddress(
                        InetAddress.getLoopbackAddress(),
                        server.getLocalPort());
                connection.connect(address, Duration.ofSeconds(2));
                connection.setReadTimeout(Duration.ofSeconds(5));
                owner = Thread.ofVirtual().start(() -> {
                    try {
                        connection.input().read();
                    } catch (Throwable failure) {
                        ownerFailure.set(failure);
                    }
                });
                // Read private admission state only for deterministic test
                // synchronization; it is never a public capability or lease.
                Field field = SelectorTransportConnection.class
                        .getDeclaredField("operationClaimed");
                field.setAccessible(true);
                AtomicBoolean claimed = (AtomicBoolean) field.get(connection);
                long deadline = System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(2);
                while (!claimed.get() && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertTrue(claimed.get(), "read must acquire the selector gate");

                IOException secondReader = assertThrows(IOException.class,
                        () -> connection.input().read(new byte[1]));
                assertTrue(secondReader.getMessage().contains("exclusive transport lease"));
                IOException competingWriter = assertThrows(IOException.class,
                        () -> connection.output().write(new byte[] { 7 }));
                assertTrue(competingWriter.getMessage().contains("exclusive transport lease"));
                IOException competingConnect = assertThrows(IOException.class,
                        () -> connection.connect(address, Duration.ofSeconds(1)));
                assertTrue(competingConnect.getMessage().contains("exclusive transport lease"));
                assertTrue(claimed.get(), "failed claims must not release another operation");

                IOException competingTls = assertThrows(IOException.class,
                        () -> connection.startTls(
                                SSLContext.getDefault(), "localhost",
                                server.getLocalPort(), false, Duration.ofSeconds(1)));
                assertTrue(competingTls.getMessage().contains("exclusive transport lease"));
                assertFalse(connection.tls(), "competing TLS must not install an engine");

                List<Thread> contenders = new ArrayList<>();
                AtomicInteger denied = new AtomicInteger();
                AtomicReference<Throwable> unexpected = new AtomicReference<>();
                CountDownLatch raceStart = new CountDownLatch(1);
                for (int iteration = 0; iteration < 32; iteration++) {
                    final boolean read = (iteration & 1) == 0;
                    contenders.add(Thread.ofVirtual().start(() -> {
                        try {
                            raceStart.await();
                            if (read) connection.input().read(new byte[1]);
                            else connection.output().write(new byte[] { 7 });
                            unexpected.compareAndSet(null,
                                    new AssertionError("overlapping I/O admitted"));
                        } catch (IOException io) {
                            if (io.getMessage() != null
                                    && io.getMessage().contains("exclusive transport lease")) {
                                denied.incrementAndGet();
                            } else {
                                unexpected.compareAndSet(null, io);
                            }
                        } catch (Throwable failure) {
                            unexpected.compareAndSet(null, failure);
                        }
                    }));
                }
                raceStart.countDown();
                for (Thread contender : contenders) {
                    contender.join(TimeUnit.SECONDS.toMillis(2));
                    assertFalse(contender.isAlive(), "contenders must fail promptly");
                }
                assertNull(unexpected.get(), "no concurrent caller may enter");
                assertEquals(32, denied.get(), "each contender must fail closed");
                assertTrue(claimed.get(), "contenders must not steal owner claim");
                connection.close();
                owner.join(TimeUnit.SECONDS.toMillis(2));
                assertFalse(owner.isAlive(), "close must wake active selector owner");
                assertInstanceOf(IOException.class, ownerFailure.get());
                assertFalse(claimed.get(), "operation claim must release on unwind");
            } finally {
                connection.close();
                releasePeer.countDown();
                if (owner != null) owner.join(TimeUnit.SECONDS.toMillis(2));
                peer.join(TimeUnit.SECONDS.toMillis(2));
            }
        }
    }

    @Test
    void selectorDeadlinesSaturateAcrossSignedNanoTimeOrigins() {
        // A nanoTime origin can be negative; the old MAX-now guard could
        // overflow and produce a timeout well before the requested deadline.
        assertEquals(Long.MIN_VALUE + 43,
                SelectorTransportConnection.saturatingDeadline(
                        Long.MIN_VALUE + 3, 40));
        assertEquals(Long.MAX_VALUE,
                SelectorTransportConnection.saturatingDeadline(
                        Long.MAX_VALUE - 5, 40));
        assertEquals(Long.MAX_VALUE,
                SelectorTransportConnection.saturatingRemaining(
                        Long.MAX_VALUE, -42));
        assertEquals(Long.MAX_VALUE,
                SelectorTransportConnection.saturatingRemaining(
                        Long.MAX_VALUE - 1, -42));
        assertEquals(Long.MIN_VALUE,
                SelectorTransportConnection.saturatingRemaining(
                        Long.MIN_VALUE, 42));
        assertEquals(2_000_000_000L,
                SelectorTransportConnection.saturatingRemaining(
                        3_000_000_000L, 1_000_000_000L));
        assertThrows(IllegalArgumentException.class,
                () -> SelectorTransportConnection.saturatingDeadline(1, -1));
    }

    @Test
    void exclusiveOperationGateAllowsSequentialRoundTrip()
            throws Exception {
        try (ServerSocket server = new ServerSocket(
                0, 50, InetAddress.getLoopbackAddress())) {
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    int value = socket.getInputStream().read();
                    socket.getOutputStream().write(value + 1);
                } catch (IOException error) {
                    throw new AssertionError(error);
                }
            });
            try (SelectorTransportConnection connection =
                    SelectorTransportConnection.open(
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(5))) {
                connection.connect(
                        new InetSocketAddress(
                                InetAddress.getLoopbackAddress(), server.getLocalPort()),
                        Duration.ofSeconds(2));
                connection.output().write(41);
                assertEquals(42, connection.input().read());
            } finally {
                peer.join(TimeUnit.SECONDS.toMillis(2));
                assertFalse(peer.isAlive());
            }
        }
    }

    @Test
    void fragmentedLoopbackResponseDoesNotAssumeTcpMessageBoundaries()
            throws Exception {
        CountDownLatch secondFragment = new CountDownLatch(1);
        AtomicReference<Throwable> peerFailure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(
                0, 50, InetAddress.getLoopbackAddress())) {
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    if (socket.getInputStream().read() != 7)
                        throw new AssertionError("unexpected request byte");
                    socket.getOutputStream().write(new byte[] {0, 0});
                    socket.getOutputStream().flush();
                    if (!secondFragment.await(2, TimeUnit.SECONDS))
                        throw new AssertionError("second fragment not released");
                    socket.getOutputStream().write(new byte[] {0, 42});
                    socket.getOutputStream().flush();
                } catch (Throwable failure) {
                    peerFailure.set(failure);
                }
            });
            try (SelectorTransportConnection connection =
                    SelectorTransportConnection.open(
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(6))) {
                connection.connect(new InetSocketAddress(
                        InetAddress.getLoopbackAddress(), server.getLocalPort()),
                        Duration.ofSeconds(2));
                connection.setReadTimeout(Duration.ofSeconds(3));
                connection.output().write(7);
                byte[] payload = new byte[4];
                int offset = connection.input().read(payload, 0, 4);
                assertTrue(offset > 0 && offset <= 2,
                        "first read cannot fabricate the missing fragment");
                secondFragment.countDown();
                while (offset < payload.length) {
                    int count = connection.input().read(
                            payload, offset, payload.length - offset);
                    assertTrue(count > 0, "premature EOF during fragmented reply");
                    offset += count;
                }
                assertArrayEquals(new byte[] {0, 0, 0, 42}, payload);
            } finally {
                secondFragment.countDown();
                peer.join(TimeUnit.SECONDS.toMillis(2));
                assertFalse(peer.isAlive());
            }
            assertNull(peerFailure.get());
        }
    }

    @Test
    void readTimeoutReleasesExclusiveClaimForNextOperation()
            throws Exception {
        CountDownLatch peerMayWrite = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(
                0, 50, InetAddress.getLoopbackAddress())) {
            AtomicReference<Throwable> peerFailure = new AtomicReference<>();
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    peerMayWrite.await();
                    socket.getOutputStream().write(42);
                } catch (Throwable failure) {
                    peerFailure.set(failure);
                }
            });
            try (SelectorTransportConnection connection =
                    SelectorTransportConnection.open(
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(5))) {
                connection.connect(new InetSocketAddress(
                        InetAddress.getLoopbackAddress(), server.getLocalPort()),
                        Duration.ofSeconds(2));
                connection.setReadTimeout(Duration.ofMillis(30));
                assertThrows(SocketTimeoutException.class,
                        () -> connection.input().read(new byte[1]));
                assertFalse(claimOf(connection).get(),
                        "read timeout must release exclusive claim");
                connection.setReadTimeout(Duration.ofSeconds(2));
                peerMayWrite.countDown();
                assertEquals(42, connection.input().read(),
                        "new read can proceed after prior read times out");
            } finally {
                peerMayWrite.countDown();
                peer.join(TimeUnit.SECONDS.toMillis(2));
                assertFalse(peer.isAlive());
            }
            assertNull(peerFailure.get());
        }
    }

    @Test
    void closeRejectsNewOperationsWithoutResurrectingExclusiveClaim()
            throws Exception {
        SelectorTransportConnection connection =
                SelectorTransportConnection.open(
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        connection.close();
        connection.close();
        assertThrows(ClosedChannelException.class,
                () -> connection.input().read(new byte[1]));
        assertThrows(ClosedChannelException.class,
                () -> connection.output().write(new byte[] { 1 }));
        assertThrows(ClosedChannelException.class,
                () -> connection.connect(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 1),
                        Duration.ofSeconds(1)));
        assertThrows(ClosedChannelException.class,
                () -> connection.startTls(
                        SSLContext.getDefault(), "localhost", 443,
                        false, Duration.ofSeconds(1)));
        assertFalse(claimOf(connection).get());
    }

    @Test
    void tlsHandshakeCannotExposeConcurrentReadOrWriteAndCloseRevokesIt()
            throws Exception {
        CountDownLatch releasePeer = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(
                0, 50, InetAddress.getLoopbackAddress())) {
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    releasePeer.await();
                } catch (Exception ignored) {
                    // Closing the transport revokes the pending TLS handshake.
                }
            });
            SelectorTransportConnection connection =
                    SelectorTransportConnection.open(
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            Thread handshaker = null;
            try {
                connection.connect(new InetSocketAddress(
                        InetAddress.getLoopbackAddress(), server.getLocalPort()),
                        Duration.ofSeconds(2));
                AtomicReference<Throwable> failure = new AtomicReference<>();
                handshaker = Thread.ofVirtual().start(() -> {
                    try {
                        connection.startTls(SSLContext.getDefault(),
                                "localhost", server.getLocalPort(),
                                false, Duration.ofSeconds(4));
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                });
                AtomicBoolean claimed = claimOf(connection);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!claimed.get() && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertTrue(claimed.get(), "TLS handshake must own connection state");
                IOException competingRead = assertThrows(IOException.class,
                        () -> connection.input().read(new byte[1]));
                assertTrue(competingRead.getMessage().contains("exclusive transport lease"));
                IOException competingWrite = assertThrows(IOException.class,
                        () -> connection.output().write(new byte[] { 42 }));
                assertTrue(competingWrite.getMessage().contains("exclusive transport lease"));
                connection.close();
                handshaker.join(TimeUnit.SECONDS.toMillis(2));
                assertFalse(handshaker.isAlive(), "close must terminate TLS handshake");
                assertInstanceOf(IOException.class, failure.get());
                assertFalse(claimed.get(), "failed TLS handshake must release admission");
            } finally {
                connection.close();
                releasePeer.countDown();
                if (handshaker != null) handshaker.join(TimeUnit.SECONDS.toMillis(2));
                peer.join(TimeUnit.SECONDS.toMillis(2));
            }
        }
    }


    @Test
    void slowConnectionCannotBlockIndependentConnectionAdmission()
            throws Exception {
        CountDownLatch releaseSlowPeer = new CountDownLatch(1);
        try (ServerSocket slowServer = new ServerSocket(
                0, 50, InetAddress.getLoopbackAddress());
             ServerSocket fastServer = new ServerSocket(
                0, 50, InetAddress.getLoopbackAddress())) {
            Thread slowPeer = Thread.ofVirtual().start(() -> {
                try (Socket socket = slowServer.accept()) {
                    releaseSlowPeer.await();
                } catch (Exception ignored) {
                    // Shutdown may close the slow connection.
                }
            });
            AtomicReference<Throwable> fastFailure = new AtomicReference<>();
            Thread fastPeer = Thread.ofVirtual().start(() -> {
                try (Socket socket = fastServer.accept()) {
                    int input = socket.getInputStream().read();
                    if (input != 13) throw new AssertionError("unexpected request");
                    socket.getOutputStream().write(42);
                } catch (Throwable failure) {
                    fastFailure.set(failure);
                }
            });
            Thread slowReader = null;
            try (SelectorTransportConnection slow =
                    SelectorTransportConnection.open(
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
                 SelectorTransportConnection fast =
                    SelectorTransportConnection.open(
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(5))) {
                slow.connect(new InetSocketAddress(
                        InetAddress.getLoopbackAddress(), slowServer.getLocalPort()),
                        Duration.ofSeconds(2));
                fast.connect(new InetSocketAddress(
                        InetAddress.getLoopbackAddress(), fastServer.getLocalPort()),
                        Duration.ofSeconds(2));
                slow.setReadTimeout(Duration.ofSeconds(4));
                AtomicReference<Throwable> slowFailure = new AtomicReference<>();
                slowReader = Thread.ofVirtual().start(() -> {
                    try { slow.input().read(); }
                    catch (Throwable error) { slowFailure.set(error); }
                });
                AtomicBoolean claimed = claimOf(slow);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!claimed.get() && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertTrue(claimed.get(), "slow socket must hold its own claim");
                fast.output().write(13);
                assertEquals(42, fast.input().read(),
                        "separate socket must not wait on slow socket's gate");
                assertTrue(claimed.get(), "independent socket did not steal slow claim");
                slow.close();
                slowReader.join(TimeUnit.SECONDS.toMillis(2));
                assertFalse(slowReader.isAlive());
                assertInstanceOf(IOException.class, slowFailure.get());
            } finally {
                releaseSlowPeer.countDown();
                if (slowReader != null) slowReader.join(TimeUnit.SECONDS.toMillis(2));
                slowPeer.join(TimeUnit.SECONDS.toMillis(2));
                fastPeer.join(TimeUnit.SECONDS.toMillis(2));
                assertFalse(slowPeer.isAlive());
                assertFalse(fastPeer.isAlive());
            }
            assertNull(fastFailure.get(), "fast peer completed without I/O failures");
        }
    }

    @Test
    void malformedTlsHandshakeRetiresSocketAndCannotReturnItToAPool()
            throws Exception {
        AtomicReference<Throwable> peerFailure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(
                0, 50, InetAddress.getLoopbackAddress())) {
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    // Consume some ClientHello bytes so the peer is not
                    // allowed to report a successful TLS negotiation.
                    socket.setSoTimeout(2000);
                    byte[] header = new byte[5];
                    int received = socket.getInputStream().read(header);
                    if (received <= 0) {
                        throw new AssertionError("ClientHello was not sent");
                    }
                    socket.getOutputStream().write(
                            "HTTP/1.1 400 Bad Request\\r\\n\\r\\n"
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (Throwable failure) {
                    peerFailure.set(failure);
                }
            });
            try (SelectorTransportConnection connection =
                    SelectorTransportConnection.open(
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(6))) {
                connection.connect(new InetSocketAddress(
                        InetAddress.getLoopbackAddress(), server.getLocalPort()),
                        Duration.ofSeconds(2));
                assertThrows(IOException.class,
                        () -> connection.startTls(
                                SSLContext.getDefault(), "localhost",
                                server.getLocalPort(), false,
                                Duration.ofSeconds(3)));
                assertFalse(claimOf(connection).get(),
                        "failed TLS handshake must release selector claim");
                assertThrows(ClosedChannelException.class,
                        () -> connection.input().read(new byte[1]),
                        "TLS handshake failure must poison the physical connection");
                assertThrows(ClosedChannelException.class,
                        () -> connection.output().write(new byte[] { 1 }));
            } finally {
                peer.join(TimeUnit.SECONDS.toMillis(2));
                assertFalse(peer.isAlive());
            }
            assertNull(peerFailure.get());
        }
    }

    private static AtomicBoolean claimOf(SelectorTransportConnection connection)
            throws ReflectiveOperationException {
        Field field = SelectorTransportConnection.class
                .getDeclaredField("operationClaimed");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(connection);
    }
}
