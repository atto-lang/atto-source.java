package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Experimental loopback TEXT adapter. All application decisions run in the
 * linked source actor. No sockets, addresses or lease handles reach guest code.
 * One active session per server; the existing transport bounds frames, queues,
 * messages, control traffic and native I/O. Not a general WebSocket API. */
public final class NativeWebSocket {
    private NativeWebSocket() { }

    public static Server listen(OresContext context, ActorRuntime.ActorCodeExecutor code,
            String type, int port, String origin) {
        if (ActorRuntime.inActorExecution()) throw new SecurityException("WebSocket listen requires supervisor");
        try {
            return new Server(context, code, type, WebSocketLoopbackListener.listen(
                    context, port, 8, 1, 4096, 4096, Set.of(origin),
                    new WebSocketRawUpgrade.Limits(4096, 32, 1000)));
        } catch (Exception failure) {
            throw new IllegalStateException("WebSocket listen failed", failure);
        }
    }

    public static final class Server implements AutoCloseable {
        private final OresContext context;
        private final ActorRuntime.ActorCodeExecutor code;
        private final String type;
        private final WebSocketLoopbackListener listener;
        private final AtomicBoolean serving = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Thread pump;
        private final AtomicBoolean started = new AtomicBoolean();
        private final OresFuture<Void> retired = new OresFuture<>();
        private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        private volatile WebSocketLoopbackListener.Connection connection;
        private volatile ActorRuntime.ActorRef<Object> actor;

        private Server(OresContext context, ActorRuntime.ActorCodeExecutor code,
                String type, WebSocketLoopbackListener listener) throws Exception {
            this.context = context; this.code = code; this.type = type; this.listener = listener;
            context.nativeIo().register(this);
        }

        /** Future settles only after the session and both host pumps retire. */
        public OresFuture<Void> serveOne() {
            if (ActorRuntime.inActorExecution()) throw new SecurityException("WebSocket serve requires supervisor");
            if (closed.get() || !started.compareAndSet(false, true))
                throw new java.util.concurrent.RejectedExecutionException("WebSocket server closed or busy");
            serving.set(true);
            OresFuture<Void> result = new OresFuture<>(this::close);
            pump = Thread.ofVirtual().name("atto-ws-session").start(() -> {
                Thread outputs = null;
                Throwable failure = null;
                var outputFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                var stopping = new AtomicBoolean();
                try {
                    connection = listener.acceptAsync().get(30, TimeUnit.SECONDS);
                    actor = context.actors().spawnCodeActor(ActorRuntime.ActorKind.SHARED, code, type);
                    actor.ready().get(3, TimeUnit.SECONDS);
                    var owner = connection.bindActor(context, actor);
                    outputs = Thread.ofVirtual().name("atto-ws-output").start(() -> {
                        try {
                            while (!closed.get()) {
                                var step = actor.outputs().next().get(3, TimeUnit.SECONDS);
                                if (step.done()) return;
                                if (!(step.value().value() instanceof String text))
                                    throw new IllegalArgumentException("WebSocket TEXT actor must emit String");
                                if (inFlight.get() <= 0) throw new IllegalStateException("TEXT adapter requires one output per input");
                                connection.sendTextForActorAsync(owner, context, actor, text)
                                        .get(2, TimeUnit.SECONDS);
                                inFlight.decrementAndGet();
                            }
                        } catch (Throwable error) {
                            // Output timeout, malformed output or slow reader retires the session.
                            if (!stopping.get() && !(error instanceof InterruptedException)) outputFailure.set(error);
                            connection.close();
                        }
                    });
                    while (!closed.get()) {
                        var action = connection.readForActorAsync(owner, context, actor)
                                .get(2, TimeUnit.SECONDS);
                        if (action.kind() == WebSocketSessionState.ActionKind.SEND_CLOSE) {
                            // The transport's single writer owns the close reply. Wait for
                            // its receipt via the bounded peer-close handshake before teardown.
                            connection.awaitWriterIdle(1000);
                            break;
                        }
                        if (action.kind() == WebSocketSessionState.ActionKind.USER_ENQUEUED) {
                            if (inFlight.incrementAndGet() > 16)
                                throw new java.util.concurrent.RejectedExecutionException("WebSocket application window full");
                            if (!connection.dispatchNextActorMessage(owner, context, actor, message -> {
                                if (message.opcode() != WebSocketFrameCodec.OPCODE_TEXT)
                                    throw new IllegalArgumentException("TEXT-only experimental adapter");
                                actor.send(new String(message.payload(), StandardCharsets.UTF_8));
                            })) throw new IllegalStateException("WebSocket actor mailbox refused delivery");
                        }
                    }
                } catch (Throwable error) { failure = error; }
                finally {
                    // Cancellation wakes host pumps; clear that interrupt so cleanup
                    // can join physical work before publishing the retirement receipt.
                    stopping.set(true);
                    Thread.interrupted();
                    Throwable cleanupFailure = null;
                    try {
                        listener.close();
                        if (connection != null) connection.close();
                        if (actor != null) {
                            actor.cancel();
                            try { actor.done().get(3, TimeUnit.SECONDS); }
                            catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException expectedTermination) { }
                        }
                        if (outputs != null) {
                            outputs.interrupt();
                            outputs.join(3000);
                            if (outputs.isAlive()) throw new IllegalStateException("WebSocket output pump did not retire");
                        }
                    } catch (Throwable error) { cleanupFailure = error; }
                    inFlight.set(0);
                    serving.set(false);
                    closed.set(true);
                    if (cleanupFailure == null) {
                        context.nativeIo().unregister(this);
                        retired.completeFromRuntime(null);
                    } else {
                        // Keep the resource in the context's retirement ledger.
                        retired.failFromRuntime(cleanupFailure);
                        if (failure == null) failure = cleanupFailure;
                    }
                    if (failure == null) failure = outputFailure.get();
                    if (failure == null) result.completeFromRuntime(null);
                    else result.failFromRuntime(failure);
                }
            });
            return result;
        }

        public OresFuture<Void> retired() { return retired; }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            listener.close();
            if (connection != null) connection.close();
            if (actor != null) actor.cancel();
            Thread worker = pump;
            if (worker != null && worker != Thread.currentThread()) worker.interrupt();
            if (!serving.get()) {
                context.nativeIo().unregister(this);
                retired.completeFromRuntime(null);
            }
        }
    }
}
