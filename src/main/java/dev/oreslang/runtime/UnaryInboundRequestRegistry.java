package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * Bounded inbound request ledger for authenticated peers that choose their own
 * correlation IDs. Unlike UnaryRequestRegistry, this class never mints IDs.
 *
 * <p>V1 deliberately requires strictly increasing peer request IDs. That makes
 * replay rejection bounded: retired IDs need not be retained forever because
 * every ID at or below the high-water mark is permanently stale for the
 * session generation.</p>
 */
final class UnaryInboundRequestRegistry implements AutoCloseable {
    private record Pending(int payloadBytes) { }

    private final long sessionId;
    private final long generation;
    private final int requestCapacity;
    private final long byteCapacity;
    private final Map<Long, Pending> pending = new HashMap<>();

    private long highWaterRequestId;
    private long activePayloadBytes;
    private boolean closed;

    UnaryInboundRequestRegistry(long sessionId, long generation,
                                int requestCapacity, long byteCapacity) {
        long maximumUsefulByteCapacity =
                (long) requestCapacity * UnaryTransportFrame.MAX_PAYLOAD_BYTES;
        if (sessionId <= 0 || generation <= 0
                || requestCapacity < 1 || requestCapacity > 65536
                || byteCapacity < 0 || byteCapacity > maximumUsefulByteCapacity) {
            throw new IllegalArgumentException("invalid inbound unary admission limits");
        }
        this.sessionId = sessionId;
        this.generation = generation;
        this.requestCapacity = requestCapacity;
        this.byteCapacity = byteCapacity;
    }

    synchronized boolean admit(long session, long epoch, long requestId,
                               int payloadBytes) {
        verifySession(session, epoch);
        if (closed) throw new IllegalStateException("unary inbound session is closed");
        if (requestId <= 0 || requestId <= highWaterRequestId)
            throw new SecurityException("stale, duplicate or non-monotonic unary request ID");
        if (payloadBytes < 0 || payloadBytes > UnaryTransportFrame.MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("invalid inbound unary payload size");

        // Once an authenticated, syntactically valid request header is observed,
        // its correlation ID is consumed even if bounded admission rejects the
        // body. The transport may already publish a terminal overload ERROR; a
        // lost ERROR must never make replaying the same ID executable later.
        highWaterRequestId = requestId;
        if (pending.size() >= requestCapacity) return false;
        if (payloadBytes > byteCapacity - activePayloadBytes) return false;

        pending.put(requestId, new Pending(payloadBytes));
        activePayloadBytes += payloadBytes;
        return true;
    }

    synchronized boolean complete(long session, long epoch, long requestId) {
        verifySession(session, epoch);
        if (requestId <= 0 || requestId > highWaterRequestId)
            throw new SecurityException("unissued inbound unary request ID");
        return release(requestId);
    }

    synchronized boolean cancel(long session, long epoch, long requestId) {
        return complete(session, epoch, requestId);
    }

    synchronized boolean isPending(long requestId) {
        return pending.containsKey(requestId);
    }

    synchronized int active() { return pending.size(); }
    synchronized int available() { return requestCapacity - pending.size(); }
    synchronized long activePayloadBytes() { return activePayloadBytes; }
    synchronized long availablePayloadBytes() { return byteCapacity - activePayloadBytes; }
    synchronized long highWaterRequestId() { return highWaterRequestId; }
    synchronized boolean isClosed() { return closed; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        pending.clear();
        activePayloadBytes = 0;
    }

    private boolean release(long requestId) {
        Pending removed = pending.remove(requestId);
        if (removed == null) return false;
        activePayloadBytes -= removed.payloadBytes();
        if (activePayloadBytes < 0)
            throw new IllegalStateException("inbound unary byte accounting underflow");
        return true;
    }

    private void verifySession(long session, long epoch) {
        if (session != sessionId || epoch != generation)
            throw new SecurityException("foreign or stale inbound unary session");
    }
}
