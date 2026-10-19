package dev.oreslang.runtime;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Host-owned authenticated TCP unary session adapter.
 *
 * <p>The authenticator owns the authentication wire protocol and must bind the
 * supervisor-minted session/generation to the authenticated peer before it
 * returns. No unary envelope is read before authentication succeeds. Envelope
 * IDs are correlation fences, never authentication credentials.</p>
 *
 * <p>Requests are admitted from the fixed TCP header before payload allocation.
 * Count and payload-byte credits are bounded both per session and globally.
 * A global work credit remains held through queued terminal delivery, preventing
 * slow socket writers from turning completed requests into an unbounded response
 * queue. Guest/actor dispatch is callback-only; socket I/O always runs on
 * transport-owned threads.</p>
 */
final class UnaryTcpSessionAdapter implements AutoCloseable {
    record Limits(
            int maxConnections,
            int maxRequestsPerConnection,
            long maxPayloadBytesPerConnection,
            int maxGlobalWorkItems,
            long maxGlobalPayloadBytes,
            int maxPayloadBytes,
            int readDeadlineMillis,
            int requestDeadlineMillis,
            int writeDeadlineMillis) {
        Limits {
            if (maxConnections < 1 || maxConnections > 65536
                    || maxRequestsPerConnection < 1 || maxRequestsPerConnection > 65536
                    || maxGlobalWorkItems < 1 || maxGlobalWorkItems > 1_000_000
                    || maxPayloadBytes < 0 || maxPayloadBytes > UnaryTransportFrame.MAX_PAYLOAD_BYTES
                    || maxPayloadBytesPerConnection < 0
                    || maxPayloadBytesPerConnection
                            > (long) maxRequestsPerConnection * maxPayloadBytes
                    || maxGlobalPayloadBytes < 0
                    || maxGlobalPayloadBytes > (long) maxGlobalWorkItems * maxPayloadBytes
                    || readDeadlineMillis < 1 || requestDeadlineMillis < 1
                    || writeDeadlineMillis < 1) {
                throw new IllegalArgumentException("invalid unary TCP adapter limits");
            }
        }
    }

    record PeerIdentity(String principal) {
        PeerIdentity {
            Objects.requireNonNull(principal, "principal");
            if (principal.isBlank() || principal.length() > 4096
                    || principal.chars().anyMatch(ch -> ch < 32 || ch == 127)) {
                throw new IllegalArgumentException("invalid authenticated peer identity");
            }
        }
    }

    /**
     * Authenticate the socket and securely communicate/bind the supplied
     * supervisor-minted session ID and generation to that peer. Implementations
     * may use mTLS, a Noise-like handshake, or another reviewed mechanism.
     */
    @FunctionalInterface
    interface Authenticator {
        PeerIdentity authenticateAndBind(
                Socket socket,
                long sessionId,
                long generation) throws Exception;
    }

    record Request(
            PeerIdentity peer,
            long sessionId,
            long generation,
            long correlationId,
            byte[] payload) {
        Request {
            Objects.requireNonNull(peer, "peer");
            Objects.requireNonNull(payload, "payload");
            if (sessionId <= 0 || generation <= 0 || correlationId <= 0)
                throw new IllegalArgumentException("invalid unary TCP request identity");
            if (payload.length > UnaryTransportFrame.MAX_PAYLOAD_BYTES)
                throw new IllegalArgumentException("unary TCP request exceeds protocol byte limit");
            payload = payload.clone();
        }

        @Override public byte[] payload() { return payload.clone(); }

        /** Data-only message suitable for a runtime actor transport boundary. */
        Map<String, Object> actorMessage() {
            return Map.of(
                    "peer", peer.principal(),
                    "session_id", sessionId,
                    "generation", generation,
                    "correlation_id", correlationId,
                    "payload", OresBytes.wrapTrusted(payload));
        }
    }

    record Reply(UnaryTransportFrame.Kind kind, byte[] payload) {
        Reply {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(payload, "payload");
            if (kind != UnaryTransportFrame.Kind.REPLY
                    && kind != UnaryTransportFrame.Kind.ERROR) {
                throw new IllegalArgumentException("terminal TCP actor result must be REPLY or ERROR");
            }
            if (payload.length > UnaryTransportFrame.MAX_PAYLOAD_BYTES)
                throw new IllegalArgumentException("unary TCP reply exceeds protocol byte limit");
            if (kind == UnaryTransportFrame.Kind.ERROR && payload.length != 0)
                throw new IllegalArgumentException("unary TCP ERROR frames must be bodyless");
            payload = payload.clone();
        }

