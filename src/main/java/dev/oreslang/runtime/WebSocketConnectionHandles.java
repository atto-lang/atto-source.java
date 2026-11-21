package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/**
 * Bounded internal mapping between opaque actor connection handles and the
 * host-only WebSocket socket transport.
 *
 * <p>This is a Java runtime boundary for a future closed-world Oreslang
 * builtin, NOT itself a guest-visible API. Handles carry no Socket, ActorRef,
 * NET capability, or host object getter. Each operation rechecks the exact
 * actor incarnation in WebSocketActorClaim; possession of a token alone
 * never grants another actor authority.</p>
 *
 * <p>Entries are retained until socket closure, permit cleanup, and native
 * resource loans have all retired, so a
 * closed actor cannot churn handles and bypass the fixed table capacity.
 * Reused slots advance a non-wrapping generation and cannot resurrect old
 * handles. One registry owns at most MAX_HANDLES simultaneous connections.
 * The application still needs an aggregate quota across all registries.</p>
 */
final class WebSocketConnectionHandles implements AutoCloseable {
    static final int MAX_HANDLES = 16;

    /** Opaque, non-serializable identity; only this table can construct it. */
    static final class Handle {
        private final WebSocketConnectionHandles table;
        private final int slot;
        private final long generation;

        private Handle(WebSocketConnectionHandles table, int slot, long generation) {
            this.table = table;
            this.slot = slot;
            this.generation = generation;
        }

        @Override public String toString() {
            return "WebSocketConnectionHandle(<opaque>)";
        }
    }

    private static final class Entry {
        final WebSocketLoopbackListener.Connection connection;
        final WebSocketActorClaim claim;
        final long generation;

        Entry(WebSocketLoopbackListener.Connection connection,
                WebSocketActorClaim claim, long generation) {
            this.connection = connection;
            this.claim = claim;
            this.generation = generation;
        }
    }

    private final Entry[] entries;
    private final long[] generations;
    private boolean closed;

    WebSocketConnectionHandles(int capacity) {
        if (capacity < 1 || capacity > MAX_HANDLES) {
            throw new IllegalArgumentException("WebSocket handle capacity must be 1..16");
        }
        entries = new Entry[capacity];
        generations = new long[capacity];
    }

    /**
     * One-time supervisor claim, after an already authorized WebSocket
     * upgrade. The host connection performs scoped NET checks separately.
     * No per-connection threads or dynamically growing handle maps.
     */
    synchronized Handle claim(WebSocketLoopbackListener.Connection connection,
            ActorRuntime runtime, ActorRuntime.ActorRef<?> owner) {
        requireHostSupervisor();
        Objects.requireNonNull(connection, "WebSocket connection");
        Objects.requireNonNull(runtime, "actor runtime");
        Objects.requireNonNull(owner, "actor owner");
        if (closed) throw new RejectedExecutionException("WebSocket handle table closed");
        int free = -1;
        for (int i = 0; i < entries.length; i++) {
            if (entries[i] == null || entries[i].connection.isFullyRetired()) {
                free = i;
                break;
            }
        }
        if (free < 0) {
            throw new RejectedExecutionException("WebSocket opaque handle capacity exhausted");
        }
        if (generations[free] == Long.MAX_VALUE) {
            throw new RejectedExecutionException("WebSocket handle generation exhausted");
        }
        // claimActor enforces supervisor context, exact live actor generation,
        // and one claim for this physical connection. Do it BEFORE publishing
        // the slot, so failed claims do not leak or replace prior handles.
        WebSocketActorClaim claim = connection.claimActor(runtime, owner);
        if (claim.revoked() || connection.isRetired()) {
            claim.revoke();
            throw new RejectedExecutionException("WebSocket owner or socket retired during claim");
        }
        long generation = ++generations[free];
        entries[free] = new Entry(connection, claim, generation);
        return new Handle(this, free, generation);
    }

    /** Actor-turn operation; bounded USER enqueue and Future remain host-owned. */
    OresFuture<Void> sendTextAsync(Handle handle, String text) {
        Entry entry = resolve(handle);
        entry.claim.requireOwnerTurn();
        return entry.connection.sendTextAsync(text);
    }

    /** Defensive payload cloning and quota checks remain in the host writer. */
    OresFuture<Void> sendBinaryAsync(Handle handle, byte[] payload) {
        Entry entry = resolve(handle);
        entry.claim.requireOwnerTurn();
        return entry.connection.sendBinaryAsync(payload);
    }

    /**
     * Actor-requested close only revokes authority. The listener's bounded
     * host retirement sweep owns physical socket closure; never close the
     * kernel socket on an actor or CONTROL carrier.
     */
    void revokeFromOwnerTurn(Handle handle) {
        Entry entry = resolve(handle);
        entry.claim.requireOwnerTurn();
        entry.claim.revoke();
    }

    /**
     * Lookup does not convey authority. Every operation still checks the
     * current actor runtime, generation, and active mailbox turn.
     */
    private synchronized Entry resolve(Handle handle) {
        if (handle == null || handle.table != this || closed
                || handle.slot < 0 || handle.slot >= entries.length) {
            throw new SecurityException("unknown WebSocket opaque handle");
        }
        Entry entry = entries[handle.slot];
        if (entry == null || entry.generation != handle.generation
                || entry.claim.revoked() || entry.connection.isRetired()) {
            throw new SecurityException("stale or retired WebSocket opaque handle");
        }
        return entry;
    }

    /**
     * Supervisor retirement is logically immediate and never performs I/O.
     * Stale entries cannot be used; physical close is owned by the existing
     * finite host-listener retirement worker.
     */
    @Override public synchronized void close() {
        requireHostSupervisor();
        if (closed) return;
        closed = true;
        for (int i = 0; i < entries.length; i++) {
            Entry entry = entries[i];
            entries[i] = null;
            if (entry != null) entry.claim.revoke();
        }
    }

    private static void requireHostSupervisor() {
        if (ActorRuntime.inActorExecution() || ActorRuntime.isActorCarrierThread()) {
            throw new SecurityException("WebSocket handle issuance/retirement is host-only");
        }
    }
}
