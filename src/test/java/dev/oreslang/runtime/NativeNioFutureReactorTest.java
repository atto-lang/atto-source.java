package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class NativeNioFutureReactorTest {
    @Test void futuresBridgeReadWriteAndPartialPeerTraffic() throws Exception {
        try (NativeNioFutureReactor reactor = NativeNioFutureReactor.loopback(0, 32, 8, 64)) {
            var accepted = reactor.acceptAsync();
            try (Socket peer = new Socket(InetAddress.getLoopbackAddress(), reactor.localPort())) {
                peer.setSoTimeout(2000);
                var connection = accepted.get(2, TimeUnit.SECONDS);
                var reading = connection.readAsync(1024);
                peer.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
                assertEquals("ping", new String(reading.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8));
                byte[] outbound = "pong".getBytes(StandardCharsets.UTF_8);
                var writing = connection.writeAsync(outbound);
                outbound[0] = 'X'; // producer mutations do not leak across ownership boundary
                assertEquals(4, writing.get(2, TimeUnit.SECONDS));
                assertEquals("pong", new String(peer.getInputStream().readNBytes(4), StandardCharsets.UTF_8));
                assertTrue(reactor.maxCommandQueueNanos() >= 0);
                connection.closeAsync().get(2, TimeUnit.SECONDS);
            }
            assertEquals(0, reactor.outstandingFutures());
        }
    }

    @Test void selectedSocketBurstBeyondOneQuantumMakesProgress() throws Exception {
        // One reactor, 144 simultaneously readable sockets, 128 ready events
        // per quantum. Leftover keys must not wait for another kernel edge.
        try (var reactor = NativeNioFutureReactor.loopback(0, 256, 160, 384)) {
            var peers = new java.util.ArrayList<Socket>();
            var connections = new java.util.ArrayList<NativeNioFutureReactor.Connection>();
            var reads = new java.util.ArrayList<OresFuture<byte[]>>();
            try {
                for (int i = 0; i < 144; ++i) {
                    var accepted = reactor.acceptAsync();
                    var peer = new Socket(InetAddress.getLoopbackAddress(), reactor.localPort());
                    peers.add(peer);
                    var connection = accepted.get(5, TimeUnit.SECONDS);
                    connections.add(connection);
                    reads.add(connection.readAsync(1));
                }
                for (var peer : peers) peer.getOutputStream().write(0x41);
                for (var read : reads)
                    assertArrayEquals(new byte[] {65}, read.get(5, TimeUnit.SECONDS));
                for (var connection : connections)
                    connection.closeAsync().get(5, TimeUnit.SECONDS);
                assertEquals(0, reactor.outstandingFutures());
            } finally {
                for (var peer : peers) peer.close();
            }
        }
    }

    @Test void stalledSocketDoesNotBlockOtherConnectionOrClose() throws Exception {
        try (NativeNioFutureReactor reactor = NativeNioFutureReactor.loopback(0, 32, 2, 16)) {
            var firstAccept = reactor.acceptAsync();
            try (Socket stalled = new Socket(InetAddress.getLoopbackAddress(), reactor.localPort())) {
                var first = firstAccept.get(2, TimeUnit.SECONDS);
                var waitingRead = first.readAsync(4096);
                assertFalse(waitingRead.isDone(), "stalled socket must suspend, not block reactor");
                var secondAccept = reactor.acceptAsync();
                try (Socket secondPeer = new Socket(InetAddress.getLoopbackAddress(), reactor.localPort())) {
                    var second = secondAccept.get(2, TimeUnit.SECONDS);
                    var secondRead = second.readAsync(10);
                    secondPeer.getOutputStream().write("ok".getBytes(StandardCharsets.UTF_8));
                    assertEquals("ok", new String(secondRead.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8));
                    second.closeAsync().get(2, TimeUnit.SECONDS);
                }
                first.closeAsync().get(2, TimeUnit.SECONDS);
                assertThrows(ExecutionException.class, () -> waitingRead.get(2, TimeUnit.SECONDS));
            }
            assertEquals(0, reactor.outstandingFutures());
        }
    }

    @Test void boundedAcceptanceAndDuplicateReadFailClosed() throws Exception {
        try (NativeNioFutureReactor reactor = NativeNioFutureReactor.loopback(0, 8, 1, 2)) {
            var firstAccept = reactor.acceptAsync();
            var secondAccept = reactor.acceptAsync();
            var refused = reactor.acceptAsync();
            var rejection = assertThrows(ExecutionException.class,
                    () -> refused.get(2, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, rejection.getCause());
            assertEquals(2, reactor.peakOutstandingFutures());
            try (Socket peer = new Socket(InetAddress.getLoopbackAddress(), reactor.localPort())) {
                var connection = firstAccept.get(2, TimeUnit.SECONDS);
                var initial = connection.readAsync(4);
                var duplicated = connection.readAsync(4);
                assertThrows(ExecutionException.class, () -> duplicated.get(2, TimeUnit.SECONDS));
                peer.getOutputStream().write('a');
                assertEquals("a", new String(initial.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8));
                connection.closeAsync().get(2, TimeUnit.SECONDS);
            }
            // The unfulfilled second accept remains pending and must fail on close.
            reactor.close();
            assertThrows(ExecutionException.class, () -> secondAccept.get(2, TimeUnit.SECONDS));
            assertTrue(reactor.awaitTerminated(2000));
            assertEquals(0, reactor.outstandingFutures());
        }
    }

    @Test void abruptKernelSelectorLossSettlesEveryAdmittedFuture() throws Exception {
        var reactor = NativeNioFutureReactor.loopback(0, 4, 2, 32);
        try {
            var waiting = new java.util.ArrayList<OresFuture<NativeNioFutureReactor.Connection>>();
            for (int i = 0; i < 16; i++) waiting.add(reactor.acceptAsync());
            // Adversarial host failure: closing the selector without normal
            // reactor shutdown must still settle every admitted operation.
            var field = NativeNioFutureReactor.class.getDeclaredField("selector");
            field.setAccessible(true);
            ((java.nio.channels.Selector) field.get(reactor)).close();
            assertTrue(reactor.awaitTerminated(2000), "selector loss must terminate reactor");
            for (var future : waiting) {
                assertTrue(future.isDone(), "selector loss stranded a guest Future");
                assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            }
            assertEquals(0, reactor.outstandingFutures());
            assertTrue(reactor.closeAsync().isDone());
        } finally {
            reactor.close();
        }
    }

    @Test void concurrentShutdownCannotStrandAdmittedFutures() throws Exception {
        for (int round = 0; round < 40; round++) {
            var reactor = NativeNioFutureReactor.loopback(0, 4, 2, 16);
            try {
                var admitted = new java.util.concurrent.CopyOnWriteArrayList<OresFuture<?>>();
                try (ExecutorService submitters = Executors.newFixedThreadPool(2)) {
                    var submit = submitters.submit(() -> {
                        for (int i = 0; i < 32; i++) admitted.add(reactor.acceptAsync());
                    });
                    var stop = submitters.submit(reactor::close);
                    submit.get(2, TimeUnit.SECONDS);
                    stop.get(2, TimeUnit.SECONDS);
                }
                assertTrue(reactor.awaitTerminated(2000), "reactor must terminate");
                for (var future : admitted) {
                    assertTrue(future.isDone(), "admitted future stranded after reactor shutdown");
                }
                assertEquals(0, reactor.outstandingFutures(), "shutdown must release admission");
                var after = reactor.acceptAsync();
                assertThrows(ExecutionException.class, () -> after.get(2, TimeUnit.SECONDS));
                assertEquals(0, reactor.outstandingFutures());
            } finally {
                reactor.close();
            }
        }
    }

    @Test void cancelledReadCannotConsumeFutureSocketBytes() throws Exception {
        try (var reactor = NativeNioFutureReactor.loopback(0, 8, 1, 8)) {
            var admission = reactor.acceptAsync();
            try (Socket peer = new Socket(InetAddress.getLoopbackAddress(), reactor.localPort())) {
                var connection = admission.get(2, TimeUnit.SECONDS);
                var cancelled = connection.readAsync(64);
                assertTrue(cancelled.cancel(false), "pending non-consuming read can be cancelled");
                assertTrue(cancelled.isCancelled());
                // A fresh read queued after cancellation must receive the bytes.
                var receiving = connection.readAsync(64);
                peer.getOutputStream().write("preserved".getBytes(StandardCharsets.US_ASCII));
                assertEquals("preserved", new String(receiving.get(2, TimeUnit.SECONDS),
                        StandardCharsets.US_ASCII));
                connection.closeAsync().get(2, TimeUnit.SECONDS);
            }
            assertEquals(0, reactor.outstandingFutures());
        }
    }

    @Test void guestByteArrayFutureCancellationRevokesUnderlyingRead() throws Exception {
        try (var reactor = NativeNioFutureReactor.loopback(0, 8, 1, 8)) {
            var accepted = reactor.acceptAsync();
            try (Socket peer = new Socket(InetAddress.getLoopbackAddress(), reactor.localPort())) {
                var connection = accepted.get(2, TimeUnit.SECONDS);
                var abandoned = connection.readBytesAsync(64);
                assertTrue(abandoned.cancel(false),
                        "mapped Array<int> cancellation must revoke raw kernel read");
                assertTrue(abandoned.isCancelled());
                var next = connection.readBytesAsync(64);
                peer.getOutputStream().write("raw".getBytes(StandardCharsets.US_ASCII));
                assertEquals(java.util.List.of(114L, 97L, 119L), next.get(2, TimeUnit.SECONDS));
                connection.closeAsync().get(2, TimeUnit.SECONDS);
            }
            assertEquals(0, reactor.outstandingFutures());
        }
    }

    @Test void cancelledAcceptDoesNotClaimIncomingSocket() throws Exception {
        try (var reactor = NativeNioFutureReactor.loopback(0, 8, 1, 8)) {
            var first = reactor.acceptAsync();
            assertTrue(first.cancel(false));
            var next = reactor.acceptAsync();
            try (Socket peer = new Socket(InetAddress.getLoopbackAddress(), reactor.localPort())) {
                var connection = next.get(2, TimeUnit.SECONDS);
                assertTrue(first.isCancelled());
                connection.closeAsync().get(2, TimeUnit.SECONDS);
            }
            assertEquals(0, reactor.outstandingFutures());
        }
    }

    @Test void cancelledAcceptAndReadDoNotLeakAdmissionUnderRepeatedRaces() throws Exception {
        for (int round = 0; round < 30; round++) {
            try (var reactor = NativeNioFutureReactor.loopback(0, 4, 1, 12)) {
                var cancelled = new java.util.ArrayList<OresFuture<?>>();
                for (int i = 0; i < 8; i++) {
                    var request = reactor.acceptAsync();
                    assertTrue(request.cancel(false));
                    cancelled.add(request);
                }
                // The subsequent I/O must not stall behind cancelled accepts.
                var accepted = reactor.acceptAsync();
                try (Socket peer = new Socket(InetAddress.getLoopbackAddress(), reactor.localPort())) {
                    var connection = accepted.get(2, TimeUnit.SECONDS);
                    for (int i = 0; i < 4; i++) {
                        var read = connection.readAsync(10);
                        assertTrue(read.cancel(false));
                        cancelled.add(read);
                    }
                    var latest = connection.readAsync(10);
                    peer.getOutputStream().write('q');
                    assertEquals("q", new String(latest.get(2, TimeUnit.SECONDS),
                            StandardCharsets.US_ASCII));
                    connection.closeAsync().get(2, TimeUnit.SECONDS);
                }
                reactor.close();
                assertTrue(reactor.awaitTerminated(2000));
                for (var request : cancelled) assertTrue(request.isCancelled());
                assertEquals(0, reactor.outstandingFutures());
            }
        }
    }

    @Test void outstandingAcceptSettlesOnShutdownWithoutBlockingCaller() throws Exception {
        var reactor = NativeNioFutureReactor.loopback(0, 2, 1, 1);
        try {
            var waiting = reactor.acceptAsync();
            assertTrue(waiting.cancel(false), "pending accept cancellation must win before kernel accept");
            assertTrue(waiting.isCancelled());
            reactor.close(); // wake selector, never join on actor carrier
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> waiting.get(2, TimeUnit.SECONDS));
            assertTrue(reactor.awaitTerminated(2000));
            assertEquals(0, reactor.outstandingFutures());
        } finally {
            reactor.close();
        }
    }
}