        @Override public byte[] payload() { return payload.clone(); }

        static Reply success(byte[] payload) {
            return new Reply(UnaryTransportFrame.Kind.REPLY, payload);
        }

        static Reply error() {
            return new Reply(UnaryTransportFrame.Kind.ERROR, new byte[0]);
        }
    }

    /**
     * Dispatch completion registration must not execute guest code. The adapter
     * only registers a host callback which schedules socket delivery on its own
     * I/O executor.
     */
    interface DispatchHandle {
        void whenComplete(BiConsumer<? super Reply, ? super Throwable> completion);
        boolean cancel();
    }

    @FunctionalInterface
    interface Dispatcher {
        DispatchHandle dispatch(Request request) throws Exception;
    }

    private static final class GlobalBudget {
        private final int maxConnections;
        private final int maxWorkItems;
        private final long maxPayloadBytes;
        private int connections;
        private int workItems;
        private long payloadBytes;

        GlobalBudget(Limits limits) {
            maxConnections = limits.maxConnections();
            maxWorkItems = limits.maxGlobalWorkItems();
            maxPayloadBytes = limits.maxGlobalPayloadBytes();
        }

        synchronized ConnectionCredit tryConnection() {
            if (connections >= maxConnections) return null;
            connections++;
            return new ConnectionCredit(this);
        }

        synchronized WorkCredit tryWork(int bytes) {
            if (bytes < 0) throw new IllegalArgumentException("negative work payload");
            if (workItems >= maxWorkItems || bytes > maxPayloadBytes - payloadBytes) return null;
            workItems++;
            payloadBytes += bytes;
            return new WorkCredit(this, bytes);
        }

        synchronized boolean convert(WorkCredit credit, int replacementBytes) {
            if (credit.closed || credit.owner != this || replacementBytes < 0)
                return false;
            long withoutCurrent = payloadBytes - credit.bytes;
            if (withoutCurrent < 0 || replacementBytes > maxPayloadBytes - withoutCurrent)
                return false;
            payloadBytes = withoutCurrent + replacementBytes;
            credit.bytes = replacementBytes;
            return true;
        }

        synchronized void releaseConnection(ConnectionCredit credit) {
            if (credit.closed) return;
            credit.closed = true;
            if (connections <= 0) throw new IllegalStateException("global TCP connection accounting underflow");
            connections--;
        }

        synchronized void releaseWork(WorkCredit credit) {
            if (credit.closed) return;
            credit.closed = true;
            if (workItems <= 0 || payloadBytes < credit.bytes)
                throw new IllegalStateException("global TCP work accounting underflow");
            workItems--;
            payloadBytes -= credit.bytes;
        }

        synchronized int connections() { return connections; }
        synchronized int workItems() { return workItems; }
        synchronized long payloadBytes() { return payloadBytes; }
    }

    private static final class ConnectionCredit implements AutoCloseable {
        private final GlobalBudget owner;
        private boolean closed;

        ConnectionCredit(GlobalBudget owner) { this.owner = owner; }

        @Override public void close() { owner.releaseConnection(this); }
    }

    private static final class WorkCredit implements AutoCloseable {
        private final GlobalBudget owner;
        private int bytes;
        private boolean closed;

        WorkCredit(GlobalBudget owner, int bytes) {
            this.owner = owner;
            this.bytes = bytes;
        }

        boolean convertToResponse(int replacementBytes) {
            return owner.convert(this, replacementBytes);
        }

        @Override public void close() { owner.releaseWork(this); }
    }

