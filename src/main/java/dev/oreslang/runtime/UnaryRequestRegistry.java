package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, per-session request/reply admission with monotonic request IDs.
 * No guest callbacks or socket I/O occur while holding this registry monitor.
 * A real transport adapter must still authenticate the peer/session first.
 */
final class UnaryRequestRegistry implements AutoCloseable {
    private final UnaryTransportFrame.Transport transport;
    private final long sessionId;
    private final long generation;
    private final int capacity;
    private final Map<Long, UnaryReplySlot> pending = new HashMap<>();
    private long nextRequestId = 1;
    private boolean closed;

    UnaryRequestRegistry(UnaryTransportFrame.Transport transport, long sessionId,
                         long generation, int capacity) {
        this.transport = Objects.requireNonNull(transport, "transport");
        if (sessionId <= 0 || generation <= 0 || capacity < 1 || capacity > 65536)
            throw new IllegalArgumentException("invalid unary session or admission capacity");
        this.sessionId = sessionId;
        this.generation = generation;
        this.capacity = capacity;
    }

    synchronized long admit() {
        if (closed) throw new IllegalStateException("unary session already closed");
        if (pending.size() >= capacity)
            throw new IllegalStateException("unary session request admission exhausted");
        if (nextRequestId <= 0 || nextRequestId == Long.MAX_VALUE)
            throw new IllegalStateException("unary request IDs exhausted; establish new session");
        long id = nextRequestId++;
        pending.put(id, new UnaryReplySlot(transport, sessionId, generation, id));
        return id;
    }

    synchronized boolean complete(UnaryTransportFrame.Transport from,
                                  UnaryTransportFrame.Envelope reply) {
        Objects.requireNonNull(reply, "reply");
        verify(from, reply.sessionId(), reply.generation(), reply.correlationId());
        if (reply.kind() != UnaryTransportFrame.Kind.REPLY
                && reply.kind() != UnaryTransportFrame.Kind.ERROR)
            throw new IllegalArgumentException("expected unary reply/error, not request/cancel");
        UnaryReplySlot slot = pending.get(reply.correlationId());
        if (slot == null) return false; // Already completed, cancelled or closed.
        if (!slot.accept(from, reply)) return false;
        pending.remove(reply.correlationId(), slot);
        return true;
    }

    synchronized boolean cancel(long requestId) {
        if (requestId <= 0 || requestId >= nextRequestId)
            throw new SecurityException("unissued unary request ID");
        UnaryReplySlot slot = pending.get(requestId);
        if (slot == null) return false;
        if (!slot.cancel(transport, sessionId, generation, requestId)) return false;
        pending.remove(requestId, slot);
        return true;
    }

    synchronized int active() { return pending.size(); }
    synchronized int available() { return capacity - pending.size(); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (var entry : pending.entrySet())
            entry.getValue().cancel(transport, sessionId, generation, entry.getKey());
        pending.clear();
    }

    private void verify(UnaryTransportFrame.Transport from, long session, long epoch, long requestId) {
        if (from != transport || session != sessionId || epoch != generation
                || requestId <= 0 || requestId >= nextRequestId)
            throw new SecurityException("foreign or unissued unary request correlation");
    }
}
