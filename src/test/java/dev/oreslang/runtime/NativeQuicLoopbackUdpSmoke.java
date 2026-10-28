package dev.oreslang.runtime;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

final class NativeQuicLoopbackUdpSmoke {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static void mustReject(Runnable action) {
        try { action.run(); throw new AssertionError("expected rejection"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] ignored) throws Exception {
        try (var a = new NativeQuicLoopbackUdp(); var b = new NativeQuicLoopbackUdp()) {
            check(a.port() > 0 && b.port() > 0 && a.port() != b.port());
            var packet = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
            mustReject(() -> { try { a.sendToLoopback(0, packet); } catch (java.io.IOException ex) { throw new AssertionError(ex); } });
            mustReject(() -> { try { b.receive(ByteBuffer.allocate(1)); } catch (java.io.IOException ex) { throw new AssertionError(ex); } });
            check(a.sendToLoopback(b.port(), packet) == 4);
            check(packet.position() == 0);
            NativeQuicLoopbackUdp.Received got = null;
            var scratch = ByteBuffer.allocate(1351);
            for (int i = 0; i < 1000; i++) {
                got = b.receive(scratch);
                if (got.available()) break;
                Thread.sleep(1);
            }
            check(got != null && got.available() && got.size() == 4 && got.sourcePort() == a.port());
            check(scratch.position() == 0);
            // Boundary: exactly 1350 bytes are accepted without truncation;
            // 1351 bytes are rejected before any UDP send occurs.
            var full = ByteBuffer.wrap(new byte[NativeQuicLoopbackUdp.MAX_DATAGRAM]);
            check(a.sendToLoopback(b.port(), full) == NativeQuicLoopbackUdp.MAX_DATAGRAM);
            check(full.position() == 0);
            NativeQuicLoopbackUdp.Received boundary = null;
            for (int i = 0; i < 1000; i++) {
                boundary = b.receive(scratch);
                if (boundary.available()) break;
                Thread.sleep(1);
            }
            check(boundary != null && boundary.available() &&
                  boundary.size() == NativeQuicLoopbackUdp.MAX_DATAGRAM);
            mustReject(() -> {
                try { a.sendToLoopback(b.port(), ByteBuffer.wrap(new byte[1351])); }
                catch (java.io.IOException ex) { throw new AssertionError(ex); }
            });
            try (var raw = DatagramChannel.open(StandardProtocolFamily.INET)) {
                raw.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}), 0));
                raw.send(ByteBuffer.wrap(new byte[1351]), new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}), b.port()));
            }
            boolean oversized = false;
            for (int i = 0; i < 1000; i++) {
                try {
                    var received = b.receive(scratch);
                    if (received.available()) throw new AssertionError("oversize accepted");
                } catch (SecurityException expected) { oversized = true; break; }
                Thread.sleep(1);
            }
            check(oversized);
        }
        System.out.println("Native Java QUIC loopback UDP: bounded nonblocking send/receive passed (not QUIC)");
    }
}
