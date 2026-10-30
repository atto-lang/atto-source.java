package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runtime-owned immutable authorization chain. A child scope keeps a
 * reference to every ancestor restriction, so neither wildcard rules nor
 * unusual path/network normalizations can turn delegation into escalation.
 *
 * This is deliberately not a guest-facing capability object and is never
 * transported through actor messages. All host I/O stays subject to the
 * context-level policy and the actor's coarse IsolatePolicy as well.
 */
final class ActorPermissionScope {
    private static final int MAX_RESTRICTION_LAYERS = 128;
    private static final int MAX_SUBTREE_IO = 65_536;
    private final Object ioQuotaLock;
    private final int maxSubtreeIo;
    private int activeSubtreeIo;
    private final ActorPermissionScope parent;
    private final RuntimePermissions restrictions;
    private final int depth;
    /** Monotonic revocation, visible to actor turns and async native-I/O workers. */
    private final AtomicBoolean revoked = new AtomicBoolean();

    private ActorPermissionScope(ActorPermissionScope parent, RuntimePermissions restrictions) {
        this(parent, restrictions, parent == null ? MAX_SUBTREE_IO : parent.maxSubtreeIo);
    }

    private ActorPermissionScope(ActorPermissionScope parent,
            RuntimePermissions restrictions, int maxIo) {
        this.parent = parent;
        this.restrictions = Objects.requireNonNull(restrictions, "restrictions");
        this.depth = parent == null ? 1 : parent.depth + 1;
        if (depth > MAX_RESTRICTION_LAYERS) {
            throw new SecurityException("actor permission delegation depth limit exceeded");
        }
        if (maxIo < 1 || maxIo > MAX_SUBTREE_IO
                || (parent != null && maxIo > parent.maxSubtreeIo)) {
            throw new IllegalArgumentException("child actor cannot widen subtree I/O quota");
        }
        this.maxSubtreeIo = maxIo;
        this.ioQuotaLock = parent == null ? new Object() : parent.ioQuotaLock;
    }

    static ActorPermissionScope root(RuntimePermissions ceiling) {
        return new ActorPermissionScope(null, ceiling);
    }

    ActorPermissionScope attenuate(RuntimePermissions requested) {
        return attenuate(requested, maxSubtreeIo);
    }

    ActorPermissionScope attenuate(RuntimePermissions requested, int maxIo) {
        synchronized (ioQuotaLock) {
            requireActive("delegate actor I/O quota");
            return new ActorPermissionScope(this, requested, maxIo);
        }
    }

    boolean revoke() {
        // Serialize with reservation; only future admission is revoked.
        synchronized (ioQuotaLock) {
            return revoked.compareAndSet(false, true);
        }
    }

    /** Host-only ticket. Caller must release only after physical I/O retirement. */
    static final class IoReservation implements AutoCloseable {
        private final ActorPermissionScope leaf;
        private boolean retired;
        private IoReservation(ActorPermissionScope leaf) { this.leaf = leaf; }

        @Override public void close() {
            synchronized (leaf.ioQuotaLock) {
                if (retired) throw new IllegalStateException("duplicate I/O quota retirement");
                for (ActorPermissionScope p = leaf; p != null; p = p.parent) {
                    if (p.activeSubtreeIo <= 0)
                        throw new IllegalStateException("subtree I/O quota underflow");
                }
                retired = true;
                for (ActorPermissionScope p = leaf; p != null; p = p.parent)
                    p.activeSubtreeIo--;
            }
        }
    }

    IoReservation reserveIo(String operation) {
        synchronized (ioQuotaLock) {
            for (ActorPermissionScope p = this; p != null; p = p.parent) {
                if (p.revoked.get())
                    throw new SecurityException("revoked actor permission for " + operation);
                if (p.activeSubtreeIo >= p.maxSubtreeIo)
                    throw new RejectedExecutionException("actor subtree I/O quota exhausted");
            }
            for (ActorPermissionScope p = this; p != null; p = p.parent)
                p.activeSubtreeIo++;
            return new IoReservation(this);
        }
    }

    int subtreeIoInFlight() {
        synchronized (ioQuotaLock) { return activeSubtreeIo; }
    }

    void requireActive(String operation) {
        for (ActorPermissionScope layer = this; layer != null; layer = layer.parent) {
            if (layer.revoked.get()) {
                throw new SecurityException(
                        "revoked actor permission delegation for " + operation);
            }
        }
    }

    void requireNetworkListen(String resource, String operation) {
        // Recheck at each actual boundary, including async worker callbacks.
        requireActive(operation);
        for (ActorPermissionScope layer = this; layer != null; layer = layer.parent) {
            layer.restrictions.requireNetworkListen(resource, operation);
        }
        // Recheck after policy matching; final admission still races later revoke.
        requireActive(operation);
    }

    void require(RuntimePermissions.Permission permission, String resource, String operation) {
        requireActive(operation);
        // No recursive stack growth, even with adversarial nested delegation.
        for (ActorPermissionScope layer = this; layer != null; layer = layer.parent) {
            layer.restrictions.require(permission, resource, operation);
        }
        // Recheck after policy matching to reduce the revocation race window.
        // This is NOT a linearizable OS-operation fence and cannot roll back
        // operations that have already passed authorization.
        requireActive(operation);
    }
}
