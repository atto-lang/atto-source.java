package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transport and connection-generation-fenced single terminal unary reply.
 * One slot belongs to one supervisor-minted request, never an actor field or
 * sendable guest value. Cancellation and reply compete for a single outcome.
 */
final class UnaryReplySlot {
    private final UnaryTransportFrame.Transport transport;
    private final long sessionId;
    private final long generation;
    private final long correlationId;
    private final AtomicBoolean terminal = new AtomicBoolean();

    UnaryReplySlot(UnaryTransportFrame.Transport transport, long sessionId, long generation, long correlationId) {
        this.transport = Objects.requireNonNull(transport, "transport");
        if (sessionId <= 0 || generation <= 0 || correlationId <= 0)
            throw new IllegalArgumentException("session, generation and correlation ID must be positive");
        this.sessionId = sessionId;
        this.generation = generation;
        this.correlationId = correlationId;
    }

    boolean accept(UnaryTransportFrame.Transport from, UnaryTransportFrame.Envelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        authenticate(from, envelope.sessionId(), envelope.generation(), envelope.correlationId());
        if (envelope.kind() != UnaryTransportFrame.Kind.REPLY
                && envelope.kind() != UnaryTransportFrame.Kind.ERROR)
            throw new IllegalArgumentException("expected a reply or error envelope");
        return terminal.compareAndSet(false, true);
    }

    boolean cancel(UnaryTransportFrame.Transport from, long currentSessionId, long connectionGeneration, long requestId) {
        authenticate(from, currentSessionId, connectionGeneration, requestId);
        return terminal.compareAndSet(false, true);
    }

    boolean isTerminal() { return terminal.get(); }

    private void authenticate(UnaryTransportFrame.Transport from, long currentSessionId, long currentGeneration, long requestId) {
        if (from != transport || currentSessionId != sessionId
                || currentGeneration != generation || requestId != correlationId)
            throw new SecurityException("wrong transport, session, generation or unary reply correlation");
    }
}
