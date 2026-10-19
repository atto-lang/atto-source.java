package dev.oreslang.runtime;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Set<Socket> acceptedSockets =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean serving = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread acceptingThread;

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

                Socket accepted = null;
                try {
                    accepted = listener.accept();
                    acceptedSockets.add(accepted);
                    if (closed.get()) {
                        acceptedSockets.remove(accepted);
                        closeQuietly(accepted);
                        connectionSlots.release();
                        break;
                    }
                    final Socket socket = accepted;
                    connectionWorkers.execute(() -> {
                        try {
                            adapter.serve(socket);
                        } finally {
                            acceptedSockets.remove(socket);
                            connectionSlots.release();
                        }
                    });
                } catch (IOException | RejectedExecutionException failure) {
                    if (accepted != null) {
                        acceptedSockets.remove(accepted);
                        closeQuietly(accepted);
                    }
                    connectionSlots.release();
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
        for (Socket socket : List.copyOf(acceptedSockets)) closeQuietly(socket);
        adapter.close();
        Thread accepting = acceptingThread;
        if (accepting != null && accepting != Thread.currentThread())
            accepting.interrupt();
        connectionWorkers.shutdownNow();
    }
}
