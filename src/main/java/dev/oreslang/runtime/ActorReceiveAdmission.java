package dev.oreslang.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Receive-only logical admission primitive for a single actor incarnation.
 *
 * <p>This gate is deliberately independent of the actor's other receiver
 * methods and of actor-state loans: a serial actor has exactly one live DATA
 * receive invocation, not a single-threaded object. A suspended receive keeps
 * its permit until terminal completion and physical cleanup. No OS thread is
 * blocked waiting for a permit; a full gate returns null to its bounded caller.
 *
 * <p>Integration note: this is the runtime primitive and testable contract,
 * not yet the ActorCell scheduler. Existing ActorRuntime still serializes
 * all guest turns; wiring true concurrent dispatch also requires distinct
 * stacks/continuations and correct critical-read/write physical arbitration.
 */
public final class ActorReceiveAdmission implements AutoCloseable {
    private final long incarnation;
    private final int maximum;
    private final boolean serialReceive;
    private final Set<Permit> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private long nextTicket;
    private int peak;
    private boolean closed;

    private ActorReceiveAdmission(long incarnation, int maximum, boolean serialReceive) {
        if (incarnation <= 0 || maximum <= 0)
            throw new IllegalArgumentException("receive incarnation and limits must be positive");
        if (serialReceive && maximum != 1)
            throw new IllegalArgumentException("serial actor has exactly one live receive");
        this.incarnation = incarnation;
        this.maximum = maximum;
        this.serialReceive = serialReceive;
    }

    public static ActorReceiveAdmission serial(long incarnation) {
        return new ActorReceiveAdmission(incarnation, 1, true);
    }

    public static ActorReceiveAdmission concurrent(long incarnation, int maxLiveReceives) {
        return new ActorReceiveAdmission(incarnation, maxLiveReceives, false);
    }

    public synchronized boolean isSerialReceive() { return serialReceive; }
    public synchronized int activeReceives() { return active.size(); }
    public synchronized int peakReceives() { return peak; }
    public synchronized boolean isClosed() { return closed; }

    /**
     * Mints an opaque one-shot logical receive identity. Only a physically
     * admitted receive obtains a permit; full capacity does not spend tickets.
     * Validation uses the incarnation, not carrier thread identity.
     */
    public synchronized Permit tryEnter(long actorIncarnation) {
        if (actorIncarnation != incarnation)
            throw new SecurityException("stale or foreign actor incarnation");
        if (closed) throw new IllegalStateException("actor receive admission closed");
        if (active.size() >= maximum) return null;
        if (nextTicket == Long.MAX_VALUE)
            throw new IllegalStateException("receive admission ticket sequence exhausted");
        Permit permit = new Permit(++nextTicket);
        active.add(permit);
        peak = Math.max(peak, active.size());
        return permit;
    }

    /**
     * A permit may not be returned on await, logical cancellation request or
     * a transient carrier unwind. Call only after terminal physical retirement.
     */
    public final class Permit implements AutoCloseable {
        private final long ticket;
        private boolean retired;

        private Permit(long ticket) { this.ticket = ticket; }

        public long ticket() { return ticket; }

        public boolean isRetired() {
            synchronized (ActorReceiveAdmission.this) { return retired; }
        }

        @Override public void close() {
            synchronized (ActorReceiveAdmission.this) {
                if (retired) return;
                retired = true;
                active.remove(this);
            }
        }
    }

    /**
     * Closing a retired actor fences every old permit. No permit can ever be
     * applied to a different gate/incarnation, even if ticket numbers repeat.
     */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Permit permit : active) permit.retired = true;
        active.clear();
    }
}
