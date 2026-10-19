package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Source-only transport framing, loopback TCP, and generation replay proof. */
public final class UnaryTransportFrameCheck {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void invalidTcp(byte[] bytes) throws Exception {
        try { UnaryTransportFrame.readTcp(new ByteArrayInputStream(bytes));
              throw new AssertionError("malformed TCP frame accepted"); }
        catch (IOException expected) { }
    }

    private static void invalidWs(byte[] bytes) throws Exception {
        try { UnaryTransportFrame.decodeWebSocketBinary(bytes);
              throw new AssertionError("malformed WebSocket binary payload accepted"); }
        catch (IOException expected) { }
    }

    private static void framing() throws Exception {
        byte[] input = "hello".getBytes(StandardCharsets.UTF_8);
        var request = new UnaryTransportFrame.Envelope(UnaryTransportFrame.Kind.REQUEST, 7, 81, input);
        input[0] = 'X';
        check(new String(request.payload(), StandardCharsets.UTF_8).equals("hello"), "input must be copied");
        byte[] leaked = request.payload(); leaked[1] = 'X';
        check(new String(request.payload(), StandardCharsets.UTF_8).equals("hello"), "getter must copy");
        byte[] wire = UnaryTransportFrame.encodeTcp(request);
        check(ByteBuffer.wrap(wire).getInt() == wire.length - 4, "TCP length prefix");
        var decoded = UnaryTransportFrame.readTcp(new ByteArrayInputStream(wire));
        check(decoded.kind() == UnaryTransportFrame.Kind.REQUEST && decoded.generation() == 7
                && decoded.correlationId() == 81 && Arrays.equals(decoded.payload(), request.payload()),
                "TCP envelope roundtrip");
        var ws = UnaryTransportFrame.decodeWebSocketBinary(UnaryTransportFrame.encodeWebSocketBinary(request));
        check(Arrays.equals(ws.payload(), request.payload()) && ws.correlationId() == 81,
                "WebSocket message-envelope roundtrip");

        for (int length : new int[] { -1, 0, 17, Integer.MAX_VALUE }) {
            invalidTcp(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(length).array());
        }
        invalidTcp(Arrays.copyOf(wire, wire.length - 1));
        invalidTcp(new byte[] { 0, 0 });
        byte[] wsBad = UnaryTransportFrame.encodeWebSocketBinary(request);
        wsBad[0] = 2; invalidWs(wsBad);
        wsBad[0] = 1; wsBad[1] = 99; invalidWs(wsBad);
        wsBad[1] = 1; Arrays.fill(wsBad, 2, 10, (byte) 0); invalidWs(wsBad);
        invalidWs(new byte[17]);
        invalidWs(new byte[UnaryTransportFrame.MAX_ENVELOPE_BYTES + 1]);
        try { new UnaryTransportFrame.Envelope(UnaryTransportFrame.Kind.CANCEL, 1, 1, new byte[] { 1 });
              throw new AssertionError("CANCEL accepted a body"); }
        catch (IllegalArgumentException expected) { }
        try { new UnaryTransportFrame.Envelope(UnaryTransportFrame.Kind.REQUEST, 0, 1, new byte[0]);
              throw new AssertionError("zero generation accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void loopbackTcp() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> handler = workers.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    var first = UnaryTransportFrame.readTcp(socket.getInputStream());
                    var second = UnaryTransportFrame.readTcp(socket.getInputStream());
                    check(first.correlationId() == 101 && second.correlationId() == 102,
                            "split/coalesced TCP requests retain identity");
                    UnaryTransportFrame.writeTcp(socket.getOutputStream(), new UnaryTransportFrame.Envelope(
                            UnaryTransportFrame.Kind.REPLY, first.generation(), first.correlationId(), first.payload()));
                    UnaryTransportFrame.writeTcp(socket.getOutputStream(), new UnaryTransportFrame.Envelope(
                            UnaryTransportFrame.Kind.REPLY, second.generation(), second.correlationId(), second.payload()));
                    socket.getOutputStream().flush();
                } catch (Exception ex) { throw new AssertionError(ex); }
            });
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(3000);
                var a = new UnaryTransportFrame.Envelope(UnaryTransportFrame.Kind.REQUEST, 11, 101, new byte[] {1, 2});
                var b = new UnaryTransportFrame.Envelope(UnaryTransportFrame.Kind.REQUEST, 11, 102, new byte[] {3, 4, 5});
                byte[] first = UnaryTransportFrame.encodeTcp(a), second = UnaryTransportFrame.encodeTcp(b);
                client.getOutputStream().write(first, 0, 3);
                client.getOutputStream().flush();
                client.getOutputStream().write(first, 3, first.length - 3);
                client.getOutputStream().write(second);
                client.getOutputStream().flush();
                var replyA = UnaryTransportFrame.readTcp(client.getInputStream());
                var replyB = UnaryTransportFrame.readTcp(client.getInputStream());
                check(replyA.kind() == UnaryTransportFrame.Kind.REPLY && replyA.correlationId() == 101,
                        "first TCP reply correlation");
                check(replyB.kind() == UnaryTransportFrame.Kind.REPLY && replyB.correlationId() == 102,
                        "second TCP reply correlation");
                handler.get(4, TimeUnit.SECONDS);
            }
        }
    }

    private static void exactlyOnce() throws Exception {
        var slot = new UnaryReplySlot(UnaryTransportFrame.Transport.TCP, 9, 5);
        var response = new UnaryTransportFrame.Envelope(UnaryTransportFrame.Kind.REPLY, 9, 5, new byte[] {1});
        var old = new UnaryTransportFrame.Envelope(UnaryTransportFrame.Kind.REPLY, 8, 5, new byte[] {1});
        try { slot.accept(UnaryTransportFrame.Transport.TCP, old);
              throw new AssertionError("stale generation accepted"); }
        catch (SecurityException expected) { }
        try { slot.accept(UnaryTransportFrame.Transport.WEBSOCKET, response);
              throw new AssertionError("wrong transport accepted"); }
        catch (SecurityException expected) { }
        check(slot.accept(UnaryTransportFrame.Transport.TCP, response), "first reply wins");
        check(!slot.accept(UnaryTransportFrame.Transport.TCP, response), "duplicate reply rejected");
        check(!slot.cancel(UnaryTransportFrame.Transport.TCP, 9, 5), "late cancellation rejected");
        try { slot.accept(UnaryTransportFrame.Transport.TCP,
                new UnaryTransportFrame.Envelope(UnaryTransportFrame.Kind.REQUEST, 9, 5, new byte[0]));
              throw new AssertionError("REQUEST used as reply"); }
        catch (IllegalArgumentException expected) { }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1000; i++) {
                var raced = new UnaryReplySlot(UnaryTransportFrame.Transport.WEBSOCKET, 12, 15);
                var wsReply = new UnaryTransportFrame.Envelope(UnaryTransportFrame.Kind.REPLY, 12, 15, new byte[0]);
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger winners = new AtomicInteger();
                Future<?> reply = executor.submit(() -> {
                    try { start.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
                    if (raced.accept(UnaryTransportFrame.Transport.WEBSOCKET, wsReply)) winners.incrementAndGet();
                });
                Future<?> cancel = executor.submit(() -> {
                    try { start.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
                    if (raced.cancel(UnaryTransportFrame.Transport.WEBSOCKET, 12, 15)) winners.incrementAndGet();
                });
                start.countDown(); reply.get(2, TimeUnit.SECONDS); cancel.get(2, TimeUnit.SECONDS);
                check(winners.get() == 1 && raced.isTerminal(), "exactly one terminal owner");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        framing(); loopbackTcp(); exactlyOnce();
        System.out.println("PASS: strict TCP/WS envelopes, split TCP loopback and 1000 cancel/reply races");
    }
}
