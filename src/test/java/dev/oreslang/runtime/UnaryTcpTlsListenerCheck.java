package dev.oreslang.runtime;

import java.io.DataInputStream;
import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/** Real mTLS loopback for supervisor accept capacity, reply and graceful shutdown. */
public final class UnaryTcpTlsListenerCheck {
    private UnaryTcpTlsListenerCheck() { }

    private static void require(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    @SuppressWarnings("try") // Intentional early shutdown before waiting for accept-loop exit.
    public static void main(String[] args) throws Exception {
        if (args.length != 1)
            throw new IllegalArgumentException("expected disposable TLS fixture directory");
        Path fixtures = Path.of(args[0]);
        SSLContext server = UnaryTcpMutualTlsLoopbackCheck.context(
                fixtures.resolve("server.p12"), fixtures.resolve("server-trust.p12"));
        SSLContext client = UnaryTcpMutualTlsLoopbackCheck.context(
                fixtures.resolve("client.p12"), fixtures.resolve("client-trust.p12"));

        var limits = new UnaryTcpSessionAdapter.Limits(
                1, 2, 64, 2, 128, 1024, 2000, 1000, 1000);
        UnaryTcpSessionAdapter.Dispatcher echo = request ->
                new UnaryTcpSessionAdapter.DispatchHandle() {
                    @Override public void whenComplete(
                            BiConsumer<? super UnaryTcpSessionAdapter.Reply,
                                       ? super Throwable> completion) {
                        completion.accept(
                                UnaryTcpSessionAdapter.Reply.success(request.payload()), null);
                    }

                    @Override public boolean cancel() { return true; }
                };

        try (SSLServerSocket endpoint = (SSLServerSocket)
                server.getServerSocketFactory().createServerSocket(
                        0, 4, InetAddress.getLoopbackAddress());
             var adapterListener = new UnaryTcpTlsListener(
                     endpoint, limits, certificate -> {
                         if (!certificate.getSubjectX500Principal().getName()
                                 .contains("oreslang-test-client"))
                             throw new SecurityException("unauthorized test principal");
                         return "test:approved";
                     }, echo);
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var accepting = pool.submit(() -> {
                try {
                    adapterListener.serve();
                } catch (java.io.IOException failure) {
                    throw new AssertionError(failure);
                }
            });
            try (SSLSocket socket = (SSLSocket)
                    client.getSocketFactory().createSocket(
                            InetAddress.getLoopbackAddress(), endpoint.getLocalPort())) {
                socket.setSoTimeout(3000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                long sessionId = input.readLong();
                long generation = input.readLong();
                require(sessionId > 0 && generation > 0,
                        "listener authenticated the channel before session binding");

                byte[] body = "opaque-request".getBytes(StandardCharsets.UTF_8);
                var request = new UnaryTransportFrame.Envelope(
                        UnaryTransportFrame.Kind.REQUEST, sessionId, generation, 1, body);
                UnaryTransportFrame.writeTcp(socket.getOutputStream(), request);
                socket.getOutputStream().flush();
                var result = UnaryTransportFrame.readTcp(socket.getInputStream(), 1024);
                require(result.kind() == UnaryTransportFrame.Kind.REPLY
                                && result.correlationId() == 1
                                && Arrays.equals(result.payload(), body),
                        "mTLS unary listener must dispatch and return one correlated reply");
                require(adapterListener.availableConnectionSlots() == 0,
                        "one connection must consume the only admission slot");
            } finally {
                adapterListener.close();
            }
            accepting.get(3, TimeUnit.SECONDS);
            require(adapterListener.activeConnections() == 0,
                    "listener shutdown must retire adapter connection credits");
        }
        System.out.println("PASS: TLS 1.3 supervisor listener admission, unary reply and shutdown");
    }
}
