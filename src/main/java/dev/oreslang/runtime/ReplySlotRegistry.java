package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Runtime-private, bounded correlation table for replies that cross a
 * process/isolate/transport boundary. Local actor calls must continue using
 * the direct OresFuture in their sealed mailbox envelope.
 *
 * <p>A 64-bit handle alone is NEVER reply authority. An authenticated transport
 * must supply the verified peer identity/session and the origin route before
 * entering complete/fail. The expected schema is an immutable protocol ABI
 * digest, not a guest-supplied dynamic type name. This class is intentionally
 * package-private and never handed to Oreslang guest code.</p>
 *
 * <p>All state changes and OresFuture settlement linearize on one per-origin
 * lock. Future runtime waiters may execute on the settlement thread, but those
 * waiters may only enqueue scheduler work, not execute guest continuations.
 * The origin scheduler owns actual continuation execution.</p>
 */
final class ReplySlotRegistry implements AutoCloseable {
    private static final long MASK32 = 0xffff_ffffL;
    private static final int MAX_CAPACITY = 1_000_000;

    record Endpoint(String id, long incarnation, long routeEpoch) {
        Endpoint {
            if (id == null || id.isBlank() || id.length() > 256) {
                throw new IllegalArgumentException("reply endpoint id must not be blank");
            }
            if (incarnation < 0 || routeEpoch < 0) {
                throw new IllegalArgumentException("negative reply endpoint incarnation/epoch");
            }
        }
    }

    /**
     * The peer/session here MUST come from authenticated runtime transport,
     * never from fields copied out of an incoming user-controlled packet.
     */
    record AuthenticatedPeer(Endpoint endpoint, long sessionId) {
        AuthenticatedPeer {
            Objects.requireNonNull(endpoint, "endpoint");
            if (sessionId == 0) {
                throw new IllegalArgumentException("reply session id must be nonzero");
            }
        }
    }

    record Pending<R>(long handle, OresFuture<R> future) {
        Pending {
            Objects.requireNonNull(future, "future");
        }
    }

    private static final class Slot {
        long generation = 1;
        OresFuture<?> future;
        Class<?> responseClass;
        String schemaDigest;
        AuthenticatedPeer responder;
        boolean retired;
    }

    private final Endpoint origin;
    /** Secret minted once per origin incarnation. */
    private final ReplyTicketAuthenticator tickets = new ReplyTicketAuthenticator();
    private final Slot[] slots;
    private final int[] freeIndices;
    private int nextUnused;
    private int freeCount;
    private int active;
    private int highWater;
    private boolean closed;

