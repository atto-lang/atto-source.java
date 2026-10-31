package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real localhost TCP + real AsyncNativeIo workers and physical resource loans.
 * Host-only: does not execute an Oreslang guest or prove actor dispatch.
 */
@Timeout(15)
final class WebSocketLoopbackListenerTest {
    private static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    private static int unusedPort() throws Exception {
        try (var reserved = new ServerSocket(0, 1,
                InetAddress.getByName("127.0.0.1"))) {
            return reserved.getLocalPort();
        }
    }

    private static WebSocketLoopbackListener listen(AsyncNativeIo io, int port,
            int maxConnections) throws Exception {
        return WebSocketLoopbackListener.bind(io, (endpoint, action) -> { },
                port, 4, maxConnections, 1024, 1024,
                Set.of("http://127.0.0.1:" + port),
                new WebSocketRawUpgrade.Limits(4096, 16, 750));
    }

    private static void sendOpening(Socket client, int port) throws Exception {
        String request = "GET /echo HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + KEY + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Origin: http://127.0.0.1:" + port + "\r\n\r\n";
        client.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
    }

    private static String readHead(Socket client) throws Exception {
        client.setSoTimeout(2_000);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        while (raw.size() < 4096) {
            int one = client.getInputStream().read();
            if (one < 0) break;
            raw.write(one);
            byte[] bytes = raw.toByteArray();
            int n = bytes.length;
            if (n >= 4 && bytes[n - 4] == '\r' && bytes[n - 3] == '\n'
                    && bytes[n - 2] == '\r' && bytes[n - 1] == '\n') break;
        }
        return raw.toString(StandardCharsets.US_ASCII);
    }

