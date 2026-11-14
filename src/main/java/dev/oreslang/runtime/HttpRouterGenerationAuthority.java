package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Trusted-host admission authority for one HTTP router/supervisor binding.
 *
 * <p>NOT a guest API. Neither Owner, Snapshot nor Ticket may be put in guest
 * fields, actor mail, wire frames, callback arguments, logs or public Decision
 * constructors. The listener must bind its immutable route snapshot and call
 * admit() at the SAME dispatch linearization point. This class does not yet
 * wire itself into NativeHttp or the native Atto router.
 *
 * <p>Identity, not route table fingerprints or caller supplied IDs, grants
 * access. Existing requests may finish under their original snapshot during
 * a same-owner upgrade. Supervisor/tenant replacement revokes old response
 * claims without faking physical quiescence.
 */
final class HttpRouterGenerationAuthority implements AutoCloseable {
    static final class Owner {
        private final String tenant;
        private final long supervisorId;
        private final long actorId;
        private final long incarnation;

        /** Only the trusted supervisor host may construct or retain this. */
        Owner(String tenant, long supervisorId, long actorId, long incarnation) {
            if (tenant == null || !tenant.matches("[A-Za-z0-9_.-]{1,128}")
                    || tenant.equals(".") || tenant.equals(".."))
                throw new IllegalArgumentException("invalid tenant identity");
            if (supervisorId <= 0 || actorId <= 0 || incarnation <= 0)
                throw new IllegalArgumentException("invalid supervisor/actor incarnation");
            this.tenant = tenant;
            this.supervisorId = supervisorId;
            this.actorId = actorId;
            this.incarnation = incarnation;
        }
        String tenant() { return tenant; }
        long supervisorId() { return supervisorId; }
        long actorId() { return actorId; }
        long incarnation() { return incarnation; }
    }

    /** Opaque instance identity; SHA-256 is diagnostics only, not authority. */
    static final class Snapshot {
        private final HttpRouterGenerationAuthority issuer;
        private final long generation;
        private final String routeSha256;
        private Snapshot(HttpRouterGenerationAuthority issuer, long generation, String sha) {
            this.issuer = issuer;
            this.generation = generation;
            this.routeSha256 = sha;
        }
        long generation() { return generation; }
        String routeSha256() { return routeSha256; }
    }

    private static final class Generation {
        final Owner owner;
        final Snapshot snapshot;
        final HttpGenerationDrainV1 drain;
        boolean revoked;

        Generation(Owner owner, Snapshot snapshot, int maxRequests, int maxPins) {
            this.owner = owner;
            this.snapshot = snapshot;
            this.drain = new HttpGenerationDrainV1(snapshot.generation(), maxRequests, maxPins);
        }
    }

    /** Unexportable, runtime-issued, once-only response claim. */
    static final class Ticket {
        private final HttpRouterGenerationAuthority issuer;
        private final Generation generation;
        private final HttpGenerationDrainV1.Exchange exchange;
        private boolean responseClaimed;
        private boolean headersCommitted;
        private boolean cancelled;
        private boolean handlerDone;
        private boolean transportDone;

        private Ticket(HttpRouterGenerationAuthority issuer, Generation generation,
                       HttpGenerationDrainV1.Exchange exchange) {
            this.issuer = issuer;
            this.generation = generation;
            this.exchange = exchange;
        }
        long generation() { return generation.snapshot.generation(); }
    }

    /** Staged publication, opaque to guests and bound to the exact old head. */
    static final class Candidate {
        private final HttpRouterGenerationAuthority issuer;
        private final Generation original;
        private final Generation proposed;
        private Candidate(HttpRouterGenerationAuthority issuer,
                          Generation original, Generation proposed) {
            this.issuer = issuer;
            this.original = original;
            this.proposed = proposed;
        }
    }

    private final int maxRequests;
    private final int maxPins;
    private final int maxRetainedGenerations;
    private final ArrayList<Generation> oldGenerations = new ArrayList<>();
    private Generation active;
    private Candidate candidate;
    private boolean closed;

