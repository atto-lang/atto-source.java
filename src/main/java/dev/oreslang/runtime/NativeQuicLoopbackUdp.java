package dev.oreslang.runtime;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Objects;

/**
 * Host-only nonblocking IPv4 loopback datagram probe for a future QUIC engine.
 * This is not a QUIC implementation, general egress capability or guest API.
 * A source-issued actor/egress authorization and physical I/O retirement are
 * still required before production native socket admission.
 */
final class NativeQuicLoopbackUdp implements AutoCloseable {
    static final int MAX_DATAGRAM = 1350;
    private static final InetAddress LOOPBACK;
    static {
        try {
            LOOPBACK = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (java.net.UnknownHostException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }
    private final DatagramChannel channel;

    NativeQuicLoopbackUdp() throws IOException {
        channel = DatagramChannel.open(StandardProtocolFamily.INET);
        try {
            channel.configureBlocking(false);
            channel.bind(new InetSocketAddress(LOOPBACK, 0));
        } catch (IOException | RuntimeException error) {
            channel.close();
            throw error;
        }
    }

    int port() throws IOException {
        return ((InetSocketAddress) channel.getLocalAddress()).getPort();
    }

    /** Returns zero for nonblocking would-block. Never resolves a hostname. */
    int sendToLoopback(int port, ByteBuffer packet) throws IOException {
        Objects.requireNonNull(packet, "packet");
        if (port < 1 || port > 65535 || packet.remaining() < 1 ||
                packet.remaining() > MAX_DATAGRAM) {
            throw new IllegalArgumentException("invalid bounded QUIC UDP datagram");
        }
        // Duplicate prevents changing the caller's buffer cursor on a failed
        // send. QUIC packet production owns its own retransmission state.
        return channel.send(packet.duplicate(), new InetSocketAddress(LOOPBACK, port));
    }

    /**
     * Receive into caller-owned scratch space of at least MAX_DATAGRAM+1 bytes.
     * The extra byte detects oversize datagrams even if the kernel truncates
     * a packet larger than scratch. No blocking selector wait occurs here.
     */
    Received receive(ByteBuffer scratch) throws IOException {
        Objects.requireNonNull(scratch, "scratch");
        if (scratch.remaining() < MAX_DATAGRAM + 1) {
            throw new IllegalArgumentException("QUIC UDP scratch too small to detect truncation");
        }
        ByteBuffer destination = scratch.slice();
        destination.limit(MAX_DATAGRAM + 1);
        SocketAddress source = channel.receive(destination);
        if (source == null) return new Received(false, 0, 0);
        if (!(source instanceof InetSocketAddress peer) ||
                !(peer.getAddress() instanceof Inet4Address) ||
                !peer.getAddress().equals(LOOPBACK) ||
                peer.getPort() < 1 || destination.position() < 1 ||
                destination.position() > MAX_DATAGRAM) {
            throw new SecurityException("invalid loopback QUIC UDP source or packet length");
        }
        return new Received(true, destination.position(), peer.getPort());
    }

    record Received(boolean available, int size, int sourcePort) { }

    @Override
    public void close() throws IOException { channel.close(); }
}