    private static void sendMaskedText(Socket client, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length <= 125);
        client.getOutputStream().write(0x81);
        client.getOutputStream().write(0x80 | bytes.length);
        client.getOutputStream().write(new byte[] {1, 2, 3, 4});
        for (int i = 0; i < bytes.length; i++) {
            client.getOutputStream().write(bytes[i] ^ (i % 4 + 1));
        }
        client.getOutputStream().flush();
    }

    @Test
    void hostListenerAcceptsAndReadsMaskedTextWithNativeLoans() throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 2)) {
            var accept = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var connection = accept.get(3, TimeUnit.SECONDS);
                assertEquals(1, listener.activeConnectionCount());
                sendMaskedText(client, "hello");
                assertEquals(WebSocketSessionState.ActionKind.USER_ENQUEUED,
                        connection.readAsync().get(3, TimeUnit.SECONDS).kind());
                var message = connection.pollUserMessage().orElseThrow();
                assertEquals(WebSocketFrameCodec.OPCODE_TEXT, message.opcode());
                assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), message.payload());
                connection.close();
                connection.close(); // No duplicate permit return.
                assertEquals(0, listener.activeConnectionCount());
            }
        }
    }

    @Test
    void listenerShutdownClosesAllOwnedPeersAndRevokesQueuedData() throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 2)) {
            var first = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var owned = first.get(3, TimeUnit.SECONDS);
                sendMaskedText(client, "retained");
                owned.readAsync().get(3, TimeUnit.SECONDS);
                assertEquals(1, listener.activeConnectionCount());
                assertEquals(1, owned.pollUserMessage().stream().count());
                sendMaskedText(client, "unread");
                owned.readAsync().get(3, TimeUnit.SECONDS);
                listener.close();
                assertEquals(0, listener.activeConnectionCount());
                assertTrue(owned.pollUserMessage().isEmpty(),
                        "retirement must zero retained USER queue");
                assertEquals(-1, client.getInputStream().read(),
                        "parent listener close must close the accepted TCP socket");
                assertThrows(RuntimeException.class, owned::readAsync);
                assertThrows(RuntimeException.class, listener::acceptAsync);
            }
        }
    }

    @Test
    void overloadRejectsSecondPeerButFirstKeepsProgressing() throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 1)) {
            var first = listener.acceptAsync();
            try (var client1 = new Socket("127.0.0.1", port)) {
                sendOpening(client1, port);
                assertTrue(readHead(client1).startsWith("HTTP/1.1 101"));
                var owned = first.get(3, TimeUnit.SECONDS);
                var second = listener.acceptAsync();
                try (var client2 = new Socket("127.0.0.1", port)) {
                    sendOpening(client2, port);
                    assertThrows(ExecutionException.class,
                            () -> second.get(3, TimeUnit.SECONDS));
                    assertEquals(1, listener.activeConnectionCount());
                    sendMaskedText(client1, "live");
                    assertEquals(WebSocketSessionState.ActionKind.USER_ENQUEUED,
                            owned.readAsync().get(3, TimeUnit.SECONDS).kind());
                    owned.close();
                }
            }
            var third = listener.acceptAsync();
            try (var client3 = new Socket("127.0.0.1", port)) {
                sendOpening(client3, port);
                assertTrue(readHead(client3).startsWith("HTTP/1.1 101"));
                third.get(3, TimeUnit.SECONDS).close();
            }
            assertEquals(0, listener.activeConnectionCount());
        }
    }

    @Test
    void physicalReadFutureCancellationIsDeniedUntilTransportActuallyRetires()
            throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 1)) {
            var accept = listener.acceptAsync();
            assertFalse(accept.cancel(true),
                    "admitted native accept is a physical worker obligation");
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var owned = accept.get(3, TimeUnit.SECONDS);
                var read = owned.readAsync();
                assertFalse(read.cancel(true),
                        "observer cancellation must not forge physical read retirement");
                client.getOutputStream().write(new byte[] {
                    (byte) 0x81, (byte) 0x85, 1, 2, 3, 4, (byte) ('h' ^ 1)
                });
                client.getOutputStream().flush();
                listener.close();
                assertThrows(ExecutionException.class,
                        () -> read.get(3, TimeUnit.SECONDS));
                assertEquals(0, listener.activeConnectionCount());
                assertTrue(owned.pollUserMessage().isEmpty());
            }
        }
    }

    @Test
    void listenerCloseDuringPartialNioFrameFailsReadAndRevokesUserState()
            throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 1)) {
            var accept = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var owned = accept.get(3, TimeUnit.SECONDS);
                var reading = owned.readAsync();
                // Header and mask arrive but the advertised payload never
                // completes. Listener retirement must break the parked
                // selector rather than retain a pending USER delivery.
                client.getOutputStream().write(new byte[] {
                    (byte) 0x81, (byte) 0x85, 1, 2, 3, 4, (byte) ('h' ^ 1)
                });
                client.getOutputStream().flush();
                listener.close();
                assertThrows(ExecutionException.class,
                        () -> reading.get(3, TimeUnit.SECONDS));
                assertEquals(0, listener.activeConnectionCount());
                assertTrue(owned.pollUserMessage().isEmpty());
                assertThrows(RuntimeException.class, owned::readAsync);
            }
        }
    }

    @Test
    void parentCloseUnblocksPendingAcceptAndNativeIoCanRetire() throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(2);
             var listener = listen(io, port, 1)) {
            var pending = listener.acceptAsync();
            listener.close();
            assertThrows(ExecutionException.class,
                    () -> pending.get(3, TimeUnit.SECONDS));
            assertEquals(0, listener.activeConnectionCount());
        }
    }

    @Test
    void contextNativeIoShutdownRetiresLogicalOwnerAndPeer() throws Exception {
        int port = unusedPort();
        var io = new AsyncNativeIo(4);
        try {
            var listener = listen(io, port, 1);
            var accept = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var owned = accept.get(3, TimeUnit.SECONDS);
                assertEquals(1, listener.activeConnectionCount());
                // Closing native I/O directly must retire the registered
                // logical listener, not just wake its ServerSocket.
                io.close();
                assertEquals(0, listener.activeConnectionCount());
                assertTrue(owned.pollUserMessage().isEmpty());
                assertEquals(-1, client.getInputStream().read());
            }
        } finally {
            io.close();
        }
    }

    @Test
    void slowDripMaskedPayloadExpiresWholeFrameDeadlineAndRetiresPeer() throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 1)) {
            var accept = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var owned = accept.get(3, TimeUnit.SECONDS);
                var pending = owned.readAsync();
                // FIN text, MASK, 8-byte payload but send fewer than 8 data
                // bytes in total. Every partial read makes progress yet the
                // complete frame must expire at the original 750ms deadline.
                client.getOutputStream().write(new byte[] {
                    (byte) 0x81, (byte) 0x88, 1, 2, 3, 4
                });
                client.getOutputStream().flush();
                for (int i = 0; i < 5; i++) {
                    try {
                        client.getOutputStream().write((byte) ('x' ^ (i % 4 + 1)));
                        client.getOutputStream().flush();
                    } catch (java.io.IOException closedPeer) {
                        break;
                    }
                    Thread.sleep(175);
                }
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> pending.get(3, TimeUnit.SECONDS));
                assertInstanceOf(java.net.SocketTimeoutException.class, failure.getCause());
                assertEquals(0, listener.activeConnectionCount());
            }
        }
    }

    @Test
    void fullyRetiredNativeIoRejectsListenWithoutLeakingBoundSocket() throws Exception {
        int port = unusedPort();
        var io = new AsyncNativeIo(1);
        io.close();
        assertThrows(IllegalStateException.class, () -> listen(io, port, 1));
        try (var reclaim = new ServerSocket(port, 1,
                InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, reclaim.getLocalPort());
        }
    }

    @Test
    void netPolicyDenialHappensBeforeSocketBind() throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(2)) {
            assertThrows(SecurityException.class, () ->
                    WebSocketLoopbackListener.bind(io,
                            (endpoint, action) -> { throw new SecurityException("NET denied"); },
                            port, 4, 1, 1024, 1024,
                            Set.of("http://127.0.0.1:" + port),
                            new WebSocketRawUpgrade.Limits(4096, 16, 750)));
            try (var available = new ServerSocket(port, 1,
                    InetAddress.getByName("127.0.0.1"))) {
                assertEquals(port, available.getLocalPort());
            }
        }
    }

    @Test
    void acceptedPeerBindsOpaqueActorAndRevokesOutstandingLeaseOnClose()
            throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 1)) {
            var accepted = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var conn = accepted.get(3, TimeUnit.SECONDS);
                var tenant = java.util.UUID.randomUUID();
                var runtime = java.util.UUID.randomUUID();
                var a = new ActorRuntime.ActorAddress(runtime,
                        ActorRuntime.ActorId.create(), 1,
                        ActorRuntime.ActorKind.PRIVATE, "local-process");
                var b = new ActorRuntime.ActorAddress(runtime,
                        ActorRuntime.ActorId.create(), 2,
                        ActorRuntime.ActorKind.PRIVATE, "local-process");
                var handle = conn.bindActorHost(tenant, a, () -> true);
                assertThrows(java.util.concurrent.RejectedExecutionException.class,
                        () -> conn.bindActorHost(tenant, b, () -> true));
                var lease = conn.acquireActorLease(handle, tenant, a);
                assertTrue(lease.mayDeliver(handle, tenant, a));
                assertThrows(java.util.concurrent.RejectedExecutionException.class,
                        () -> conn.transferActorHost(handle, tenant, a, b, () -> true),
                        "transfer must wait for the physical loan's completion");
                assertThrows(SecurityException.class,
                        () -> conn.acquireActorLease(handle, java.util.UUID.randomUUID(), a));
                conn.close();
                assertFalse(lease.mayDeliver(handle, tenant, a));
                assertFalse(lease.enqueueIfCurrent(() ->
                        fail("retired actor handle published queued USER work")));
                assertThrows(SecurityException.class,
                        () -> conn.acquireActorLease(handle, tenant, a));
                // Logical revocation does not forge an underlying native-I/O
                // completion receipt; the producer must return the lease.
                lease.close();
                lease.close();
                assertEquals(0, listener.activeConnectionCount());
                assertThrows(SecurityException.class,
                        () -> conn.transferActorHost(handle, tenant, a, b, () -> true));
            }
        }
    }

    @Test
    void actorTransferAfterReturnedLoanFencesOriginalHandle() throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 1)) {
            var accepted = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var conn = accepted.get(3, TimeUnit.SECONDS);
                var tenant = java.util.UUID.randomUUID();
                var runtime = java.util.UUID.randomUUID();
                var a = new ActorRuntime.ActorAddress(runtime,
                        ActorRuntime.ActorId.create(), 1,
                        ActorRuntime.ActorKind.PRIVATE, "local-process");
                var b = new ActorRuntime.ActorAddress(runtime,
                        ActorRuntime.ActorId.create(), 2,
                        ActorRuntime.ActorKind.PRIVATE, "local-process");
                var original = conn.bindActorHost(tenant, a, () -> true);
                try (var physical = conn.acquireActorLease(original, tenant, a)) {
                    assertTrue(physical.mayDeliver(original, tenant, a));
                }
                var transferred = conn.transferActorHost(
                        original, tenant, a, b, () -> true);
                assertThrows(SecurityException.class,
                        () -> conn.acquireActorLease(original, tenant, a));
                assertThrows(SecurityException.class,
                        () -> conn.acquireActorLease(transferred, tenant, a));
                try (var next = conn.acquireActorLease(transferred, tenant, b)) {
                    assertTrue(next.mayDeliver(transferred, tenant, b));
                }
                conn.close();
                assertThrows(SecurityException.class,
                        () -> conn.acquireActorLease(transferred, tenant, b));
            }
        }
    }

    @Test
    void actorDoneRevokesActiveReadWithoutEarlyPhysicalLeaseRelease()
            throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 1)) {
            var pending = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var connection = pending.get(3, TimeUnit.SECONDS);
                var tenant = java.util.UUID.randomUUID();
                var actor = new ActorRuntime.ActorAddress(
                        java.util.UUID.randomUUID(), ActorRuntime.ActorId.create(),
                        1, ActorRuntime.ActorKind.PRIVATE, "local-process");
                var handle = connection.bindActorHost(tenant, actor, () -> true);
                var done = new OresFuture<Void>();
                connection.watchHostActorDone(done, actor);
                var reader = connection.readForActorAsync(handle, tenant, actor);
                assertFalse(reader.cancel(true),
                        "native read remains physically owned until worker exit");
                assertTrue(done.completeFromRuntime(null));
                assertThrows(ExecutionException.class,
                        () -> reader.get(3, TimeUnit.SECONDS),
                        "actor stop must interrupt outstanding selector read");
                assertThrows(SecurityException.class,
                        () -> connection.readForActorAsync(handle, tenant, actor));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (listener.activeConnectionCount() != 0
                        && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(0, listener.activeConnectionCount(),
                        "actor done must cause physical socket retirement");
                assertEquals(-1, client.getInputStream().read());
            }
        }
    }

    @Test
    void retiredOldActorNotificationCannotCloseTransferredConnection()
            throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 1)) {
            var pending = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var connection = pending.get(3, TimeUnit.SECONDS);
                var tenant = java.util.UUID.randomUUID();
                var runtime = java.util.UUID.randomUUID();
                var oldOwner = new ActorRuntime.ActorAddress(
                        runtime, ActorRuntime.ActorId.create(), 1,
                        ActorRuntime.ActorKind.PRIVATE, "local-process");
                var newOwner = new ActorRuntime.ActorAddress(
                        runtime, ActorRuntime.ActorId.create(), 2,
                        ActorRuntime.ActorKind.PRIVATE, "local-process");
                var oldDone = new OresFuture<Void>();
                var newDone = new OresFuture<Void>();
                var oldHandle = connection.bindActorHost(tenant, oldOwner, () -> true);
                connection.watchHostActorDone(oldDone, oldOwner);
                var currentHandle = connection.transferActorHost(
                        oldHandle, tenant, oldOwner, newOwner, () -> true);
                connection.watchHostActorDone(newDone, newOwner);
                assertTrue(oldDone.completeFromRuntime(null));
                assertEquals(1, listener.activeConnectionCount(),
                        "old done cannot retire a transferred live connection");
                connection.sendTextForActorAsync(
                        currentHandle, tenant, newOwner, "live").get(3, TimeUnit.SECONDS);
                assertArrayEquals(new byte[] {(byte) 0x81, 4, 'l', 'i', 'v', 'e'},
                        client.getInputStream().readNBytes(6));
                assertTrue(newDone.completeFromRuntime(null));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (listener.activeConnectionCount() != 0
                        && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(0, listener.activeConnectionCount());
                assertThrows(SecurityException.class,
                        () -> connection.acquireActorLease(currentHandle, tenant, newOwner));
            }
        }
    }

    @Test
    void actorReadDeliveryAndWriteRequireHandleAndDrainBeforeTransfer()
            throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 1)) {
            var pending = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var connection = pending.get(3, TimeUnit.SECONDS);
                var tenant = java.util.UUID.randomUUID();
                var runtime = java.util.UUID.randomUUID();
                var a = new ActorRuntime.ActorAddress(runtime,
                        ActorRuntime.ActorId.create(), 1,
                        ActorRuntime.ActorKind.PRIVATE, "local-process");
                var b = new ActorRuntime.ActorAddress(runtime,
                        ActorRuntime.ActorId.create(), 2,
                        ActorRuntime.ActorKind.PRIVATE, "local-process");
                var handle = connection.bindActorHost(tenant, a, () -> true);
                assertThrows(SecurityException.class, connection::readAsync);
                assertThrows(SecurityException.class,
                        () -> connection.sendTextAsync("unscoped"));
                sendMaskedText(client, "echo");
                var action = connection.readForActorAsync(handle, tenant, a)
                        .get(3, TimeUnit.SECONDS);
                assertEquals(WebSocketSessionState.ActionKind.USER_ENQUEUED,
                        action.kind());
                assertThrows(java.util.concurrent.RejectedExecutionException.class,
                        () -> connection.transferActorHost(
                                handle, tenant, a, b, () -> true),
                        "handoff must reject USER messages admitted by former owner");
                var delivered = new java.util.ArrayList<WebSocketFrameCodec.Message>();
                assertTrue(connection.pollAndEnqueueForActor(
                        handle, tenant, a, delivered::add));
                assertEquals(1, delivered.size());
                assertArrayEquals("echo".getBytes(StandardCharsets.UTF_8),
                        delivered.get(0).payload());
                assertFalse(connection.pollAndEnqueueForActor(
                        handle, tenant, a, delivered::add));
                var newHandle = connection.transferActorHost(
                        handle, tenant, a, b, () -> true);
                assertThrows(SecurityException.class,
                        () -> connection.readForActorAsync(handle, tenant, a));
                assertThrows(SecurityException.class,
                        () -> connection.sendTextForActorAsync(
                                handle, tenant, a, "stale"));
                connection.sendTextForActorAsync(
                        newHandle, tenant, b, "echo").get(3, TimeUnit.SECONDS);
                assertArrayEquals(new byte[] {
                    (byte) 0x81, 4, 'e', 'c', 'h', 'o'
                }, client.getInputStream().readNBytes(6));
                connection.close();
                assertThrows(java.util.concurrent.RejectedExecutionException.class,
                        () -> connection.sendBinaryForActorAsync(
                                newHandle, tenant, b, new byte[] {1}));
            }
        }
    }

    @Test
    void outstandingActorReadBlocksTransferUntilPhysicalShutdown() throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 1)) {
            var accepted = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var connection = accepted.get(3, TimeUnit.SECONDS);
                var tenant = java.util.UUID.randomUUID();
                var runtime = java.util.UUID.randomUUID();
                var owner = new ActorRuntime.ActorAddress(runtime,
                        ActorRuntime.ActorId.create(), 1,
                        ActorRuntime.ActorKind.PRIVATE, "local-process");
                var successor = new ActorRuntime.ActorAddress(runtime,
                        ActorRuntime.ActorId.create(), 2,
                        ActorRuntime.ActorKind.PRIVATE, "local-process");
                var handle = connection.bindActorHost(tenant, owner, () -> true);
                var read = connection.readForActorAsync(handle, tenant, owner);
                assertFalse(read.cancel(true),
                        "native read cannot forge physical loan completion");
                assertThrows(java.util.concurrent.RejectedExecutionException.class,
                        () -> connection.transferActorHost(
                                handle, tenant, owner, successor, () -> true));
                listener.close();
                assertThrows(ExecutionException.class,
                        () -> read.get(3, TimeUnit.SECONDS));
                assertEquals(0, listener.activeConnectionCount());
                assertThrows(SecurityException.class,
                        () -> connection.readForActorAsync(handle, tenant, owner));
            }
        }
    }

    @Test
    void hostOutboundTextBinaryAndPongHaveCanonicalUnmaskedWire() throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 1)) {
            var accept = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var connection = accept.get(3, TimeUnit.SECONDS);
                connection.sendTextAsync("echo").get(3, TimeUnit.SECONDS);
                assertArrayEquals(new byte[] {(byte) 0x81, 4, 'e', 'c', 'h', 'o'},
                        client.getInputStream().readNBytes(6));
                connection.sendBinaryAsync(new byte[] {0, 1, 2})
                        .get(3, TimeUnit.SECONDS);
                assertArrayEquals(new byte[] {(byte) 0x82, 3, 0, 1, 2},
                        client.getInputStream().readNBytes(5));

                // A PING goes through the reserved CONTROL queue, not the
                // reader's Socket.getOutputStream() on its I/O read worker.
                client.getOutputStream().write(new byte[] {
                        (byte) 0x89, (byte) 0x81, 1, 2, 3, 4, (byte) ('p' ^ 1)
                });
                client.getOutputStream().flush();
                assertEquals(WebSocketSessionState.ActionKind.SEND_PONG,
                        connection.readAsync().get(3, TimeUnit.SECONDS).kind());
                assertArrayEquals(new byte[] {(byte) 0x8A, 1, 'p'},
                        client.getInputStream().readNBytes(3));
                connection.close();
                assertThrows(RuntimeException.class,
                        () -> connection.sendTextAsync("stale"));
                assertEquals(0, listener.activeConnectionCount());
            }
        }
    }


    @Test
    void rejectedControlAdmissionStillRetiresActorOwnedIdleSocket()
            throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 1)) {
            var accept = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var connection = accept.get(3, TimeUnit.SECONDS);
                var tenant = java.util.UUID.randomUUID();
                var owner = new ActorRuntime.ActorAddress(
                        java.util.UUID.randomUUID(), ActorRuntime.ActorId.create(),
                        1, ActorRuntime.ActorKind.PRIVATE, "local-process");
                connection.bindActorHost(tenant, owner, () -> true);

                // Saturate all sixteen bounded native CONTROL admissions.
                // The callback is forced to use the shared host fallback;
                // it must NEVER create one virtual cleanup worker per peer.
                var running = new java.util.concurrent.CountDownLatch(16);
                var release = new java.util.concurrent.CountDownLatch(1);
                try {
                    for (int i = 0; i < 16; i++) {
                        io.submitControl("test.block-control", () -> {
                            running.countDown();
                            release.await(3, TimeUnit.SECONDS);
                            return null;
                        });
                    }
                    assertTrue(running.await(3, TimeUnit.SECONDS));
                    var done = new OresFuture<Void>();
                    connection.watchHostActorDone(done, owner);
                    done.completeFromRuntime(null);
                    assertEquals(-1, client.getInputStream().read());
                    assertEquals(0, listener.activeConnectionCount());
                } finally {
                    release.countDown();
                }
            }
        }
    }

    @Test
    void failedActorDeliveryUsesHostCleanupOutsideLifecycleMonitor()
            throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 1)) {
            var accept = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var connection = accept.get(3, TimeUnit.SECONDS);
                var tenant = java.util.UUID.randomUUID();
                var owner = new ActorRuntime.ActorAddress(
                        java.util.UUID.randomUUID(), ActorRuntime.ActorId.create(),
                        1, ActorRuntime.ActorKind.PRIVATE, "local-process");
                var handle = connection.bindActorHost(tenant, owner, () -> true);
                sendMaskedText(client, "delivery");
                assertEquals(WebSocketSessionState.ActionKind.USER_ENQUEUED,
                        connection.readForActorAsync(handle, tenant, owner)
                                .get(3, TimeUnit.SECONDS).kind());
                var failed = assertThrows(IllegalStateException.class,
                        () -> connection.pollAndEnqueueForActor(
                                handle, tenant, owner,
                                message -> { throw new IllegalStateException("scheduler full"); }));
                assertEquals("scheduler full", failed.getMessage());
                // Failure releases the logical delivery lease and requests
                // host retirement outside the lifecycle lock.
                assertEquals(-1, client.getInputStream().read());
                assertEquals(0, listener.activeConnectionCount());
                assertThrows(SecurityException.class,
                        () -> connection.acquireActorLease(handle, tenant, owner));
            }
        }
    }

    @Test
    void peerCloseFrameIsSerializedAndRetiresThePhysicalConnection() throws Exception {
        int port = unusedPort();
        try (var io = new AsyncNativeIo(4);
             var listener = listen(io, port, 1)) {
            var accept = listener.acceptAsync();
            try (var client = new Socket("127.0.0.1", port)) {
                sendOpening(client, port);
                assertTrue(readHead(client).startsWith("HTTP/1.1 101"));
                var connection = accept.get(3, TimeUnit.SECONDS);
                byte[] close = WebSocketSessionState.closePayload(1000, "");
                byte[] mask = {1, 2, 3, 4};
                client.getOutputStream().write(new byte[] {
                        (byte) 0x88, (byte) (0x80 | close.length), 1, 2, 3, 4,
                        (byte) (close[0] ^ mask[0]), (byte) (close[1] ^ mask[1])
                });
                client.getOutputStream().flush();
                assertEquals(WebSocketSessionState.ActionKind.SEND_CLOSE,
                        connection.readAsync().get(3, TimeUnit.SECONDS).kind());
                assertArrayEquals(new byte[] {(byte) 0x88, 2, 3, (byte) 0xE8},
                        client.getInputStream().readNBytes(4));
                assertEquals(-1, client.getInputStream().read());
                assertThrows(RuntimeException.class,
                        () -> connection.sendBinaryAsync(new byte[] {1}));
            }
        }
    }
}
