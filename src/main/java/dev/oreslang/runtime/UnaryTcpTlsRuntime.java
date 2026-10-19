package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * Supervisor/runtime authority boundary for binding unary TLS listeners.
 *
 * <p>The low-level listener accepts an already-bound server socket so its
 * protocol/lifecycle tests stay JDK-only. Production Oreslang code must enter
 * through this facade, which performs the same scoped NET permission check as
 * NativeHttp before creating or binding the endpoint.</p>
 */
final class UnaryTcpTlsRuntime {
    private UnaryTcpTlsRuntime() { }

    static UnaryTcpTlsListener bind(
            OresContext context,
            SSLContext tlsContext,
            String host,
            int port,
            int backlog,
            UnaryTcpSessionAdapter.Limits limits,
            UnaryTcpMutualTlsAuthenticator.CertificateAuthorizer authorizer,
            UnaryTcpSessionAdapter.Dispatcher dispatcher) throws IOException {
        if (ActorRuntime.inActorExecution())
            throw new SecurityException("unary TCP listeners belong to the supervisor");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(tlsContext, "tlsContext");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(authorizer, "authorizer");
        Objects.requireNonNull(dispatcher, "dispatcher");
        if (host == null || host.isBlank()
                || port < 0 || port > 65535
                || backlog < 1 || backlog > 65536) {
            throw new IllegalArgumentException("invalid unary TCP listener endpoint");
        }

        context.requirePermission(
                RuntimePermissions.Permission.NET,
                RuntimePermissions.networkEndpoint(host, port),
                "unary.tcp.listen");

        SSLServerSocket endpoint = (SSLServerSocket)
                tlsContext.getServerSocketFactory().createServerSocket();
        boolean transferred = false;
        try {
            endpoint.bind(new InetSocketAddress(host, port), backlog);
            UnaryTcpTlsListener listener = new UnaryTcpTlsListener(
                    endpoint, limits, authorizer, dispatcher);
            transferred = true;
            return listener;
        } finally {
            if (!transferred) {
                try {
                    endpoint.close();
                } catch (IOException ignored) {
                    // Preserve the original bind/configuration failure.
                }
            }
        }
    }
}
