package dev.oreslang.runtime;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded loopback RESP2 NIO2 sessions backed ONLY by one Atto-owned KV actor.
 *
 * The NIO layer decodes/encodes RESP2 and tracks session/request routing; it
 * owns no Map, no keys or values beyond immutable command/frame snapshots.
 * All actual KV mutations execute on the target actor's serialized mailbox.
 *
 * This narrow API accepts a finite session count so executable Atto tests can
 * stop deterministically. QUIT/EOF retires only its client, never the storage
 * actor. No blocking Socket/streams, Future.get/join or carrier parking.
 */
final class AsyncNioActorRedisServer implements AutoCloseable {
    private static final int MAX_TOTAL_SESSIONS = 1024;
    private static final int MAX_QUEUED_CLIENT_EVENTS = 8;

    private final OresContext context;
    private final AsyncNioTcp.Listener listener;
    private final ActorRuntime.ActorRef<Object> actor;
    private final ActorOutputReplyDispatcher replies;
    private final int sessionTarget;
    private final OresFuture.Callback<Void> completion;
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final AtomicInteger accepted = new AtomicInteger();
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final AtomicInteger closedSessions = new AtomicInteger();

    @SuppressWarnings("unchecked")
    private AsyncNioActorRedisServer(
            OresContext context, AsyncNioTcp.Listener listener,
            ActorRuntime.ActorRef<?> actor, int sessionTarget,
            OresFuture.Callback<Void> completion) {
        this.context = Objects.requireNonNull(context);
        this.listener = Objects.requireNonNull(listener);
        this.actor = (ActorRuntime.ActorRef<Object>) Objects.requireNonNull(actor);
        this.sessionTarget = sessionTarget;
        this.completion = Objects.requireNonNull(completion);
        this.replies = new ActorOutputReplyDispatcher(actor);
    }

