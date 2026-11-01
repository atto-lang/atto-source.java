package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
}
