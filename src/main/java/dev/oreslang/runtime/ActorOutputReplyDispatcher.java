package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single authenticated actor-output reader for bounded Redis reply routing.
 *
 * The Atto actor remains the *only* owner of Map<Bytes,Bytes>. This dispatcher
 * holds only request/session routing metadata and completed immutable Bytes,
 * never storage state. It does not perform socket I/O or execute guest code.
 *
 * At most one actor-output read is outstanding. Settled Futures are drained
 * iteratively so an already-buffered response burst cannot recurse onto a
 * completion carrier stack. Client disconnects cancel only their own tickets.
 */
public final class ActorOutputReplyDispatcher implements AutoCloseable {
    private final ActorRuntime.ActorRef<?> actor;
    private final ActorRuntime.ActorOutputStream<Object> outputs;
    private final ActorReplyCorrelation correlation = new ActorReplyCorrelation();
    private final long generation;
    private final AtomicInteger pendingDrain = new AtomicInteger();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile OresFuture<GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>>> pendingRead;
    private volatile boolean waiting;
    private volatile Throwable terminalFailure;

    public ActorOutputReplyDispatcher(ActorRuntime.ActorRef<?> actor) {
        this.actor = Objects.requireNonNull(actor);
        this.outputs = actor.outputs();
        if (!outputs.actorId().equals(actor.id()))
            throw new IllegalArgumentException("reply stream belongs to another actor");
        generation = actor.generation();
        if (generation <= 0) throw new IllegalArgumentException("invalid actor generation");
    }

    public long generation() { return generation; }
    public boolean isClosed() { return closed.get(); }
    public Optional<Throwable> failure() { return Optional.ofNullable(terminalFailure); }
    public int pendingCount() { return correlation.pendingCount(); }
    public int sessionCount() { return correlation.sessionCount(); }

    public Optional<ActorReplyCorrelation.Session> openSession() {
        return correlation.openSession(generation);
    }

    public Optional<ActorReplyCorrelation.Pending> tryBegin(
            ActorReplyCorrelation.Session session) {
        return correlation.tryBegin(session);
    }

    public boolean cancelRequest(
            ActorReplyCorrelation.Session session, int requestId) {
        return correlation.cancelRequest(session, requestId);
    }

    public int closeSession(ActorReplyCorrelation.Session session) {
        return correlation.closeSession(session);
    }

    /** Register the sole output reader; this never waits on an actor carrier. */
    public void start() {
        if (!started.compareAndSet(false, true))
            throw new IllegalStateException("reply dispatcher already started");
        if (closed.get()) throw new IllegalStateException("reply dispatcher closed");
        actor.getDone().whenCompleteRuntime((ignored, failure) -> {
            fail(failure == null
                    ? new IllegalStateException("storage actor terminated")
                    : OresFuture.unwrap(failure));
        });
        drain();
    }

    private void drain() {
        if (pendingDrain.getAndIncrement() != 0) return;
        int missed = 1;
        for (;;) {
            while (!closed.get() && !waiting) {
                final OresFuture<GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>>> next;
                try {
                    next = outputs.next();
                } catch (RuntimeException failure) {
                    fail(failure);
                    break;
                }
                if (next.isDone()) {
                    try {
                        consume(next.join());
                    } catch (RuntimeException failure) {
                        fail(OresFuture.unwrap(failure));
                    }
                    continue;
                }
                waiting = true;
                pendingRead = next;
                next.whenCompleteRuntime((step, failure) -> {
                    pendingRead = null;
                    waiting = false;
                    if (failure != null) fail(OresFuture.unwrap(failure));
                    else consume(step);
                    drain();
                });
            }
            missed = pendingDrain.addAndGet(-missed);
            if (missed == 0) return;
        }
    }

    private void consume(GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>> step) {
        if (closed.get()) return;
        if (step == null || step.done()) {
            fail(new IllegalStateException("actor output stream closed"));
            return;
        }
        ActorRuntime.ActorOutput<Object> output = step.value();
        if (output == null || !output.actorId().equals(actor.id())) {
            fail(new SecurityException("unauthenticated actor reply source"));
            return;
        }
        if (!(output.value() instanceof List<?> tuple) || tuple.size() != 3
                || !(tuple.get(0) instanceof Number number)
                || !(tuple.get(1) instanceof String status)
                || !(tuple.get(2) instanceof OresBytes payload)) {
            fail(new IllegalArgumentException("invalid typed actor reply tuple"));
            return;
        }
        if (number.longValue() != number.intValue() || number.intValue() <= 0) {
            fail(new IllegalArgumentException("invalid actor request ID"));
            return;
        }
        try {
            // An unmatched id is a late/cancelled reply. It may never be routed
            // to another session, and request IDs never wrap or get reused.
            correlation.replyFromActor(number.intValue(), generation, status, payload);
        } catch (RuntimeException failure) {
            fail(failure);
        }
    }

    private void fail(Throwable failure) {
        Objects.requireNonNull(failure);
        if (!closed.compareAndSet(false, true)) return;
        terminalFailure = failure;
        OresFuture<?> read = pendingRead;
        if (read != null) read.cancel(false);
        correlation.close();
    }

    @Override public void close() {
        fail(new IllegalStateException("reply dispatcher retired"));
    }
}
