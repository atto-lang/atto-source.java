package dev.oreslang.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded, completion-only RESP2 ingress into ONE Atto-owned KV actor.
 *
 * <p>No host-owned key/value store, blocking sockets or parked actor carriers.
 * Every connection owns at most one request ticket, so per-connection RESP2
 * order holds even while different clients execute concurrently. All DATA
 * goes through ActorRef.sendAsync; its mailbox remains the single writer.
 * Replies are read by one ActorTaggedReplyDispatcher, never by sockets.</p>
 *
 * <p>The finite acceptCount is an explicit lifecycle bound used by executable
 * multi-client conformance. Long-running listener supervision/rolling upgrades
 * and WAL remain separate work; this gateway is not a durability claim.</p>
 */
public final class AsyncNioActorResp2Gateway implements AutoCloseable {
    private static final int MAX_CONNECTIONS = 1024;
    private static final int MAX_ACCEPTED = 1_000_000;

    private final AsyncNioTcp.Listener listener;
    private final ActorRuntime.ActorRef<Object> actor;
    private final int acceptCount;
    private final ActorReplyRouter<ActorTaggedReplyDispatcher.Reply> replies;
    private final ActorTaggedReplyDispatcher outputPump;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean acceptBusy = new AtomicBoolean();
    private final AtomicInteger acceptWip = new AtomicInteger();
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicReference<OresFuture<?>> acceptedFuture = new AtomicReference<>();
    private final AtomicReference<OresFuture.Callback<Void>> completion = new AtomicReference<>();
    private volatile Throwable failure;

