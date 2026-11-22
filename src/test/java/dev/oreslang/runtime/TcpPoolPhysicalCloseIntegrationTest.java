package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Host-only loopback integration, not compiled Oreslang guest TCP pooling. */
@Timeout(15)
final class TcpPoolPhysicalCloseIntegrationTest {
    private static final String PARTITION = "tenant-a|plain|127.0.0.1";
    private static final TcpPoolLeaseLedger.ActorIdentity A =
            new TcpPoolLeaseLedger.ActorIdentity(101, 1);
    private static final TcpPoolLeaseLedger.ActorIdentity B =
            new TcpPoolLeaseLedger.ActorIdentity(102, 1);

    @Test
    void twoActorsRequirePhysicalSocketCloseBeforeNextGeneration() throws Exception {
        try (ServerSocket server = new ServerSocket(
                0, 50, InetAddress.getLoopbackAddress())) {
            AtomicReference<Throwable> peerError = new AtomicReference<>();
            Thread peer = Thread.ofVirtual().start(() -> {
                try {
                    for (int round = 0; round < 2; round++) {
                        try (Socket remote = server.accept()) {
                            int request = remote.getInputStream().read();
                            remote.getOutputStream().write(request + 1);
                        }
                    }
                } catch (Throwable error) { peerError.set(error); }
            });
            var pool = new TcpPoolLeaseLedger(1, PARTITION, 7);
            try {
                TcpPoolLeaseLedger.Lease previous = null;
                for (int round = 0; round < 2; round++) {
                    var actor = round == 0 ? A : B;
                    var lease = pool.tryAcquire(0, actor, PARTITION, 7);
                    assertNotNull(lease, "physical slot must be ready");
                    if (previous != null) {
                        assertTrue(lease.generation() > previous.generation());
                        assertFalse(pool.tryBegin(previous, A, 7));
                    }
                    var conn = SelectorTransportConnection.open(
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(6));
                    try (conn) {
                        conn.connect(new InetSocketAddress(
                                InetAddress.getLoopbackAddress(), server.getLocalPort()),
                                Duration.ofSeconds(2));
                        assertTrue(pool.tryBegin(lease, actor, 7));
                        conn.output().write(40 + round);
                        assertEquals(41 + round, conn.input().read());
                        assertTrue(pool.tryRelease(lease, actor, 7));
                        assertNull(pool.tryAcquire(0, B, PARTITION, 7),
                                "release must not recycle active socket");
                        assertTrue(pool.tryComplete(lease));
                        assertNull(pool.tryAcquire(0, B, PARTITION, 7),
                                "transport must remain quarantined before close");
                    }
                    // Only the host owner attests AFTER close() on the socket.
                    assertTrue(pool.tryConfirmPhysicalClose(
                            0, lease.generation() + 1));
                    previous = lease;
                }
            } finally {
                assertTrue(pool.tryStop());
                peer.join(TimeUnit.SECONDS.toMillis(2));
                assertFalse(peer.isAlive());
            }
            assertNull(peerError.get());
        }
    }
}