    static OresFuture<Void> serve(
            OresContext context, AsyncNioTcp.Listener listener,
            ActorRuntime.ActorRef<?> actor, int sessions) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(listener);
        Objects.requireNonNull(actor);
        if (sessions < 1 || sessions > MAX_TOTAL_SESSIONS) {
            return OresFuture.failed(new IllegalArgumentException(
                    "bounded Redis server requires 1..1024 total client sessions"));
        }
        AtomicReference<AsyncNioActorRedisServer> ref = new AtomicReference<>();
        return OresFuture.fromCallback(callback -> {
            final AsyncNioActorRedisServer server;
            try {
                server = new AsyncNioActorRedisServer(
                        context, listener, actor, sessions, callback);
                ref.set(server);
                context.nativeIo().register(server);
                server.start();
            } catch (RuntimeException failure) {
                AsyncNioActorRedisServer active = ref.get();
                if (active != null) active.fail(failure);
                else callback.reject(failure);
            }
        }, () -> {
            AsyncNioActorRedisServer active = ref.get();
            if (active != null) active.close();
        });
    }

    private void start() {
        replies.start();
        // A stopped actor invalidates all client sessions, not only the one
        // that happened to be awaiting a reply at the moment of failure.
        actor.getDone().whenCompleteRuntime((ignored, failure) ->
                fail(failure == null
                        ? new IllegalStateException("KV actor stopped before server retirement")
                        : OresFuture.unwrap(failure)));
        acceptNext();
    }

    private void acceptNext() {
        if (terminated.get() || accepted.get() >= sessionTarget) {
            maybeFinish();
            return;
        }
        listener.acceptForServer().whenCompleteRuntime((socket, failure) -> {
            if (terminated.get()) {
                if (socket != null) socket.closeAsync();
                return;
            }
            if (failure != null) {
                fail(OresFuture.unwrap(failure));
                return;
            }
            if (socket == null) {
                fail(new IOException("async accept returned no client"));
                return;
            }
            accepted.incrementAndGet();
            var session = replies.openSession();
            if (session.isEmpty()) {
                // Explicit bounded admission: do not retain socket or a
                // pending client Future when the per-server limit is hit.
                socket.closeAsync();
                closedSessions.incrementAndGet();
            } else {
                Client client = new Client(socket, session.orElseThrow());
                clients.add(client);
                client.readNext();
            }
            if (accepted.get() < sessionTarget) acceptNext();
            else maybeFinish();
        });
    }

    private void sessionClosed(Client client) {
        clients.remove(client);
        closedSessions.incrementAndGet();
        maybeFinish();
    }

    private void maybeFinish() {
        if (terminated.get()
                || accepted.get() != sessionTarget
                || closedSessions.get() != sessionTarget
                || !clients.isEmpty())
            return;
        if (!terminated.compareAndSet(false, true)) return;
        context.nativeIo().unregister(this);
        replies.close();
        if (!completion.isDone()) completion.resolve(null);
    }

    private void fail(Throwable failure) {
        Objects.requireNonNull(failure);
        if (!terminated.compareAndSet(false, true)) return;
        listener.closeAsync();
        for (Client client : clients) client.close();
        replies.close();
        context.nativeIo().unregister(this);
        if (!completion.isDone()) completion.reject(failure);
    }

    @Override public void close() {
        fail(new CancellationException("bounded Redis NIO2 server retired"));
    }

    private final class Client implements AutoCloseable {
        private final AsyncNioTcp.Connection socket;
        private final ActorReplyCorrelation.Session session;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final ArrayDeque<Runnable> events = new ArrayDeque<>();
        private boolean processing;
        private volatile OresFuture<Void> admission;
        private volatile ActorReplyCorrelation.Pending pending;

        private Client(
                AsyncNioTcp.Connection socket,
                ActorReplyCorrelation.Session session) {
            this.socket = socket;
            this.session = session;
        }

        /**
         * Trampolines callback completions so 256 already-buffered RESP2
         * frames cannot recursively exhaust a carrier thread's stack.
         * Per-session event retention is bounded independently of sockets.
         */
        private void dispatch(Runnable action) {
            if (closed.get()) return;
            synchronized (events) {
                if (events.size() >= MAX_QUEUED_CLIENT_EVENTS) {
                    close();
                    return;
                }
                events.addLast(action);
                if (processing) return;
                processing = true;
            }
            for (;;) {
                Runnable work;
                synchronized (events) {
                    work = events.pollFirst();
                    if (work == null) {
                        processing = false;
                        return;
                    }
                }
                if (closed.get()) continue;
                try {
                    work.run();
                } catch (RuntimeException | Error failure) {
                    close();
                }
            }
        }

        private void readNext() {
            if (closed.get()) return;
            socket.readResp2BinaryAsync().whenCompleteRuntime(
                    (frame, failure) -> dispatch(() -> {
                        if (failure != null) {
                            close();
                            return;
                        }
                        if (frame == null || frame.size() != 4
                                || !(frame.get(0) instanceof Number number)
                                || !(frame.get(2) instanceof OresBytes key)
                                || !(frame.get(3) instanceof OresBytes value)) {
                            close();
                            return;
                        }
                        int opcode = number.intValue();
                        if (opcode == 0) {
                            close(); // clean EOF
                        } else if (opcode == 5) {
                            sendReply("OK", OresBytes.empty(), true);
                        } else if (opcode == 1) {
                            sendReply("PONG", OresBytes.empty(), false);
                        } else {
                            sendActor(opcode, key, value);
                        }
                    }));
        }

        private void sendActor(int opcode, OresBytes key, OresBytes value) {
            var reserved = replies.tryBegin(session);
            if (reserved.isEmpty()) {
                close(); // bounded load-shedding, never an unbounded waiter
                return;
            }
            ActorReplyCorrelation.Pending request = reserved.orElseThrow();
            pending = request;
            Object mail = List.of(request.requestId(), opcode, key, value);
            try {
                if (actor.trySend(mail)) {
                    waitForActor(request);
                } else {
                    // The existing actor runtime caps pending asynchronous
                    // admissions. Only this source/client continuation waits.
                    OresFuture<Void> send = actor.sendAsync(mail);
                    admission = send;
                    send.whenCompleteRuntime((ignored, failure) ->
                            dispatch(() -> {
                                admission = null;
                                if (failure != null) {
                                    replies.cancelRequest(session, request.requestId());
                                    close();
                                } else {
                                    waitForActor(request);
                                }
                            }));
                }
            } catch (RuntimeException failure) {
                replies.cancelRequest(session, request.requestId());
                close();
            }
        }

        private void waitForActor(ActorReplyCorrelation.Pending request) {
            request.response().whenCompleteRuntime((response, failure) ->
                    dispatch(() -> {
                        pending = null;
                        if (failure != null || response == null
                                || response.sessionId() != session.id()
                                || response.generation() != replies.generation()
                                || response.requestId() != request.requestId()) {
                            close();
                            return;
                        }
                        sendReply(response.status(), response.payload(), false);
                    }));
        }

        private void sendReply(String status, OresBytes payload, boolean quit) {
            socket.writeResp2BinaryAsync(status, payload)
                    .whenCompleteRuntime((ignored, failure) ->
                            dispatch(() -> {
                                if (failure != null || quit) close();
                                else readNext();
                            }));
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (events) { events.clear(); }
            OresFuture<Void> send = admission;
            if (send != null) send.cancel(false);
            ActorReplyCorrelation.Pending request = pending;
            if (request != null) request.response().cancel(false);
            replies.closeSession(session);
            socket.closeAsync();
            sessionClosed(this);
        }
    }
}
