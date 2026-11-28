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

/**
 * Real kernel TCP loopback host-harness proof of the ledger's physical close
 * boundary. This is NOT a production shared pool or compiled .ores guest.
 * The host must explicitly retire its socket and acknowledge its physical
 * closure; a revoked actor lease is never itself a close receipt.
 */
@Timeout(15)
final class TcpPoolPhysicalCloseLoopbackTest {
    private static final String PARTITION = "tenant-a|plain|127.0.0.1";
    private static final TcpPoolLeaseLedger.ActorIdentity FIRST =
            new TcpPoolLeaseLedger.ActorIdentity(101, 1);
    private static final TcpPoolLeaseLedger.ActorIdentity SECOND =
            new TcpPoolLeaseLedger.ActorIdentity(102, 1);

    @Test
    void liveTcpCloseAndRearmCannotBeForgedByLeaseRelease()
            throws Exception {
        var ledger = new TcpPoolLeaseLedger(1, PARTITION, 7);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(
                0, 4, InetAddress.getLoopbackAddress())) {
            Thread peer = Thread.ofVirtual().start(() -> {
                try {
                    for (int round = 0; round < 2; round++) {
                        try (Socket accepted = server.accept()) {
                            accepted.setSoTimeout(3000);
                            int request = accepted.getInputStream().read();
                            if (request != 41 + round)
                                throw new AssertionError("wrong request round " + round);
                            accepted.getOutputStream().write(request + 1);
                            accepted.getOutputStream().flush();
                        }
                    }
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });
            try {
                for (int round = 0; round < 2; round++) {
                    var actor = round == 0 ? FIRST : SECOND;
                    var lease = ledger.tryAcquire(0, actor, PARTITION, 7);
                    assertNotNull(lease, "physical slot must be newly armed");
                    assertTrue(ledger.tryBegin(lease, actor, 7));
                    assertNull(ledger.tryAcquire(0, SECOND, PARTITION, 7));
                    try (SelectorTransportConnection connection =
                            SelectorTransportConnection.open(
                                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5))) {
                        connection.connect(new InetSocketAddress(
                                        InetAddress.getLoopbackAddress(),
                                        server.getLocalPort()),
                                Duration.ofSeconds(2));
                        connection.output().write(41 + round);
                        assertEquals(42 + round, connection.input().read());
                        // Cancellation/release never signals socket close.
                        assertTrue(ledger.tryRelease(lease, actor, 7));
                        assertFalse(ledger.tryConfirmPhysicalClose(
                                0, lease.generation() + 1),
                                "cannot acknowledge close while I/O is in flight");
                        assertNull(ledger.tryAcquire(0, SECOND, PARTITION, 7));
                        assertTrue(ledger.tryComplete(lease));
                        assertNull(ledger.tryAcquire(0, SECOND, PARTITION, 7),
                                "completion alone is not physical close");
                    }
                    // Only the trusted host, after its actual channel close,
                    // can certify the next generation for rearming.
                    assertFalse(ledger.tryConfirmPhysicalClose(0, lease.generation()));
                    assertTrue(ledger.tryConfirmPhysicalClose(
                            0, lease.generation() + 1));
                    assertFalse(ledger.tryComplete(lease), "old completion replay");
                    assertFalse(ledger.tryRelease(lease, actor, 7),
                            "old actor capability must be stale");
                }
                assertTrue(ledger.tryStop());
                assertNull(ledger.tryAcquire(0, FIRST, PARTITION, 7));
            } finally {
                peer.join(TimeUnit.SECONDS.toMillis(4));
                assertFalse(peer.isAlive(), "both TCP exchanges must terminate");
            }
            assertNull(serverFailure.get(), "loopback server must finish cleanly");
        }
    }
}
