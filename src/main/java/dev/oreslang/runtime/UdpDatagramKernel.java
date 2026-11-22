package dev.oreslang.runtime;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trusted HOST-ONLY datagram kernel. No guest call can mint PermissionGate.
 * First stage is numeric IPv4 loopback only, without DNS or ambient egress.
 * All operations are nonblocking; callers may use NioReadinessGroup for readiness.
 * Never use this as a substitute for OresContext permission/capability checks.
 */
final class UdpDatagramKernel implements AutoCloseable {
    static final int MAX_PAYLOAD = 65507;
    @FunctionalInterface
    interface PermissionGate {
        void require(String endpoint, String operation);
    }
    enum SendOutcome { SENT, WOULD_BLOCK }
    record Received(byte[] bytes, int sourcePort) {
        Received { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private static final InetAddress LOOPBACK;
    static {
        try {
            LOOPBACK = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (java.net.UnknownHostException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private final DatagramChannel channel;
    private final PermissionGate permission;
    private final int bindPort;
    private final AtomicBoolean closed = new AtomicBoolean();

    private UdpDatagramKernel(DatagramChannel channel, PermissionGate permission, int bindPort) {
        this.channel = channel;
        this.permission = permission;
        this.bindPort = bindPort;
    }

    static UdpDatagramKernel bindLoopback(int port, PermissionGate permission) throws IOException {
        Objects.requireNonNull(permission, "trusted permission gate");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("UDP bind port must be 0..65535");
        // Authorization MUST precede even opening the descriptor.
        permission.require("127.0.0.1:" + port, "udp.bind");
        DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET);
        try {
            channel.configureBlocking(false);
            channel.bind(new InetSocketAddress(LOOPBACK, port));
            return new UdpDatagramKernel(channel, permission, port);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    DatagramChannel channel() { return channel; }
    int requestedBindPort() { return bindPort; }

    int localPort() throws IOException {
        requireOpen();
        return ((InetSocketAddress) channel.getLocalAddress()).getPort();
    }

    private void requireOpen() throws IOException {
        if (closed.get() || !channel.isOpen()) throw new IOException("UDP socket closed");
    }

    private void requireLocal(String operation) throws IOException {
        requireOpen();
        // Keep port-0 authority distinct from the ephemeral OS-selected port:
        // bound address checked at admission, and checked again on each operation.
        permission.require("127.0.0.1:" + bindPort, operation);
    }

    SendOutcome trySendTo(int peerPort, byte[] source) throws IOException {
        Objects.requireNonNull(source, "datagram bytes");
        if (peerPort < 1 || peerPort > 65535) throw new IllegalArgumentException("invalid UDP destination port");
        if (source.length > MAX_PAYLOAD) throw new IllegalArgumentException("IPv4 UDP payload too large");
        requireLocal("udp.send_to");
        permission.require("127.0.0.1:" + peerPort, "udp.send_to");
        // Snapshot before sending: caller mutation never changes an outstanding send.
        byte[] detached = source.clone();
        int sent = channel.send(ByteBuffer.wrap(detached), new InetSocketAddress(LOOPBACK, peerPort));
        if (sent == 0 && detached.length != 0) return SendOutcome.WOULD_BLOCK;
        if (sent != detached.length) throw new IOException("UDP did not send a whole datagram");
        // For zero bytes, DatagramChannel.send returns 0 on success. The
        // receiver must still independently validate arrival (UDP is lossy).
        return SendOutcome.SENT;
    }

    /** null => would-block; an empty byte array is a REAL zero-byte datagram. */
    Received tryReceiveFrom(int maxBytes) throws IOException {
        if (maxBytes < 1 || maxBytes > MAX_PAYLOAD)
            throw new IllegalArgumentException("UDP receive capacity must be 1..65507");
        requireLocal("udp.recv_from");
        // Extra byte detects any packet over the requested user buffer.
        ByteBuffer scratch = ByteBuffer.allocate(maxBytes + 1);
        SocketAddress source = channel.receive(scratch);
        if (source == null) return null;
        if (!(source instanceof InetSocketAddress peer)
                || !(peer.getAddress() instanceof Inet4Address)
                || !LOOPBACK.equals(peer.getAddress())
                || peer.getPort() < 1) {
            throw new SecurityException("UDP packet source outside numeric IPv4 loopback");
        }
        permission.require("127.0.0.1:" + peer.getPort(), "udp.recv_from");
        if (scratch.position() > maxBytes)
            throw new IOException("UDP datagram truncated; packet discarded");
        byte[] owned = Arrays.copyOf(scratch.array(), scratch.position());
        return new Received(owned, peer.getPort());
    }

    @Override public void close() throws IOException {
        if (closed.compareAndSet(false, true)) channel.close();
    }
}