    public AsyncNioActorResp2Gateway(
            AsyncNioTcp.Listener listener,
            ActorRuntime.ActorRef<Object> actor,
            int maxClients,
            int acceptCount) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.actor = Objects.requireNonNull(actor, "actor");
        if (maxClients < 1 || maxClients > MAX_CONNECTIONS
                || acceptCount < 1 || acceptCount > MAX_ACCEPTED) {
            throw new IllegalArgumentException("bounded RESP2 gateway limits invalid");
        }
        this.acceptCount = acceptCount;
        this.replies = new ActorReplyRouter<>(maxClients, 1, maxClients);
        this.outputPump = new ActorTaggedReplyDispatcher(actor, replies);
    }

    public OresFuture<Void> serveAsync() {
        if (!started.compareAndSet(false, true)) {
            return OresFuture.failed(
                    new IllegalStateException("gateway already started"));
        }
        return OresFuture.fromCallback(callback -> {
            completion.set(callback);
            try {
                outputPump.start();
                pumpAccept();
            } catch (RuntimeException failure) {
                abort(failure);
            }
        }, this::close);
    }

    public int accepted() { return accepted.get(); }
    public int activeClients() { return sessions.size(); }
    public int inFlight() { return replies.inFlight(); }
    public Throwable failure() { return failure; }

    private void pumpAccept() {
        if (acceptWip.getAndIncrement() != 0) return;
        int missed = 1;
        do {
            if (!closed.get() && accepted.get() < acceptCount
                    && acceptBusy.compareAndSet(false, true)) {
                OresFuture<AsyncNioTcp.Connection> read;
                try {
                    read = listener.acceptAsync();
                } catch (Throwable failure) {
                    acceptBusy.set(false);
                    abort(failure);
                    read = null;
                }
                if (read != null) {
                    final var current = read;
                    acceptedFuture.set(current);
                    current.whenCompleteRuntime((connection, error) -> {
                        acceptedFuture.compareAndSet(current, null);
                        acceptBusy.set(false);
                        if (closed.get()) {
                            if (connection != null) connection.closeAsync();
                        } else if (error != null) {
                            abort(OresFuture.unwrap(error));
                        } else if (connection == null) {
                            abort(new IOException("NIO2 accept returned null socket"));
                        } else {
                            onAccepted(connection);
                        }
                        pumpAccept();
                    });
                }
            }
            missed = acceptWip.addAndGet(-missed);
        } while (missed != 0);
    }

    private void onAccepted(AsyncNioTcp.Connection socket) {
        int count = accepted.incrementAndGet();
        var client = replies.tryOpenClient();
        if (client.isEmpty()) {
            // A full bounded actor ingress rejects new connections, never
            // buffering an unbounded extra socket or borrowing CONTROL headroom.
            socket.closeAsync();
        } else {
            Session session = new Session(socket, client.orElseThrow());
            sessions.add(session);
            session.readNext();
        }
        if (count >= acceptCount) {
            listener.closeAsync();
            maybeFinish();
        }
    }

    private void maybeFinish() {
        if (!closed.get() && accepted.get() >= acceptCount && sessions.isEmpty()) {
            finish(null);
        }
    }

    private void abort(Throwable reason) {
        finish(Objects.requireNonNull(reason, "reason"));
    }

    private void finish(Throwable reason) {
        if (!closed.compareAndSet(false, true)) return;
        failure = reason;
        OresFuture<?> accepting = acceptedFuture.getAndSet(null);
        if (accepting != null) accepting.cancel(false);
        listener.closeAsync();
        outputPump.close();
        for (Session session : List.copyOf(sessions)) session.close();
        OresFuture.Callback<Void> callback = completion.getAndSet(null);
        if (callback != null) {
            if (reason == null) callback.resolve(null);
            else callback.reject(reason);
        }
    }

    @Override public void close() {
        finish(new IOException("bounded RESP2 actor gateway closed"));
    }

    private final class Session implements AutoCloseable {
        private final AsyncNioTcp.Connection socket;
        private final ActorReplyRouter<ActorTaggedReplyDispatcher.Reply>.Client client;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<OresFuture<?>> operation = new AtomicReference<>();
        private final AtomicReference<OresFuture<?>> admission = new AtomicReference<>();
        private final AtomicReference<ActorReplyRouter.Ticket<
                ActorTaggedReplyDispatcher.Reply>> pending = new AtomicReference<>();

        private Session(
                AsyncNioTcp.Connection socket,
                ActorReplyRouter<ActorTaggedReplyDispatcher.Reply>.Client client) {
            this.socket = socket;
            this.client = client;
        }

        private void readNext() {
            if (closed.get()) return;
            final OresFuture<List<Object>> read;
            try {
                read = socket.readResp2BinaryAsync();
            } catch (Throwable failure) {
                close();
                return;
            }
            operation.set(read);
            read.whenCompleteRuntime((frame, error) -> {
                operation.compareAndSet(read, null);
                if (closed.get()) return;
                if (error != null || frame == null || frame.size() != 4) {
                    close();
                    return;
                }
                try {
                    handleFrame(frame);
                } catch (Throwable failure) {
                    close();
                }
            });
        }

        private void handleFrame(List<Object> frame) {
            if (!(frame.get(0) instanceof Number number)
                    || !(frame.get(2) instanceof OresBytes key)
                    || !(frame.get(3) instanceof OresBytes value)) {
                close();
                return;
            }
            int kind = number.intValue();
            if (kind == 0) { close(); return; }
            if (kind == 5) { write("OK", OresBytes.empty(), true); return; }
            if (kind == 1) { write("PONG", OresBytes.empty(), false); return; }
            if (kind != 2 && kind != 3 && kind != 4) {
                write("ERR", OresBytes.empty(), false);
                return;
            }

            var admitted = replies.tryAdmit(client);
            if (admitted.isEmpty()) {
                // No DATA admission without a reserved bounded ticket.
                close();
                return;
            }
            var ticket = admitted.orElseThrow();
            pending.set(ticket);
            ticket.reply().whenCompleteRuntime((reply, error) -> {
                if (!pending.compareAndSet(ticket, null) || closed.get()) return;
                if (error != null || reply == null) {
                    close();
                    return;
                }
                write(reply.status(), reply.payload(), false);
            });

            try {
                OresFuture<Void> send = actor.sendAsync(
                        List.of(client.id(), ticket.requestId(), kind, key, value));
                admission.set(send);
                send.whenCompleteRuntime((ignored, failure) -> {
                    admission.compareAndSet(send, null);
                    if (failure != null) {
                        ticket.reply().cancel(false);
                        close();
                    }
                });
            } catch (Throwable failure) {
                ticket.reply().cancel(false);
                close();
            }
        }

        private void write(String status, OresBytes value, boolean terminal) {
            if (closed.get()) return;
            final OresFuture<Void> write;
            try {
                write = socket.writeResp2BinaryAsync(status, value);
            } catch (Throwable failure) {
                close();
                return;
            }
            operation.set(write);
            write.whenCompleteRuntime((ignored, failure) -> {
                operation.compareAndSet(write, null);
                if (closed.get()) return;
                if (failure != null || terminal) close();
                else readNext();
            });
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            ActorReplyRouter.Ticket<ActorTaggedReplyDispatcher.Reply> ticket =
                    pending.getAndSet(null);
            if (ticket != null) ticket.reply().cancel(false);
            OresFuture<?> waiting = operation.getAndSet(null);
            if (waiting != null) waiting.cancel(false);
            OresFuture<?> sending = admission.getAndSet(null);
            if (sending != null) sending.cancel(false);
            client.close();
            socket.closeAsync();
            sessions.remove(this);
            maybeFinish();
        }
    }
}
