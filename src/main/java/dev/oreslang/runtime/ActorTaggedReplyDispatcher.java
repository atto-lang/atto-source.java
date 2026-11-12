package dev.oreslang.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single consumer of one actor's authenticated output stream. This runtime
 * adapter never reads or writes actor state and never handles network I/O.
 *
 * <p>Source actors emit the exact tuple
 * {@code (client_id:int, request_id:int, status:string, payload:Bytes)}.
 * Only the actor output envelope, not a client supplied DATA message, may
 * enter this dispatcher. An external connection cannot publish to the actor
 * output channel. Pending requests are resolved through ActorReplyRouter,
 * which owns the bounded client/request admission ledger.</p>
 *
 * <p>One in-flight stream read and a trampoline prevent recursive completion
 * stack growth when many outputs are already buffered. The output callback is
 * runtime bookkeeping only: no guest code runs on a NIO completion thread.
 * Each TCP connection must still serialize its own RESP2 writes; for the
 * initial Redis integration use maxPerClient=1 to enforce that ordering.</p>
 */
public final class ActorTaggedReplyDispatcher implements AutoCloseable {
    public record Reply(String status, OresBytes payload) {
        public Reply {
            if (!List.of("OK", "NIL", "VALUE", "ONE", "ZERO", "ERR", "PONG")
                    .contains(Objects.requireNonNull(status, "status"))) {
                throw new IllegalArgumentException("unsupported binary RESP2 reply status");
            }
            Objects.requireNonNull(payload, "payload");
        }
    }

    @FunctionalInterface
    interface OutputSource {
        OresFuture<GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>>> next();
    }

    private final ActorRuntime.ActorId sourceActor;
    private final ActorReplyRouter<Reply> router;
    private final OutputSource outputs;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean waiting = new AtomicBoolean();
    private final AtomicInteger drainWip = new AtomicInteger();
    private final AtomicReference<OresFuture<?>> inFlightRead = new AtomicReference<>();
    private volatile long lastSequence = -1L;
    private volatile Throwable failure;

    /** Production constructor: the output stream is acquired from the actor
     * capability, never supplied by a network client or inferred from IDs. */
    public ActorTaggedReplyDispatcher(
            ActorRuntime.ActorRef<?> actor,
            ActorReplyRouter<Reply> router) {
        this(Objects.requireNonNull(actor, "actor").id(),
                actor.outputs()::next, router);
    }

    // A controllable runtime-output source is only available in package tests.
    ActorTaggedReplyDispatcher(
            ActorRuntime.ActorId actorId,
            OutputSource outputs,
            ActorReplyRouter<Reply> router) {
        this.sourceActor = Objects.requireNonNull(actorId, "actorId");
        this.outputs = Objects.requireNonNull(outputs, "outputs");
        this.router = Objects.requireNonNull(router, "router");
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("actor output dispatcher already started");
        }
        drain();
    }

    public boolean isClosed() { return closed.get(); }

    public Throwable failure() { return failure; }

    private static long requiredId(Object value, String label) throws IOException {
        if (!(value instanceof Long || value instanceof Integer)) {
            throw new IOException(label + " must be a signed integral source ID");
        }
        long n = ((Number) value).longValue();
        if (n <= 0) throw new IOException(label + " must be positive");
        return n;
    }

    private void process(ActorRuntime.ActorOutput<Object> output) throws IOException {
        if (output == null || !sourceActor.equals(output.actorId())) {
            throw new IOException("reply came from an unrelated actor output stream");
        }
        if (output.sequence() <= lastSequence) {
            throw new IOException("replayed or out-of-order actor output sequence");
        }
        lastSequence = output.sequence();
        if (!(output.value() instanceof List<?> fields) || fields.size() != 4) {
            throw new IOException("actor reply must have exactly four tuple fields");
        }
        long clientId = requiredId(fields.get(0), "client id");
        long requestId = requiredId(fields.get(1), "request id");
        if (!(fields.get(2) instanceof String status)
                || !(fields.get(3) instanceof OresBytes payload)) {
            throw new IOException("actor reply status/payload types are invalid");
        }
        Reply reply;
        try {
            reply = new Reply(status, payload);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid actor reply status", invalid);
        }
        // A cancelled request has no pending ticket: silently discard that
        // late authentic actor output rather than delivering to another client.
        router.deliverTagged(clientId, requestId, reply);
    }

    private void drain() {
        if (drainWip.getAndIncrement() != 0) return;
        int missed = 1;
        do {
            if (!closed.get() && waiting.compareAndSet(false, true)) {
                OresFuture<GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>>> next;
                try {
                    next = Objects.requireNonNull(outputs.next(), "actor output read Future");
                } catch (Throwable error) {
                    waiting.set(false);
                    terminate(error);
                    next = null;
                }
                if (next != null) {
                    inFlightRead.set(next);
                    next.whenCompleteRuntime((step, error) -> {
                        inFlightRead.compareAndSet(next, null);
                        waiting.set(false);
                        if (!closed.get()) {
                            if (error != null) {
                                terminate(OresFuture.unwrap(error));
                            } else if (step == null || step.done()) {
                                terminate(new IOException("actor output stream terminated"));
                            } else {
                                try {
                                    process(step.value());
                                } catch (Throwable invalid) {
                                    terminate(invalid);
                                }
                            }
                        }
                        drain();
                    });
                }
            }
            missed = drainWip.addAndGet(-missed);
        } while (missed != 0);
    }

    private void terminate(Throwable reason) {
        if (!closed.compareAndSet(false, true)) return;
        failure = Objects.requireNonNull(reason, "reason");
        router.close();
        OresFuture<?> pending = inFlightRead.getAndSet(null);
        if (pending != null) pending.cancel(false);
    }

    @Override public void close() {
        terminate(new IOException("actor reply dispatcher closed"));
    }
}
