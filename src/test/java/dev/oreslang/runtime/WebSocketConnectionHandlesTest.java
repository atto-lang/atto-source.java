package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Real loopback integration, NOT evidence of compiled Oreslang source. */
@Timeout(20)
final class WebSocketConnectionHandlesTest {
    private static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    private static int unusedPort() throws Exception {
        try (ServerSocket reservation = new ServerSocket(0, 1,
                InetAddress.getByName("127.0.0.1"))) {
            return reservation.getLocalPort();
        }
    }

    private static WebSocketLoopbackListener listen(AsyncNativeIo io, int port)
            throws Exception {
        return WebSocketLoopbackListener.bind(io, (endpoint, action) -> { },
                port, 4, 2, 1024, 1024, Set.of("http://127.0.0.1:" + port),
                new WebSocketRawUpgrade.Limits(4096, 16, 750));
    }

    private static void upgrade(Socket client, int port) throws Exception {
        client.setSoTimeout(3000);
        String request = "GET /echo HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + KEY + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Origin: http://127.0.0.1:" + port + "\r\n\r\n";
        client.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (head.size() < 4096) {
            int ch = client.getInputStream().read();
            if (ch < 0) break;
            head.write(ch);
            byte[] bytes = head.toByteArray();
            int n = bytes.length;
            if (n >= 4 && bytes[n - 4] == '\r' && bytes[n - 3] == '\n'
                    && bytes[n - 2] == '\r' && bytes[n - 1] == '\n') break;
        }
        assertTrue(head.toString(StandardCharsets.US_ASCII)
                .startsWith("HTTP/1.1 101"));
    }

