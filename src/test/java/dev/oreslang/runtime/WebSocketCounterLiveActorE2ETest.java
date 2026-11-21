package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real loopback TCP -> WebSocket -> actual ActorRuntime mailbox -> TCP.
 * Host-only integration: NOT compiled .ores, guest WebSocket, or browser /live.
 */
@Timeout(20)
final class WebSocketCounterLiveActorE2ETest {
    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private static final String EVENT = "{\"v\":1,\"type\":\"event\",\"event_id\":\""
            + ID + "\",\"name\":\"increment\",\"payload\":{},\"base_rev\":0}";
    private static final String PATCH = "{\"v\":1,\"type\":\"patch\",\"prev\":0,\"rev\":1,"
            + "\"ops\":[{\"op\":\"set_text\",\"id\":\"count_value\",\"text\":\"1\"}]}";
    private static final String ACK = "{\"v\":1,\"type\":\"event_ack\",\"event_id\":\""
            + ID + "\",\"rev\":1}";

    private static int port() throws Exception {
        try (var s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    private static void upgrade(Socket client, int port) throws Exception {
        String request = "GET /echo HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\nOrigin: http://127.0.0.1:" + port
                + "\r\n\r\n";
        client.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
        client.setSoTimeout(3000);
        var head = new ByteArrayOutputStream();
        while (head.size() < 4096) {
            int one = client.getInputStream().read();
            if (one < 0) fail("WebSocket upgrade ended before response");
            head.write(one);
            byte[] b = head.toByteArray();
            int n = b.length;
            if (n >= 4 && b[n-4] == '\r' && b[n-3] == '\n'
                    && b[n-2] == '\r' && b[n-1] == '\n') break;
        }
        assertTrue(head.toString(StandardCharsets.US_ASCII).startsWith("HTTP/1.1 101"));
    }

    private static void clientEvent(Socket client) throws Exception {
        byte[] b = EVENT.getBytes(StandardCharsets.UTF_8);
        assertTrue(b.length <= 125);
        var out = client.getOutputStream();
        out.write(0x81); out.write(0x80 | b.length);
        byte[] key = {1,2,3,4};
        out.write(key);
        for (int i = 0; i < b.length; i++) out.write(b[i] ^ key[i & 3]);
        out.flush();
    }

    private static String serverText(Socket client) throws Exception {
        var in = client.getInputStream();
        assertEquals(0x81, in.read());
        int second = in.read();
        assertTrue(second >= 0 && (second & 0x80) == 0);
        int n = second & 127;
        assertTrue(n <= 125, "server response must fit bounded test frame");
        byte[] b = in.readNBytes(n);
        assertEquals(n, b.length);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void hostReply(WebSocketLoopbackListener.Connection conn,
            WebSocketActorConnectionLease.Handle handle, UUID tenant,
            ActorRuntime.ActorRef<?> actor, ArrayBlockingQueue<String> replies,
            Socket client, String expected) throws Exception {
        String actual = replies.poll(3, TimeUnit.SECONDS);
        assertEquals(expected, actual, "actual actor mailbox must emit expected reply");
        conn.sendTextForActorAsync(handle, tenant, actor.address(), actual)
                .get(3, TimeUnit.SECONDS);
        assertEquals(expected, serverText(client));
    }

    @Test
    void counterEventAndIdempotentReplayUseRealActorTurnAndSocket() throws Exception {
        var counter = new CounterLiveTurnModel();
        var delivered = new AtomicInteger();
        var replies = new ArrayBlockingQueue<String>(4);
        try (var runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                    ctx -> (mail, turn) -> {});
            actor.ready().get(3, TimeUnit.SECONDS);
            int port = port();
            try (var io = new AsyncNativeIo(4);
                 var listener = WebSocketLoopbackListener.bind(
                         io, (endpoint, action) -> {}, port, 4, 1, 1024, 1024,
                         Set.of("http://127.0.0.1:" + port),
                         new WebSocketRawUpgrade.Limits(4096, 16, 750))) {
                var pending = listener.acceptAsync();
                try (var client = new Socket("127.0.0.1", port)) {
                    upgrade(client, port);
                    var conn = pending.get(3, TimeUnit.SECONDS);
                    var tenant = UUID.randomUUID();
                    var handle = conn.bindActorHost(tenant, actor.address(), actor::isAlive);
                    conn.watchHostActorDone(actor.done(), actor.address());
                    for (int i = 0; i < 2; i++) {
                        clientEvent(client);
                        assertEquals(WebSocketSessionState.ActionKind.USER_ENQUEUED,
                                conn.readForActorAsync(handle, tenant, actor.address())
                                        .get(3, TimeUnit.SECONDS).kind());
                        assertTrue(conn.dispatchHostActorMessage(
                                handle, tenant, runtime, actor, frame -> {
                                    assertTrue(ActorRuntime.inActorExecution());
                                    assertEquals(actor.address(),
                                            ActorRuntime.currentActorAddress().orElseThrow());
                                    assertEquals(EVENT,
                                            new String(frame.payload(), StandardCharsets.UTF_8));
                                    assertThrows(SecurityException.class,
                                            () -> conn.sendTextForActorAsync(
                                                    handle, tenant, actor.address(), PATCH),
                                            "actor turn cannot perform physical host I/O");
                                    delivered.incrementAndGet();
                                    var result = counter.increment("authenticated-loopback-user", ID, 0);
                                    for (String reply : result.frames()) assertTrue(replies.offer(reply));
                                }));
                        if (i == 0) hostReply(conn, handle, tenant, actor, replies, client, PATCH);
                        hostReply(conn, handle, tenant, actor, replies, client, ACK);
                    }
                    assertEquals(1, counter.count(), "duplicate event may not mutate actor state");
                    assertEquals(2, delivered.get(), "each frame gets one mailbox turn");
                    long ticketDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (conn.pendingActorTicketCount() != 0
                            && System.nanoTime() < ticketDeadline) Thread.sleep(5);
                    assertEquals(0, conn.pendingActorTicketCount(),
                            "delivery tickets must be released after mailbox turns");
                    assertTrue(actor.cancel());
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (listener.activeConnectionCount() != 0
                            && System.nanoTime() < until) Thread.sleep(5);
                    assertEquals(0, listener.activeConnectionCount(),
                            "actor STOP must close the physical WebSocket");
                    assertThrows(SecurityException.class,
                            () -> conn.acquireActorLease(handle, tenant, actor.address()));
                }
            }
        }
    }
}
