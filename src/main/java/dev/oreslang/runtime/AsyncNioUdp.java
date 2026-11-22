package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Capability-gated, numeric IPv4-loopback UDP on the Atto VM's existing NIO
 * readiness reactor. This is the first restricted guest network.bind_udp
 * lowering, NOT a completed std.net.udp import/IPv6 or actor-sendable handle.
 * The source checker must still enforce single-owner socket aliasing before
 * this can be used across actor/task boundaries.
 */
public final class AsyncNioUdp {
    private AsyncNioUdp() { }

    public static OresFuture<Socket> bindAsync(OresContext context, String host, int port) {
        Objects.requireNonNull(context, "context");
        try {
            context.requireCapability(IsolatePolicy.Capability.NETWORK, "network.bind_udp");
            if (!"127.0.0.1".equals(host)) {
                throw new SecurityException("UDP v1 requires numeric IPv4 loopback");
            }
            UdpDatagramKernel kernel = UdpDatagramKernel.bindLoopback(
                    port, (endpoint, operation) -> context.requirePermission(
                            RuntimePermissions.Permission.NET, endpoint, operation));
            Socket socket = new Socket(context, kernel);
            try {
                context.nativeIo().register(socket);
                return OresFuture.completed(socket);
            } catch (RuntimeException failure) {
                kernel.close();
                throw failure;
            }
        } catch (IOException | RuntimeException failure) {
            return OresFuture.failed(failure);
        }
    }

    /** Guest-visible value snapshot, never an externally mutable kernel array. */
    public record Packet(List<Long> bytes, int sourcePort) {
        public Packet {
            bytes = List.copyOf(bytes);
        }
    }

    public static final class Socket implements AutoCloseable {
        private final OresContext context;
        private final UdpDatagramKernel kernel;
        private final AtomicBoolean closed = new AtomicBoolean();
        // A UDP datagram is one indivisible operation. A single in-flight
        // operation also honors the VM NioReadinessGroup one-watch-per-channel
        // contract, preventing lost and cross-actor readiness notifications.
        private final AtomicBoolean operating = new AtomicBoolean();

        private Socket(OresContext context, UdpDatagramKernel kernel) {
            this.context = context;
            this.kernel = kernel;
        }