    ReplySlotRegistry(Endpoint origin, int capacity) {
        this.origin = Objects.requireNonNull(origin, "origin");
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("reply slot capacity out of range");
        }
        slots = new Slot[capacity];
        freeIndices = new int[capacity];
    }

    synchronized <R> Pending<R> reserve(
            Class<R> responseClass, String schemaDigest, AuthenticatedPeer responder) {
        Objects.requireNonNull(responseClass, "responseClass");
        Objects.requireNonNull(responder, "responder");
        if (responseClass.isPrimitive() || responseClass == Object.class) {
            throw new IllegalArgumentException("response must have a concrete boxed/nominal type");
        }
        if (schemaDigest == null || schemaDigest.isBlank() || schemaDigest.length() > 128) {
            throw new IllegalArgumentException("response schema ABI digest required");
        }
        if (closed) throw new IllegalStateException("reply registry is closed");
        if (freeCount == 0 && nextUnused == slots.length) {
            throw new IllegalStateException("reply slot capacity exhausted");
        }

        int index = freeCount != 0 ? freeIndices[--freeCount] : nextUnused++;
        Slot slot = slots[index];
        if (slot == null) {
            slot = new Slot();
            slots[index] = slot;
        }
        if (slot.retired || slot.future != null) {
            throw new IllegalStateException("corrupt reply slot freelist");
        }
        OresFuture<R> future = new OresFuture<>();
        long generation = slot.generation;
        slot.future = future;
        slot.responseClass = responseClass;
        slot.schemaDigest = schemaDigest;
        slot.responder = responder;
        active++;
        highWater = Math.max(highWater, active);
        final int pinnedIndex = index;
        future.whenCompleteRuntime((ignored, failure) ->
                releaseIfMatching(pinnedIndex, generation, future));
        return new Pending<>(encode(index, generation), future);
    }


    /**
     * Mint request-specific authority only for a currently outstanding slot.
     * Include these bytes in the outbound remote request; a numeric handle
     * alone must never authorize a remotely supplied reply.
     */
    synchronized byte[] issueRemoteTicket(
            long handle, AuthenticatedPeer responder, String schemaDigest) {
        Slot slot = authorize(origin, handle, responder, schemaDigest);
        if (slot == null) {
            throw new IllegalStateException("no outstanding request for remote reply ticket");
        }
        return tickets.issue(handle, origin, responder, schemaDigest);
    }

    /**
     * For authenticated transport adapters only. Peer identity MUST come from
     * the connection, never from the packet. The entire reply payload must be
     * authenticated and decoded under a trusted, schema-specific codec first.
     */
    synchronized boolean completeRemote(
            Endpoint destination, long handle, AuthenticatedPeer peer,
            String schemaDigest, byte[] replyTicket, Object value) {
        Slot slot = authorize(destination, handle, peer, schemaDigest);
        if (slot == null || !tickets.valid(replyTicket, handle, origin, peer, schemaDigest)
                || value == null || !slot.responseClass.isInstance(value)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        OresFuture<Object> future = (OresFuture<Object>) slot.future;
        return future.completeFromRuntime(value);
    }

    /** Remote failure must carry the same request-specific reply authority. */
    synchronized boolean failRemote(
            Endpoint destination, long handle, AuthenticatedPeer peer,
            String schemaDigest, byte[] replyTicket, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Slot slot = authorize(destination, handle, peer, schemaDigest);
        return slot != null
                && tickets.valid(replyTicket, handle, origin, peer, schemaDigest)
                && slot.future.failFromRuntime(failure);
    }

    /** Trusted in-runtime use only; NEVER dispatch untrusted IPC replies here. */

    /**
     * False means dropped: stale, duplicate, cancelled, timed out, wrong
     * authority, wrong schema/type, wrong origin epoch, or closed registry.
     * Rejected invalid replies MUST NOT consume the genuine caller's slot.
     */
    synchronized boolean complete(
            Endpoint destination, long handle, AuthenticatedPeer authenticatedPeer,
            String schemaDigest, Object value) {
        Slot slot = authorize(destination, handle, authenticatedPeer, schemaDigest);
        if (slot == null || value == null || !slot.responseClass.isInstance(value)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        OresFuture<Object> future = (OresFuture<Object>) slot.future;
        return future.completeFromRuntime(value);
    }

    /** Trusted in-runtime failure only; not an IPC/remote entrypoint. */
    synchronized boolean fail(
            Endpoint destination, long handle, AuthenticatedPeer authenticatedPeer,
            String schemaDigest, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Slot slot = authorize(destination, handle, authenticatedPeer, schemaDigest);
        if (slot == null) return false;
        return slot.future.failFromRuntime(failure);
    }

    /**
     * Called by the trusted origin's monotonic deadline wheel, not by a peer.
     * The returned Future settles exactly once against simultaneous completion,
     * cancellation, close and timeout; accepted remote work is not undone.
     */
    synchronized boolean timeout(long handle) {
        Slot slot = find(handle);
        if (slot == null) return false;
        return slot.future.failFromRuntime(
                new TimeoutException("actor request reply deadline elapsed"));
    }

    synchronized int pendingCount() { return active; }
    synchronized int highWaterMark() { return highWater; }
    synchronized int capacity() { return slots.length; }
    Endpoint origin() { return origin; }

    /** Fence this origin incarnation and fail all outstanding completions. */
    synchronized void terminate(Throwable reason) {
        Objects.requireNonNull(reason, "reason");
        if (closed) return;
        closed = true;
        for (int i = 0; i < nextUnused; i++) {
            Slot slot = slots[i];
            if (slot != null && slot.future != null) {
                slot.future.failFromRuntime(reason);
            }
        }
    }

    @Override public void close() {
        terminate(new IllegalStateException("reply origin terminated"));
    }

    private Slot authorize(
            Endpoint destination, long handle, AuthenticatedPeer peer, String schemaDigest) {
        // Compare the full runtime-owned route before performing indexed lookup.
        if (closed || !origin.equals(destination)) return null;
        Slot slot = find(handle);
        if (slot == null || !slot.responder.equals(peer)
                || !slot.schemaDigest.equals(schemaDigest)) {
            return null;
        }
        return slot;
    }

    private Slot find(long handle) {
        long indexUnsigned = handle >>> 32;
        long generation = handle & MASK32;
        if (generation == 0 || indexUnsigned >= nextUnused) return null;
        Slot slot = slots[(int) indexUnsigned];
        return slot != null && slot.future != null
                && slot.generation == generation ? slot : null;
    }

    private synchronized void releaseIfMatching(
            int index, long generation, OresFuture<?> expectedFuture) {
        Slot slot = slots[index];
        if (slot == null || slot.generation != generation
                || slot.future != expectedFuture) {
            return;
        }
        slot.future = null;
        slot.responseClass = null;
        slot.schemaDigest = null;
        slot.responder = null;
        active--;
        // u32 must NEVER wrap to a previously valid generation (ABA).
        if (slot.generation == MASK32) {
            slot.retired = true;
        } else {
            slot.generation++;
            freeIndices[freeCount++] = index;
        }
    }

    private static long encode(int index, long generation) {
        return ((long) index << 32) | (generation & MASK32);
    }
}
