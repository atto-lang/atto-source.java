package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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

    @Test void outstandingAcceptSettlesOnShutdownWithoutBlockingCaller() throws Exception {
        var reactor = NativeNioFutureReactor.loopback(0, 2, 1, 1);
        try {
            var waiting = reactor.acceptAsync();
            assertFalse(waiting.cancel(false), "until cancellation fencing exists cancellation is declined");
            reactor.close(); // wake selector, never join on actor carrier
            assertThrows(ExecutionException.class, () -> waiting.get(2, TimeUnit.SECONDS));
            assertTrue(reactor.awaitTerminated(2000));
            assertEquals(0, reactor.outstandingFutures());
        } finally {
            reactor.close();
        }
    }
}