        public int localPort() {
            checkLive("udp.local_port");
            try {
                return kernel.localPort();
            } catch (IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }

        /**
         * Pure host admission boundary, independently testable without a VM.
         * The trusted permission gate runs BEFORE size(), get(), or any
         * allocation proportional to guest-controlled packet content.
         */
        static byte[] snapshotAuthorizedOctets(int peerPort, List<?> octets,
                UdpDatagramKernel.PermissionGate authorize) {
            Objects.requireNonNull(authorize, "trusted NETWORK authorization");
            if (peerPort < 1 || peerPort > 65535) {
                throw new IllegalArgumentException("invalid UDP destination port");
            }
            authorize.require("127.0.0.1:" + peerPort, "udp.send_to");
            Objects.requireNonNull(octets, "UDP bytes");
            int size = octets.size();
            if (size < 0 || size > UdpDatagramKernel.MAX_PAYLOAD) {
                throw new IllegalArgumentException("UDP payload size out of range");
            }
            byte[] data = new byte[size];
            for (int i = 0; i < data.length; i++) {
                Object value = octets.get(i);
                if (!(value instanceof Number n)
                        || (n instanceof Double d && !Double.isFinite(d))
                        || n.longValue() < 0 || n.longValue() > 255
                        || n.doubleValue() != n.longValue()) {
                    throw new IllegalArgumentException("UDP bytes must be integers 0..255");
                }
                data[i] = (byte) n.intValue();
            }
            return data;
        }

        public OresFuture<Integer> sendTo(int peerPort, List<?> octets) {
            final byte[] data;
            try {
                checkLive("udp.send_to");
                data = snapshotAuthorizedOctets(peerPort, octets,
                        (endpoint, operation) -> context.requirePermission(
                                RuntimePermissions.Permission.NET, endpoint, operation));
            } catch (RuntimeException failure) {
                return OresFuture.failed(failure);
            }
            // This owned byte[] cannot be mutated by the guest while pending.
            // The kernel re-checks the destination permission at send time.
            return operate(SelectionKey.OP_WRITE, () -> {
                UdpDatagramKernel.SendOutcome result = kernel.trySendTo(peerPort, data);
                return result == UdpDatagramKernel.SendOutcome.SENT ? data.length : null;
            });
        }

        public OresFuture<Packet> receiveFrom(int maxBytes) {
            if (maxBytes < 1 || maxBytes > UdpDatagramKernel.MAX_PAYLOAD) {
                return OresFuture.failed(new IllegalArgumentException("invalid UDP recv capacity"));
            }
            return operate(SelectionKey.OP_READ, () -> {
                UdpDatagramKernel.Received received = kernel.tryReceiveFrom(maxBytes);
                if (received == null) return null;
                byte[] copied = received.bytes();
                ArrayList<Long> owned = new ArrayList<>(copied.length);
                for (byte b : copied) owned.add((long) (b & 0xff));
                java.util.Arrays.fill(copied, (byte) 0);
                return new Packet(owned, received.sourcePort());
            });
        }

        @FunctionalInterface
        private interface NonblockingStep<T> { T tryOnce() throws IOException; }

        private <T> OresFuture<T> operate(int interest, NonblockingStep<T> attempt) {
            try {
                checkLive("udp.io");
            } catch (RuntimeException failure) {
                return OresFuture.failed(failure);
            }
            if (!operating.compareAndSet(false, true)) {
                return OresFuture.failed(new IllegalStateException(
                        "concurrent UDP socket operation is forbidden"));
            }
            AtomicBoolean finished = new AtomicBoolean();
            // Physical completion and observer cancellation are distinct.
            // Cancellation may occur while a readiness worker is inside a
            // permission check or kernel tryReceive/trySend. Keep the socket
            // lease until that physical step has actually returned.
            AtomicBoolean stepping = new AtomicBoolean();
            AtomicBoolean retryRequested = new AtomicBoolean();
            AtomicReference<OresFuture<Integer>> watch = new AtomicReference<>();
            return OresFuture.fromCallback(callback -> {
                final class Retry {
                    void complete(T value, Throwable failure) {
                        if (!finished.compareAndSet(false, true)) return;
                        if (!stepping.get()) operating.set(false);
                        if (failure != null) callback.reject(failure);
                        else callback.resolve(value);
                    }
                    void tick() {
                        if (finished.get()) return;
                        if (!stepping.compareAndSet(false, true)) {
                            // An immediately completed readiness watch can
                            // notify before initial registration unwinds.
                            // Retry once the current physical step retires.
                            retryRequested.set(true);
                            return;
                        }
                        try {
                            if (finished.get()) return;
                            checkLive("udp.io");
                            T value = attempt.tryOnce();
                            if (finished.get()) return;
                            if (value != null) {
                                complete(value, null);
                                return;
                            }
                            OresFuture<Integer> ready = context.vm().nioReadiness()
                                    .watch(kernel.channel(), interest);
                            watch.set(ready);
                            if (finished.get()) {
                                ready.cancel(false);
                                return;
                            }
                            ready.whenCompleteRuntime((mask, error) -> {
                                if (finished.get()) return;
                                if (error != null) {
                                    complete(null, OresFuture.unwrap(error));
                                } else {
                                    tick();
                                }
                            });
                        } catch (IOException | RuntimeException failure) {
                            complete(null, failure);
                        } finally {
                            stepping.set(false);
                            if (finished.get()) {
                                operating.set(false);
                            } else if (retryRequested.getAndSet(false)) {
                                tick();
                            }
                        }
                    }
                }
                new Retry().tick();
            }, () -> {
                if (finished.compareAndSet(false, true)) {
                    // A canceled one-shot watch may still have a queued
                    // selector retirement command. Reusing its channel before
                    // that physical retirement would violate the reactor's
                    // one-watch-per-channel rule. Fail closed by retiring this
                    // UDP socket on cancellation, like NIO TCP; a new socket
                    // must be explicitly bound for subsequent operations.
                    OresFuture<Integer> pending = watch.get();
                    if (pending != null) pending.cancel(false);
                    try {
                        close();
                    } catch (IOException ignored) {
                        // close() fences new calls via its atomic closed flag,
                        // even if the OS reports a close error.
                    }
                    // Never free the operation slot while physical I/O may
                    // still be running. Its finally block owns retirement.
                    if (!stepping.get()) operating.set(false);
                }
            });
        }

        public OresFuture<Void> closeAsync() {
            try {
                close();
                return OresFuture.completed(null);
            } catch (IOException | RuntimeException error) {
                return OresFuture.failed(error);
            }
        }

        private void checkLive(String operation) {
            if (closed.get()) throw new IllegalStateException("UDP socket closed");
            context.requireCapability(IsolatePolicy.Capability.NETWORK, operation);
            context.requirePermission(RuntimePermissions.Permission.NET,
                    "127.0.0.1:" + kernel.requestedBindPort(), operation);
        }

        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            // Close forces the readiness watch to fail, and the asynchronous
            // operation will reject rather than publish packet bytes later.
            context.nativeIo().unregister(this);
            kernel.close();
        }
    }
}
