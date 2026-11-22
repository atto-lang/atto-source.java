package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Atto VM-owned UDP host binding: real nonblocking socket and readiness,
 * but NOT a guest-visible std.net.udp import until compiler lowering is wired.
 * No untrusted guest may supply the permission callback or Java channel.
 */
public final class AsyncNioUdp {
    private AsyncNioUdp() { }

    public static OresFuture<Socket> bindAsync(OresContext context, String host, int port) {
        Objects.requireNonNull(context, "context");
        if (!"127.0.0.1".equals(host)) {
            return OresFuture.failed(new SecurityException(
                    "UDP stage-2 binding requires numeric IPv4 loopback"));
        }
        try {
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

    public static final class Socket implements AutoCloseable {
        private final OresContext context;
        private final UdpDatagramKernel kernel;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Socket(OresContext context, UdpDatagramKernel kernel) {
            this.context = context;
            this.kernel = kernel;
        }

        public int localPort() throws IOException { return kernel.localPort(); }

        public UdpDatagramKernel.SendOutcome trySendTo(int port, byte[] bytes) throws IOException {
            return kernel.trySendTo(port, bytes);
        }

        public UdpDatagramKernel.Received tryReceiveFrom(int capacity) throws IOException {
            return kernel.tryReceiveFrom(capacity);
        }

        public OresFuture<Integer> whenReadableAsync() {
            try {
                checkLive("udp.when_readable_async");
                return context.vm().nioReadiness().watch(kernel.channel(), SelectionKey.OP_READ);
            } catch (RuntimeException failure) {
                return OresFuture.failed(failure);
            }
        }

        public OresFuture<Integer> whenWritableAsync() {
            try {
                checkLive("udp.when_writable_async");
                return context.vm().nioReadiness().watch(kernel.channel(), SelectionKey.OP_WRITE);
            } catch (RuntimeException failure) {
                return OresFuture.failed(failure);
            }
        }

        private void checkLive(String operation) {
            if (closed.get()) throw new IllegalStateException("UDP socket closed");
            context.requirePermission(RuntimePermissions.Permission.NET,
                    "127.0.0.1:" + kernelBindPort(), operation);
        }

        // Port-0 binding must be rechecked as port 0, not as the assigned
        // ephemeral port. Kernel's operations additionally gate selected peers.
        private int kernelBindPort() { return kernel.requestedBindPort(); }

        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            context.nativeIo().unregister(this);
            kernel.close();
        }
    }
}
