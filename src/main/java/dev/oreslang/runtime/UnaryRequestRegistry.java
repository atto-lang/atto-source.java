package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, per-session request/reply admission with monotonic request IDs.
 * Both request count and outstanding request payload bytes are bounded.
 * No guest callbacks or socket I/O occur while holding this registry monitor.
 * A real transport adapter must still authenticate the peer/session first.
 */
final class UnaryRequestRegistry implements AutoCloseable {
    private record Pending(UnaryReplySlot slot, int requestPayloadBytes) { }

    private final UnaryTransportFrame.Transport transport;
    private final long sessionId;
    private final long generation;
    private final int capacity;
    private final long byteCapacity;
    private final Map<Long, Pending> pending = new HashMap<>();
    private long nextRequestId = 1;
    private long activePayloadBytes;
    private boolean closed;

    UnaryRequestRegistry(UnaryTransportFrame.Transport transport, long sessionId,
                         long generation, int capacity, long byteCapacity) {
        this.transport = Objects.requireNonNull(transport, "transport");
        long maximumUsefulByteCapacity = (long) capacity * UnaryTransportFrame.MAX_PAYLOAD_BYTES;
        if (sessionId <= 0 || generation <= 0 || capacity < 1 || capacity > 65536
                || byteCapacity < 0 || byteCapacity > maximumUsefulByteCapacity)
            throw new IllegalArgumentException("invalid unary session or admission capacity");
        this.sessionId = sessionId;
        this.generation = generation;
        this.capacity = capacity;
        this.byteCapacity = byteCapacity;
    }

    synchronized long admit(int requestPayloadBytes) {
        if (requestPayloadBytes < 0 || requestPayloadBytes > UnaryTransportFrame.MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("invalid unary request payload size");
        if (closed) throw new IllegalStateException("unary session already closed");
        if (pending.size() >= capacity)
            throw new IllegalStateException("unary session request admission exhausted");
        if (requestPayloadBytes > byteCapacity - activePayloadBytes)
            throw new IllegalStateException("unary session request byte admission exhausted");
        if (nextRequestId <= 0 || nextRequestId == Long.MAX_VALUE)
            throw new IllegalStateException("unary request IDs exhausted; establish new session");

        long id = nextRequestId++;
        Pending admitted = new Pending(
                new UnaryReplySlot(transport, sessionId, generation, id),
                requestPayloadBytes);
        pending.put(id, admitted);
        activePayloadBytes += requestPayloadBytes;
        return id;
    }

    synchronized boolean complete(UnaryTransportFrame.Transport from,
                                  UnaryTransportFrame.Envelope reply) {
        Objects.requireNonNull(reply, "reply");
        verify(from, reply.sessionId(), reply.generation(), reply.correlationId());
        if (reply.kind() != UnaryTransportFrame.Kind.REPLY
                && reply.kind() != UnaryTransportFrame.Kind.ERROR)
            throw new IllegalArgumentException("expected unary reply/error, not request/cancel");

        Pending admitted = pending.get(reply.correlationId());
        if (admitted == null) return false; // Already completed, cancelled or closed.
        if (!admitted.slot().accept(from, reply)) return false;
        release(reply.correlationId(), admitted);
        return true;
    }

    synchronized boolean cancel(long requestId) {
        if (requestId <= 0 || requestId >= nextRequestId)
            throw new SecurityException("unissued unary request ID");
        Pending admitted = pending.get(requestId);
        if (admitted == null) return false;
        if (!admitted.slot().cancel(transport, sessionId, generation, requestId)) return false;
        release(requestId, admitted);
        return true;
    }

    synchronized int active() { return pending.size(); }
    synchronized int available() { return capacity - pending.size(); }
    synchronized long activePayloadBytes() { return activePayloadBytes; }
    synchronized long availablePayloadBytes() { return byteCapacity - activePayloadBytes; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (var entry : pending.entrySet())
            entry.getValue().slot().cancel(transport, sessionId, generation, entry.getKey());
        pending.clear();
        activePayloadBytes = 0;
    }

    private void release(long requestId, Pending admitted) {
        if (!pending.remove(requestId, admitted))
            throw new IllegalStateException("unary admission bookkeeping lost");
        activePayloadBytes -= admitted.requestPayloadBytes();
        if (activePayloadBytes < 0)
            throw new IllegalStateException("unary request byte accounting underflow");
    }

    private void verify(UnaryTransportFrame.Transport from, long session, long epoch, long requestId) {
        if (from != transport || session != sessionId || epoch != generation
                || requestId <= 0 || requestId >= nextRequestId)
            throw new SecurityException("foreign or unissued unary request correlation");
    }
}