    private final Limits limits;
    private final Authenticator authenticator;
    private final Dispatcher dispatcher;
    private final GlobalBudget global;
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "ores-unary-tcp-deadlines");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Session> sessions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Includes pre-authentication sockets, which are not yet Session instances.
    private final Set<Socket> admittedSockets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor cancellationWorkers;
    private final AtomicLong rejectedCancellationTasks = new AtomicLong();
    private long nextSessionId = 1;
    private long nextGeneration = 1;

    UnaryTcpSessionAdapter(Limits limits, Authenticator authenticator, Dispatcher dispatcher) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.global = new GlobalBudget(limits);
        // A blocking or non-cooperative actor cancellation callback must never
        // monopolize the socket thread or the one-thread deadline scheduler.
        // Fixed daemon workers + a finite queue bound retained cancellation work.
        cancellationWorkers = new ThreadPoolExecutor(2, 2, 0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.min(limits.maxGlobalWorkItems(), 256)),
                runnable -> {
                    Thread worker = new Thread(runnable, "ores-unary-tcp-cancel");
                    worker.setDaemon(true);
                    return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
        timers.setRemoveOnCancelPolicy(true);
        timers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /**
     * Serve one already-accepted socket until EOF/protocol failure/session close.
     * Call this from a supervisor-owned accept/virtual-thread loop, never an
     * actor carrier. Capacity rejection closes the socket before authentication.
     */
    void serve(Socket socket) {
        Objects.requireNonNull(socket, "socket");
        if (closed.get()) {
            closeQuietly(socket);
            return;
        }

        ConnectionCredit connection = global.tryConnection();
        if (connection == null) {
            closeQuietly(socket);
            return;
        }

        admittedSockets.add(socket);
        Session session = null;
        try {
            // Shutdown can race connection admission; never begin auth after it wins.
            if (closed.get()) return;
            long[] identity = mintSessionIdentity();
            socket.setSoTimeout(limits.readDeadlineMillis());
            PeerIdentity peer;
            try (ReadWindow authentication = new ReadWindow(socket)) {
                peer = Objects.requireNonNull(
                        authenticator.authenticateAndBind(socket, identity[0], identity[1]),
                        "authenticator returned null peer identity");
                if (authentication.expired()) return;
            }
            if (closed.get() || socket.isClosed()) return;
            session = new Session(socket, connection, peer, identity[0], identity[1]);
            sessions.add(session);
            session.run();
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Authentication, protocol and transport failures are session-local.
            // Never serialize arbitrary host exception text onto the wire.
        } finally {
            admittedSockets.remove(socket);
            if (session != null) session.close();
            else {
                closeQuietly(socket);
                connection.close();
            }
        }
    }

    private synchronized long[] mintSessionIdentity() {
        if (closed.get()) throw new IllegalStateException("unary TCP adapter closed");
        if (nextSessionId <= 0 || nextSessionId == Long.MAX_VALUE
                || nextGeneration <= 0 || nextGeneration == Long.MAX_VALUE) {
            throw new IllegalStateException("unary TCP session identity space exhausted");
        }
        return new long[] { nextSessionId++, nextGeneration++ };
    }

    int activeConnections() { return global.connections(); }
    int activeGlobalWorkItems() { return global.workItems(); }
    long activeGlobalPayloadBytes() { return global.payloadBytes(); }
    long rejectedCancellationTasks() { return rejectedCancellationTasks.get(); }

    /**
     * Absolute bound for one authentication handshake or one wire frame.
     * SO_TIMEOUT alone limits a single socket read and is bypassable by a peer
     * that sends one byte before each idle timeout. A terminal close aborts
     * the whole socket rather than leaving an ambiguous partial frame.
     */
    private final class ReadWindow implements AutoCloseable {
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final ScheduledFuture<?> deadline;
        private volatile boolean expired;

        ReadWindow(Socket socket) {
            deadline = timers.schedule(() -> {
                if (active.compareAndSet(true, false)) {
                    expired = true;
                    closeQuietly(socket);
                }
            }, limits.readDeadlineMillis(), TimeUnit.MILLISECONDS);
        }

        boolean expired() { return expired; }

        @Override public void close() {
            active.set(false);
            deadline.cancel(false);
        }
    }

    private final class Session implements AutoCloseable {
        private final Socket socket;
        private final ConnectionCredit connection;
        private final PeerIdentity peer;
        private final long sessionId;
        private final long generation;
        private final Object lock = new Object();
        private final Object writeLock = new Object();
        private final Map<Long, Pending> pending = new HashMap<>();
        private final Set<WorkCredit> responses = new HashSet<>();
        private final UnaryInboundRequestRegistry inbound;
        private long responsePayloadBytes;
        private volatile boolean ended;

        Session(Socket socket, ConnectionCredit connection, PeerIdentity peer,
                long sessionId, long generation) {
            this.socket = socket;
            this.connection = connection;
            this.peer = peer;
            this.sessionId = sessionId;
            this.generation = generation;
            this.inbound = new UnaryInboundRequestRegistry(
                    sessionId,
                    generation,
                    limits.maxRequestsPerConnection(),
                    limits.maxPayloadBytesPerConnection());
        }

        void run() throws IOException {
            while (!ended && !closed.get()) {
                try (ReadWindow frameRead = new ReadWindow(socket)) {
                    UnaryTransportFrame.TcpHead head =
                            UnaryTransportFrame.readTcpHead(socket.getInputStream(), limits.maxPayloadBytes());
                    authenticate(head);
                    switch (head.kind()) {
                        case REQUEST -> receiveRequest(head, frameRead);
                        case CANCEL -> {
                            frameRead.close();
                            receiveCancel(head.correlationId());
                        }
                        case REPLY, ERROR -> throw new IOException(
                                "client sent server-terminal unary frame");
                    }
                }
            }
        }

        private void authenticate(UnaryTransportFrame.TcpHead head) {
            if (head.sessionId() != sessionId || head.generation() != generation) {
                throw new SecurityException("foreign unary TCP session binding");
            }
        }

        private void receiveRequest(UnaryTransportFrame.TcpHead head,
                                    ReadWindow frameRead) throws IOException {
            Pending admitted;
            synchronized (lock) {
                requireOpen();

                boolean sessionAdmitted = inbound.admit(
                        head.sessionId(),
                        head.generation(),
                        head.correlationId(),
                        head.payloadBytes());
                if (!sessionAdmitted) {
                    admitted = null;
                } else if (inbound.activePayloadBytes() + responsePayloadBytes
                        > limits.maxPayloadBytesPerConnection()) {
                    if (!inbound.complete(sessionId, generation, head.correlationId()))
                        throw new IllegalStateException(
                                "unary TCP inbound ledger lost rejected request");
                    admitted = null;
                } else {
                    WorkCredit globalCredit = global.tryWork(head.payloadBytes());
                    if (globalCredit == null) {
                        if (!inbound.complete(sessionId, generation, head.correlationId()))
                            throw new IllegalStateException(
                                    "unary TCP inbound ledger lost global-rejected request");
                        admitted = null;
                    } else {
                        admitted = new Pending(
                                head.correlationId(), head.payloadBytes(), globalCredit);
                        pending.put(head.correlationId(), admitted);
                    }
                }
            }

            if (admitted == null) {
                UnaryTransportFrame.discardTcpPayload(socket.getInputStream(), head);
                frameRead.close();
                writeNow(new UnaryTransportFrame.Envelope(
                        UnaryTransportFrame.Kind.ERROR,
                        sessionId, generation, head.correlationId(), new byte[0]));
                return;
            }

            final byte[] payload;
            try {
                payload = UnaryTransportFrame.readTcpPayloadBytes(socket.getInputStream(), head);
                frameRead.close();
            } catch (IOException failure) {
                retireWithoutReply(admitted, true);
                throw failure;
            }
            if (frameRead.expired() || socket.isClosed()) {
                retireWithoutReply(admitted, true);
                throw new IOException("unary TCP frame expired before dispatch");
            }

            Request request = new Request(
                    peer, sessionId, generation, head.correlationId(), payload);
            ScheduledFuture<?> deadline = timers.schedule(
                    () -> timeout(admitted),
                    limits.requestDeadlineMillis(),
                    TimeUnit.MILLISECONDS);
            if (!attachDeadline(admitted, deadline)) {
                deadline.cancel(false);
                return;
            }

            final DispatchHandle handle;
            try {
                handle = Objects.requireNonNull(
                        dispatcher.dispatch(request),
                        "unary TCP dispatcher returned null handle");
            } catch (Error fatal) {
                finish(admitted, Reply.error(), false);
                throw fatal;
            } catch (Throwable failure) {
                finish(admitted, Reply.error(), false);
                return;
            }

            if (!attachHandle(admitted, handle)) {
                cancelBestEffort(handle);
                return;
            }

            try {
                handle.whenComplete((reply, failure) -> {
                    try {
                        if (failure != null || reply == null) {
                            finish(admitted, Reply.error(), false);
                        } else {
                            finish(admitted, reply, false);
                        }
                    } catch (Error fatal) {
                        close();
                        throw fatal;
                    } catch (Throwable ignored) {
                        finish(admitted, Reply.error(), false);
                    }
                });
            } catch (Error fatal) {
                finish(admitted, Reply.error(), true);
                throw fatal;
            } catch (Throwable failure) {
                finish(admitted, Reply.error(), true);
            }
        }

        private boolean attachDeadline(Pending admitted, ScheduledFuture<?> deadline) {
            synchronized (lock) {
                if (ended || pending.get(admitted.correlationId) != admitted) return false;
                admitted.deadline = deadline;
                return true;
            }
        }

        private boolean attachHandle(Pending admitted, DispatchHandle handle) {
            synchronized (lock) {
                if (ended || pending.get(admitted.correlationId) != admitted) return false;
                if (admitted.handle != null)
                    throw new IllegalStateException("unary TCP dispatch handle already attached");
                admitted.handle = handle;
                return true;
            }
        }

        private void timeout(Pending admitted) {
            finish(admitted, Reply.error(), true);
        }

        private void receiveCancel(long correlationId) {
            Pending admitted;
            synchronized (lock) {
                requireOpen();
                if (!inbound.cancel(sessionId, generation, correlationId)) return;
                admitted = pending.remove(correlationId);
                if (admitted == null)
                    throw new IllegalStateException(
                            "unary TCP inbound ledger/request state diverged on cancellation");
                if (admitted.deadline != null) admitted.deadline.cancel(false);
            }
            DispatchHandle handle = admitted.handle;
            if (handle != null) cancelBestEffort(handle);
            admitted.credit.close();
        }

        /**
         * Select one terminal outcome. Request credit is atomically transformed
         * into queued-response credit before scheduling socket I/O.
         */
        private boolean finish(Pending admitted, Reply requested, boolean cancelDispatch) {
            Objects.requireNonNull(requested, "requested");
            Reply reply = requested;
            byte[] body = reply.payload();
            if (body.length > limits.maxPayloadBytes()) {
                reply = Reply.error();
                body = reply.payload();
            }

            DispatchHandle handle;
            WorkCredit credit;
            synchronized (lock) {
                if (ended || pending.get(admitted.correlationId) != admitted) return false;
                pending.remove(admitted.correlationId);
                if (!inbound.complete(sessionId, generation, admitted.correlationId))
                    throw new IllegalStateException(
                            "unary TCP inbound ledger/request state diverged on completion");
                if (admitted.deadline != null) admitted.deadline.cancel(false);
                handle = admitted.handle;
                credit = admitted.credit;

                if (body.length
                        > limits.maxPayloadBytesPerConnection()
                                - inbound.activePayloadBytes() - responsePayloadBytes) {
                    reply = Reply.error();
                    body = reply.payload();
                }
                if (!credit.convertToResponse(body.length)) {
                    reply = Reply.error();
                    body = reply.payload();
                    if (!credit.convertToResponse(0))
                        throw new IllegalStateException("cannot shrink unary TCP request credit to empty error");
                }
                responses.add(credit);
                responsePayloadBytes += credit.bytes;
            }

            if (cancelDispatch && handle != null) cancelBestEffort(handle);

            UnaryTransportFrame.Envelope terminal = new UnaryTransportFrame.Envelope(
                    reply.kind(), sessionId, generation, admitted.correlationId, body);
            scheduleWrite(credit, terminal);
            return true;
        }

        private void scheduleWrite(WorkCredit credit, UnaryTransportFrame.Envelope terminal) {
            try {
                io.execute(() -> {
                    try {
                        if (!ended) writeNow(terminal);
                    } catch (IOException failure) {
                        close();
                    } finally {
                        synchronized (lock) {
                            if (responses.remove(credit)) {
                                responsePayloadBytes -= credit.bytes;
                                if (responsePayloadBytes < 0)
                                    throw new IllegalStateException(
                                            "unary TCP response byte accounting underflow");
                            }
                        }
                        credit.close();
                    }
                });
            } catch (RejectedExecutionException unavailable) {
                synchronized (lock) {
                    if (responses.remove(credit)) {
                        responsePayloadBytes -= credit.bytes;
                        if (responsePayloadBytes < 0)
                            throw new IllegalStateException(
                                    "unary TCP response byte accounting underflow");
                    }
                }
                credit.close();
                close();
            }
        }

        private void writeNow(UnaryTransportFrame.Envelope terminal) throws IOException {
            ScheduledFuture<?> deliveryDeadline = timers.schedule(
                    this::close,
                    limits.writeDeadlineMillis(),
                    TimeUnit.MILLISECONDS);
            try {
                synchronized (writeLock) {
                    if (ended || socket.isClosed()) return;
                    UnaryTransportFrame.writeTcp(socket.getOutputStream(), terminal);
                    socket.getOutputStream().flush();
                }
            } finally {
                deliveryDeadline.cancel(false);
            }
        }

        private void retireWithoutReply(Pending admitted, boolean cancelDispatch) {
            DispatchHandle handle;
            synchronized (lock) {
                if (pending.remove(admitted.correlationId, admitted)) {
                    if (!inbound.complete(sessionId, generation, admitted.correlationId))
                        throw new IllegalStateException(
                                "unary TCP inbound ledger/request state diverged on retirement");
                    if (admitted.deadline != null) admitted.deadline.cancel(false);
                } else {
                    return;
                }
                handle = admitted.handle;
            }
            if (cancelDispatch && handle != null) cancelBestEffort(handle);
            admitted.credit.close();
        }

        int activeRequests() {
            synchronized (lock) { return pending.size(); }
        }

        long activeRequestPayloadBytes() {
            synchronized (lock) { return inbound.activePayloadBytes(); }
        }

        long activeResponsePayloadBytes() {
            synchronized (lock) { return responsePayloadBytes; }
        }

        private void requireOpen() {
            if (ended) throw new IllegalStateException("unary TCP session closed");
        }

        @Override public void close() {
            List<Pending> toCancel;
            List<WorkCredit> toRelease;
            synchronized (lock) {
                if (ended) return;
                ended = true;
                toCancel = List.copyOf(pending.values());
                pending.clear();
                inbound.close();
                responsePayloadBytes = 0;
                toRelease = List.copyOf(responses);
                responses.clear();
                for (Pending admitted : toCancel) {
                    if (admitted.deadline != null) admitted.deadline.cancel(false);
                }
            }

            closeQuietly(socket);
            for (Pending admitted : toCancel) {
                cancelBestEffort(admitted.handle);
                admitted.credit.close();
            }
            for (WorkCredit credit : toRelease) credit.close();
            sessions.remove(this);
            connection.close();
        }
    }

    private static final class Pending {
        private final long correlationId;
        private final int requestBytes;
        private final WorkCredit credit;
        private volatile DispatchHandle handle;
        private volatile ScheduledFuture<?> deadline;

        Pending(long correlationId, int requestBytes, WorkCredit credit) {
            this.correlationId = correlationId;
            this.requestBytes = requestBytes;
            this.credit = credit;
        }
    }

    /**
     * Dispatch cancellation is advisory. Never call an arbitrary actor callback
     * on the I/O or timer thread. Both running callbacks and queued callbacks
     * have strict host-owned bounds, even if callbacks ignore interruption.
     */
    private void cancelBestEffort(DispatchHandle handle) {
        if (handle == null) return;
        try {
            cancellationWorkers.execute(() -> {
                try {
                    handle.cancel();
                } catch (Throwable ignored) {
                    // Never let an actor cancellation failure interrupt cleanup.
                }
            });
        } catch (RejectedExecutionException saturated) {
            // The adapter has reclaimed the request's credits already. The
            // callback is best effort; retain a visible diagnostic counter.
            rejectedCancellationTasks.incrementAndGet();
        }
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // Close unauthenticated handshakes as well as established sessions.
        // An in-flight serve() rechecks closed after socket registration.
        for (Socket socket : List.copyOf(admittedSockets)) closeQuietly(socket);
        for (Session session : List.copyOf(sessions)) session.close();
        timers.shutdownNow();
        cancellationWorkers.shutdown();
        io.shutdownNow();
    }
}
