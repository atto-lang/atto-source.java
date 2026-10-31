package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Internal, host-issued connection claim for exactly one live actor incarnation.
 *
 * <p>This is an authorization fence, not a guest WebSocket API and not a host
 * socket wrapper. A future source-level bridge must map a guest opaque handle
 * to this claim; never expose the transport or an ActorRef to guest code.</p>
 *
 * <p>Terminal callbacks only revoke an atomic token. They NEVER execute
 * physical socket I/O, Future callbacks, or blocking cleanup on an actor
 * carrier. Host-side transport retirement is a separate required step.</p>
 */
final class WebSocketActorClaim {
    private final ActorRuntime runtime;
    private final ActorRuntime.ActorRef<?> owner;
    private final ActorRuntime.ActorAddress address;
    private final AtomicBoolean revoked = new AtomicBoolean();

    private WebSocketActorClaim(ActorRuntime runtime,
            ActorRuntime.ActorRef<?> owner) {
        this.runtime = runtime;
        this.owner = owner;
        this.address = owner.address();
    }

    /** Supervisor-only one-time claim; foreign/stopped actors cannot claim. */
    static WebSocketActorClaim issue(ActorRuntime runtime,
            ActorRuntime.ActorRef<?> owner) {
        if (ActorRuntime.inActorExecution() || ActorRuntime.isActorCarrierThread()) {
            throw new SecurityException("WebSocket actor claims are supervisor-issued");
        }
        Objects.requireNonNull(runtime, "actor runtime");
        Objects.requireNonNull(owner, "actor reference");
        if (!runtime.isLiveIncarnation(owner)) {
            throw new SecurityException("WebSocket claim requires live actor incarnation");
        }
        WebSocketActorClaim claim = new WebSocketActorClaim(runtime, owner);
        // Finalization can race issuance. Runtime Future callbacks run in the
        // settling context, so this callback must remain atomic and nonblocking.
        owner.done().whenCompleteRuntime((ignored, failure) -> claim.revoke());
        return claim;
    }

    ActorRuntime.ActorAddress address() {
        return address;
    }

    boolean revoked() {
        return revoked.get() || !runtime.isLiveIncarnation(owner);
    }

    /**
     * Revocation and bounded USER enqueue synchronize on the same gate.
     * If revoke wins, the queued work is never admitted. If admission wins,
     * the accepted write is subject to physical socket retirement, not an
     * invented completion receipt. Never do network I/O under this monitor.
     */
    synchronized boolean revoke() {
        return revoked.compareAndSet(false, true);
    }

    /**
     * Linearizable owner-checked admission. The supplied operation must
     * reserve ONLY a bounded outbound queue slot; it must not block, schedule
     * work, or execute an arbitrary guest callback.
     */
    synchronized <T> T admitOwnerTurn(Supplier<T> enqueue) {
        Objects.requireNonNull(enqueue, "bounded outbound admission");
        requireOwnerTurn();
        return enqueue.get();
    }

    /**
     * Require the same runtime, actor ID, and exact actor-ref incarnation
     * on this active mailbox turn. A background thread merely holding the
     * ActorRef is not authorized, nor is an actor in a different runtime.
     */
    void requireOwnerTurn() {
        if (ActorRuntime.currentActorRuntime() != runtime
                || !owner.id().equals(ActorRuntime.currentActorId().orElse(null))
                || revoked()) {
            throw new SecurityException("WebSocket actor connection is not owned by this turn");
        }
    }
}
