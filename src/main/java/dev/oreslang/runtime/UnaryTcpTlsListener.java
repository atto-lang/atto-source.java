package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * Supervisor-owned opt-in TLS 1.3 unary TCP accept loop.
 *
 * <p>Requires a previously bound SSLServerSocket built with an independently
 * reviewed server key and client-certificate trust policy. This listener
 * force-enables TLS 1.3 and client authentication, and constructs the concrete
 * certificate-authorizing unary authenticator. It never accepts plaintext.</p>
 *
 * <p>A fair semaphore is acquired BEFORE accept(), so the listener cannot
 * create unbounded virtual threads or accepted connections under overload.
 * Backlog, bind address, per-peer rate limits and deployment permissions remain
 * responsibilities of the host that constructs the SSLServerSocket.</p>
 */
final class UnaryTcpTlsListener implements AutoCloseable {
    private final SSLServerSocket listener;
    private final UnaryTcpSessionAdapter adapter;
    private final Semaphore connectionSlots;
    private final ExecutorService connectionWorkers =
            Executors.newVirtualThreadPerTaskExecutor();
    private final Set<AcceptedConnection> acceptedConnections =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean serving = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread acceptingThread;

    /**
     * Production bind path: enforce supervisor ownership and scoped NET
     * permission before the socket is created or bound.
     */
    static UnaryTcpTlsListener bind(
            OresContext context,
            SSLContext tlsContext,
            String host,
            int port,
            int backlog,
            UnaryTcpSessionAdapter.Limits limits,
            UnaryTcpMutualTlsAuthenticator.CertificateAuthorizer authorizer,
            UnaryTcpSessionAdapter.Dispatcher dispatcher) throws IOException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(tlsContext, "tlsContext");
        if (ActorRuntime.inActorExecution())
            throw new SecurityException("unary TCP listeners belong to the supervisor");
        if (host == null || host.isBlank()
                || port < 0 || port > 65535
                || backlog < 1 || backlog > 65536) {
            throw new IllegalArgumentException("invalid unary TCP listener endpoint");
        }

        context.requirePermission(
                RuntimePermissions.Permission.NET,
                host.toLowerCase(Locale.ROOT) + ":" + port,
                "unary.tcp.listen");

        SSLServerSocket endpoint = (SSLServerSocket)
                tlsContext.getServerSocketFactory().createServerSocket();
        boolean transferred = false;
        try {
            endpoint.bind(new InetSocketAddress(host, port), backlog);
            UnaryTcpTlsListener bound = new UnaryTcpTlsListener(
                    endpoint, limits, authorizer, dispatcher);
            transferred = true;
            return bound;
        } finally {
            if (!transferred) {
                try {
                    endpoint.close();
                } catch (IOException ignored) {
                    // Preserve the bind/configuration failure.
                }
            }
        }
    }

    UnaryTcpTlsListener(
            SSLServerSocket listener,
            UnaryTcpSessionAdapter.Limits limits,
            UnaryTcpMutualTlsAuthenticator.CertificateAuthorizer authorizer,
            UnaryTcpSessionAdapter.Dispatcher dispatcher) {
        this.listener = Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(authorizer, "authorizer");
        Objects.requireNonNull(dispatcher, "dispatcher");
        if (!listener.isBound() || listener.isClosed())
            throw new IllegalArgumentException("TLS unary listener must be bound and open");

        // Fail closed if TLS 1.3 is unsupported; never silently permit legacy TLS.
        listener.setEnabledProtocols(new String[] {"TLSv1.3"});
        listener.setNeedClientAuth(true);
        this.adapter = new UnaryTcpSessionAdapter(
                limits, new UnaryTcpMutualTlsAuthenticator(authorizer), dispatcher);
        this.connectionSlots = new Semaphore(limits.maxConnections(), true);
    }

    /** Blocks in the host/supervisor accept thread; never call on actor carriers. */
    void serve() throws IOException {
        if (!serving.compareAndSet(false, true))
            throw new IllegalStateException("TLS unary listener already serving");
        acceptingThread = Thread.currentThread();
        try {
            while (!closed.get()) {
                try {
                    connectionSlots.acquire();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    if (closed.get()) break;
                    throw new IOException("TLS unary listener interrupted", interrupted);
                }
                if (closed.get()) {
                    connectionSlots.release();
                    break;
                }

                AcceptedConnection credit = null;
                try {
                    Socket socket = listener.accept();
                    credit = new AcceptedConnection(socket);
                    acceptedConnections.add(credit);
                    if (closed.get()) {
                        credit.close();
                        break;
                    }
                    final AcceptedConnection admitted = credit;
                    connectionWorkers.execute(() -> {
                        try {
                            adapter.serve(socket);
                        } finally {
                            admitted.retire();
                        }
                    });
                } catch (IOException | RejectedExecutionException failure) {
                    if (credit == null) connectionSlots.release();
                    else credit.close();
                    if (!closed.get()) throw failure;
                    break;
                }
            }
        } finally {
            close();
            acceptingThread = null;
            serving.set(false);
        }
    }

    int activeConnections() { return adapter.activeConnections(); }
    int availableConnectionSlots() { return connectionSlots.availablePermits(); }

    /**
     * A leased accepted socket may be closed before its virtual thread starts.
     * Retirement is idempotent across worker completion, submission failure
     * and concurrent listener shutdown; the capacity permit is never doubled.
     */
    private final class AcceptedConnection implements AutoCloseable {
        private final Socket socket;
        private final AtomicBoolean retired = new AtomicBoolean();

        AcceptedConnection(Socket socket) {
            this.socket = socket;
        }

        void retire() {
            if (!retired.compareAndSet(false, true)) return;
            acceptedConnections.remove(this);
            connectionSlots.release();
        }

        @Override public void close() {
            closeQuietly(socket);
            retire();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort shutdown of host-owned accepted sockets.
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            listener.close();
        } catch (IOException ignored) {
            // Closing the bound endpoint is best effort.
        }
        for (AcceptedConnection credit : List.copyOf(acceptedConnections))
            credit.close();
        adapter.close();
        Thread accepting = acceptingThread;
        if (accepting != null && accepting != Thread.currentThread())
            accepting.interrupt();
        connectionWorkers.shutdownNow();
    }
}