    private static void assertRetired(WebSocketLoopbackListener listener, Socket client)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (listener.activeConnectionCount() > 0
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, listener.activeConnectionCount());
        assertEquals(-1, client.getInputStream().read());
    }

    @Test
    void boundHandleAllowsOnlyOwnerTurnAndHidesPhysicalHostObjects() throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var runtime = new ActorRuntime();
             var listener = listen(io, port);
             var handles = new WebSocketConnectionHandles(1);
             var foreign = new WebSocketConnectionHandles(1)) {
            AtomicReference<WebSocketConnectionHandles.Handle> id = new AtomicReference<>();
            AtomicReference<OresFuture<Void>> sent = new AtomicReference<>();
            AtomicReference<Throwable> failed = new AtomicReference<>();
            CountDownLatch ownerTurn = new CountDownLatch(1);
            var actor = runtime.<String>spawnPrivate(() -> (message, context) -> {
                try { sent.set(handles.sendTextAsync(id.get(), message)); }
                catch (Throwable failure) { failed.set(failure); }
                finally { ownerTurn.countDown(); }
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            var pending = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                upgrade(client, port);
                var connection = pending.get(3, TimeUnit.SECONDS);
                var handle = handles.claim(connection, runtime, actor);
                id.set(handle);
                assertEquals("WebSocketConnectionHandle(<opaque>)", handle.toString());
                assertThrows(SecurityException.class, () -> foreign.sendTextAsync(handle, "x"));
                assertThrows(SecurityException.class, () -> handles.sendTextAsync(handle, "host"));
                actor.send("actor");
                assertTrue(ownerTurn.await(5, TimeUnit.SECONDS));
                assertNull(failed.get());
                sent.get().get(3, TimeUnit.SECONDS);
                assertArrayEquals(new byte[] {(byte) 0x81, 5, 'a', 'c', 't', 'o', 'r'},
                        client.getInputStream().readNBytes(7));
                actor.stop();
                actor.done().get(5, TimeUnit.SECONDS);
                assertRetired(listener, client);
                assertThrows(SecurityException.class,
                        () -> handles.sendTextAsync(handle, "stale"));
            }
        }
    }

    @Test
    void fullTableDoesNotReuseSlotBeforePhysicalRetirement() throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var runtime = new ActorRuntime();
             var listener = listen(io, port);
             var handles = new WebSocketConnectionHandles(1)) {
            var firstActor = runtime.<String>spawnPrivate(
                    () -> (message, context) -> { });
            var secondActor = runtime.<String>spawnPrivate(
                    () -> (message, context) -> { });
            firstActor.ready().get(5, TimeUnit.SECONDS);
            secondActor.ready().get(5, TimeUnit.SECONDS);
            var firstAccept = listener.acceptAsync();
            try (var first = new Socket("127.0.0.1", port)) {
                upgrade(first, port);
                var firstConnection = firstAccept.get(3, TimeUnit.SECONDS);
                var old = handles.claim(firstConnection, runtime, firstActor);
                var secondAccept = listener.acceptAsync();
                try (var second = new Socket("127.0.0.1", port)) {
                    upgrade(second, port);
                    var secondConnection = secondAccept.get(3, TimeUnit.SECONDS);
                    assertThrows(RejectedExecutionException.class, () ->
                            handles.claim(secondConnection, runtime, secondActor));
                    // Failed table admission never assumes ownership of this
                    // accepted host socket. The supervisor must close it.
                    secondConnection.close();
                    assertEquals(-1, second.getInputStream().read());
                }
                firstActor.stop();
                firstActor.done().get(5, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!firstConnection.isFullyRetired()
                        && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(firstConnection.isFullyRetired(),
                        "slot reuse requires close, permit cleanup, and native loan exit");
                assertEquals(-1, first.getInputStream().read());
                var thirdAccept = listener.acceptAsync();
                try (var third = new Socket("127.0.0.1", port)) {
                    upgrade(third, port);
                    var nextConnection = thirdAccept.get(3, TimeUnit.SECONDS);
                    var next = handles.claim(nextConnection, runtime, secondActor);
                    assertNotSame(old, next);
                    assertThrows(SecurityException.class,
                            () -> handles.sendBinaryAsync(old, new byte[] {1}));
                    secondActor.stop();
                    secondActor.done().get(5, TimeUnit.SECONDS);
                    assertRetired(listener, third);
                }
            }
        }
    }

    @Test
    void ownerRequestedRevokeIsNonblockingAndClosesIdleSocket() throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var runtime = new ActorRuntime();
             var listener = listen(io, port);
             var handles = new WebSocketConnectionHandles(1)) {
            AtomicReference<WebSocketConnectionHandles.Handle> id = new AtomicReference<>();
            AtomicReference<Throwable> failed = new AtomicReference<>();
            CountDownLatch revoked = new CountDownLatch(1);
            var owner = runtime.<String>spawnPrivate(() -> (message, context) -> {
                try { handles.revokeFromOwnerTurn(id.get()); }
                catch (Throwable caught) { failed.set(caught); }
                finally { revoked.countDown(); }
            });
            owner.ready().get(5, TimeUnit.SECONDS);
            var accept = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                upgrade(client, port);
                var connection = accept.get(3, TimeUnit.SECONDS);
                var handle = handles.claim(connection, runtime, owner);
                id.set(handle);
                assertThrows(SecurityException.class, () -> handles.revokeFromOwnerTurn(handle));
                owner.send("close");
                assertTrue(revoked.await(5, TimeUnit.SECONDS));
                assertNull(failed.get());
                assertRetired(listener, client);
                assertThrows(SecurityException.class,
                        () -> handles.sendTextAsync(handle, "after close"));
            }
        }
    }

    @Test
    void closedTableRevokesClaimsWithoutRunningSocketIo() throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var runtime = new ActorRuntime();
             var listener = listen(io, port);
             var handles = new WebSocketConnectionHandles(1)) {
            var owner = runtime.<String>spawnPrivate(
                    () -> (message, context) -> { });
            owner.ready().get(5, TimeUnit.SECONDS);
            var accept = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                upgrade(client, port);
                var connection = accept.get(3, TimeUnit.SECONDS);
                var handle = handles.claim(connection, runtime, owner);
                handles.close();
                handles.close();
                assertThrows(SecurityException.class, () -> handles.sendTextAsync(handle, "x"));
                assertRetired(listener, client);
                assertThrows(RejectedExecutionException.class,
                        () -> handles.claim(connection, runtime, owner));
            }
        }
    }

    @Test
    void invalidTableCapacitiesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketConnectionHandles(0));
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketConnectionHandles(17));
    }
}