    HttpRouterGenerationAuthority(Owner trustedOwner, String initialRoutesSha256,
                                   int maxRequests, int maxPins, int maxRetainedGenerations) {
        Objects.requireNonNull(trustedOwner, "trusted owner");
        validateSha(initialRoutesSha256);
        if (maxRequests <= 0 || maxPins <= 0
                || maxRetainedGenerations < 1 || maxRetainedGenerations > 1024)
            throw new IllegalArgumentException("invalid bounded authority capacities");
        this.maxRequests = maxRequests;
        this.maxPins = maxPins;
        this.maxRetainedGenerations = maxRetainedGenerations;
        active = generation(trustedOwner, 1, initialRoutesSha256);
    }

    private static void validateSha(String sha) {
        if (sha == null || !sha.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("expected canonical route SHA-256");
    }

    private Generation generation(Owner owner, long epoch, String digest) {
        Snapshot snapshot = new Snapshot(this, epoch, digest);
        return new Generation(owner, snapshot, maxRequests, maxPins);
    }

    /** Hand out only to the trusted listener, never to a Decision or actor. */
    synchronized Snapshot activeSnapshot(Owner trustedOwner) {
        checkLiveOwner(trustedOwner);
        return active.snapshot;
    }

    /**
     * Route selection and this admission MUST occur under the same trusted
     * listener lock. No public Decision metadata is accepted as evidence.
     */
    synchronized Ticket admit(Owner trustedOwner, Snapshot chosenSnapshot) {
        checkLiveOwner(trustedOwner);
        if (candidate != null || chosenSnapshot != active.snapshot)
            throw new SecurityException("unpublished, foreign or frozen route snapshot");
        HttpGenerationDrainV1.Exchange exchange = active.drain.admit();
        return new Ticket(this, active, exchange);
    }

    /**
     * Claim response transfer once. An already admitted request can complete
     * on its original snapshot after SAME-owner upgrade; a changed supervisor
     * incarnation revokes claims to old snapshots. Physical work stays pinned.
     */
    synchronized boolean claimResponse(Owner owner, Snapshot snapshot, Ticket ticket) {
        checkTicket(owner, snapshot, ticket);
        if (ticket.cancelled || ticket.responseClaimed || ticket.generation.revoked
                || ticket.handlerDone || ticket.transportDone || ticket.exchange.finished())
            return false;
        ticket.responseClaimed = true;
        return true;
    }

    /**
     * Separate irreversible success-header commit from response claiming.
     * An owner replacement and success-header authorization serialize under
     * this monitor; no stale generation can commit new success headers.
     * If a replacement happens AFTER this succeeds, the transport must abort
     * an already-started response rather than attempting another status line.
     */
    synchronized boolean commitHeaders(Owner owner, Snapshot snapshot, Ticket ticket) {
        checkTicket(owner, snapshot, ticket);
        if (!ticket.responseClaimed || ticket.headersCommitted || ticket.cancelled
                || ticket.generation.revoked || ticket.transportDone
                || ticket.exchange.finished()) return false;
        ticket.headersCommitted = true;
        return true;
    }

    synchronized boolean requestCancellation(Owner owner, Snapshot snapshot, Ticket ticket) {
        checkTicket(owner, snapshot, ticket);
        if (ticket.cancelled || ticket.exchange.finished()) return false;
        ticket.cancelled = true;
        ticket.exchange.requestCancellation();
        return true;
    }

    synchronized boolean cancelled(Owner owner, Snapshot snapshot, Ticket ticket) {
        checkTicket(owner, snapshot, ticket);
        return ticket.cancelled || ticket.generation.revoked
                || ticket.exchange.cancellationRequested();
    }

    /** Physical resource pins survive cancellation and generation promotion. */
    synchronized HttpGenerationDrainV1.ResourcePin pin(
            Owner owner, Snapshot snapshot, Ticket ticket,
            HttpGenerationDrainV1.ResourceKind kind) {
        checkTicket(owner, snapshot, ticket);
        if (ticket.cancelled || ticket.generation.revoked || ticket.handlerDone
                || ticket.transportDone)
            throw new IllegalStateException("retired/cancelled request cannot pin new resource");
        return ticket.exchange.pin(kind);
    }

    /** Called only after the guest handler and all its continuations quiesce. */
    synchronized void handlerQuiescent(Owner owner, Snapshot snapshot, Ticket ticket) {
        checkTicket(owner, snapshot, ticket);
        if (!ticket.handlerDone) {
            ticket.handlerDone = true;
            ticket.exchange.handlerQuiescent();
        }
    }

    /** Called only after socket emission/abort and transport cleanup quiesce. */
    synchronized void transportQuiescent(Owner owner, Snapshot snapshot, Ticket ticket) {
        checkTicket(owner, snapshot, ticket);
        if (!ticket.transportDone) {
            ticket.transportDone = true;
            ticket.exchange.transportQuiescent();
        }
    }

    /**
     * Freeze new admission before staging an immutable replacement. A digest
     * merely diagnoses the source image; it does NOT establish capability.
     * New owner identity represents a different supervisor/tenant incarnation.
     */
    synchronized Candidate prepare(Owner presentOwner, Owner replacement,
                                   String nextRoutesSha256) {
        checkLiveOwner(presentOwner);
        Objects.requireNonNull(replacement, "replacement owner");
        validateSha(nextRoutesSha256);
        if (candidate != null) throw new IllegalStateException("publication already prepared");
        prune();
        if (oldGenerations.size() >= maxRetainedGenerations)
            throw new IllegalStateException("old generation retention budget exhausted");
        if (active.snapshot.generation == Long.MAX_VALUE)
            throw new IllegalStateException("HTTP generation epoch exhausted");
        Generation proposed = generation(replacement, active.snapshot.generation + 1, nextRoutesSha256);
        candidate = new Candidate(this, active, proposed);
        return candidate;
    }

    synchronized void publish(Candidate pending) {
        checkCandidate(pending);
        // Retain the exact old snapshot while physically pinned requests drain.
        oldGenerations.add(active); // any allocation failure leaves active unchanged
        active.drain.beginDrain();
        if (active.owner != pending.proposed.owner) active.revoked = true;
        active = pending.proposed;
        candidate = null;
    }

    synchronized void abort(Candidate pending) {
        checkCandidate(pending);
        candidate = null;
    }

    synchronized int retainedGenerations() { prune(); return oldGenerations.size(); }
    synchronized long activeGeneration() { return active.snapshot.generation(); }

    synchronized boolean fullyQuiescent() {
        if (!closed) return false;
        prune();
        return oldGenerations.isEmpty() && active.drain.readyToRetire();
    }

    private void prune() {
        oldGenerations.removeIf(g -> g.drain.readyToRetire());
    }

    private void checkLiveOwner(Owner owner) {
        if (closed || owner == null || owner != active.owner)
            throw new SecurityException("foreign, stale or closed routing owner");
    }

    private void checkTicket(Owner owner, Snapshot snapshot, Ticket ticket) {
        if (ticket == null || ticket.issuer != this || snapshot == null
                || snapshot.issuer != this || ticket.generation.snapshot != snapshot
                || owner == null || owner != ticket.generation.owner)
            throw new SecurityException("foreign, stale or mismatched request capability");
    }

    private void checkCandidate(Candidate pending) {
        if (closed || pending == null || pending.issuer != this
                || candidate != pending || pending.original != active)
            throw new SecurityException("stale or foreign router publication candidate");
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        candidate = null;
        active.revoked = true;
        active.drain.beginDrain();
        for (Generation old : oldGenerations) old.revoked = true;
        // Never mark handler or transport quiescent on behalf of live work.
    }
}
