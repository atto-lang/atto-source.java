package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
}
