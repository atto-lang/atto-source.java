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
    private static final int MAX_SUBTREE_HANDLES = 65_536;
    private static final long MAX_ACCOUNTED_BYTES = 64L * 1024 * 1024;
    private final int maxSubtreeHandles;
    private final long maxSubtreeAccountedBytes;
    private int registeredSubtreeHandles;
    private long registeredSubtreeBytes;
    private final Object ioQuotaLock;
    private final int maxSubtreeIo;
    private int activeSubtreeIo;
    private final ActorPermissionScope parent;
    private final RuntimePermissions restrictions;
    private final int depth;
    /** Monotonic revocation, visible to actor turns and async native-I/O workers. */
    private final AtomicBoolean revoked = new AtomicBoolean();

    private ActorPermissionScope(ActorPermissionScope parent, RuntimePermissions restrictions) {
        this(parent, restrictions, parent == null ? MAX_SUBTREE_IO : parent.maxSubtreeIo,
                parent == null ? MAX_SUBTREE_HANDLES : parent.maxSubtreeHandles,
                parent == null ? MAX_ACCOUNTED_BYTES : parent.maxSubtreeAccountedBytes);
    }

    private ActorPermissionScope(ActorPermissionScope parent,
            RuntimePermissions restrictions, int maxIo) {
        this(parent, restrictions, maxIo,
                parent == null ? MAX_SUBTREE_HANDLES : parent.maxSubtreeHandles,
                parent == null ? MAX_ACCOUNTED_BYTES : parent.maxSubtreeAccountedBytes);
    }

    private ActorPermissionScope(ActorPermissionScope parent,
            RuntimePermissions restrictions, int maxIo, int maxHandles, long maxBytes) {
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
        if (maxHandles < 1 || maxHandles > MAX_SUBTREE_HANDLES
                || (parent != null && maxHandles > parent.maxSubtreeHandles)
                || maxBytes < 0 || maxBytes > MAX_ACCOUNTED_BYTES
                || (parent != null && maxBytes > parent.maxSubtreeAccountedBytes)) {
            throw new IllegalArgumentException(
                    "child actor cannot widen registered resource handle/byte budget");
        }
        this.maxSubtreeIo = maxIo;
        this.maxSubtreeHandles = maxHandles;
        this.maxSubtreeAccountedBytes = maxBytes;
        this.ioQuotaLock = parent == null ? new Object() : parent.ioQuotaLock;
    }

    static ActorPermissionScope root(RuntimePermissions ceiling) {
        return new ActorPermissionScope(null, ceiling);
    }

    ActorPermissionScope attenuate(RuntimePermissions requested) {
        return attenuate(requested, maxSubtreeIo);
    }

    ActorPermissionScope attenuate(RuntimePermissions requested, int maxIo) {
        return attenuate(requested, maxIo, maxSubtreeHandles, maxSubtreeAccountedBytes);
    }

    ActorPermissionScope attenuate(RuntimePermissions requested, int maxIo,
            int maxHandles, long maxBytes) {
        synchronized (ioQuotaLock) {
            requireActive("delegate actor resource quotas");
            return new ActorPermissionScope(this, requested, maxIo, maxHandles, maxBytes);
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

    /**
     * Reservation for a trusted, registered native resource. Never attach this
     * to a guest object: callbacks must not forge a physical retirement receipt.
     */
    static final class ResourceReservation implements AutoCloseable {
        private final ActorPermissionScope leaf;
        private final long accountedBytes;
        private boolean retired;

        private ResourceReservation(ActorPermissionScope leaf, long accountedBytes) {
            this.leaf = leaf;
            this.accountedBytes = accountedBytes;
        }

        @Override public void close() {
            synchronized (leaf.ioQuotaLock) {
                if (retired)
                    throw new IllegalStateException("duplicate resource quota retirement");
                for (ActorPermissionScope p = leaf; p != null; p = p.parent) {
                    if (p.registeredSubtreeHandles <= 0
                            || p.registeredSubtreeBytes < accountedBytes)
                        throw new IllegalStateException("resource quota receipt underflow");
                }
                retired = true;
                for (ActorPermissionScope p = leaf; p != null; p = p.parent) {
                    p.registeredSubtreeHandles--;
                    p.registeredSubtreeBytes -= accountedBytes;
                }
            }
        }
    }

    ResourceReservation reserveResource(long accountedBytes, String operation) {
        if (accountedBytes < 0)
            throw new IllegalArgumentException("negative native resource byte accounting");
        synchronized (ioQuotaLock) {
            for (ActorPermissionScope p = this; p != null; p = p.parent) {
                if (p.revoked.get())
                    throw new SecurityException("revoked actor permission for " + operation);
                if (p.registeredSubtreeHandles >= p.maxSubtreeHandles
                        || accountedBytes > p.maxSubtreeAccountedBytes - p.registeredSubtreeBytes)
                    throw new RejectedExecutionException(
                            "actor registered resource handle/byte quota exhausted");
            }
            for (ActorPermissionScope p = this; p != null; p = p.parent) {
                p.registeredSubtreeHandles++;
                p.registeredSubtreeBytes += accountedBytes;
            }
            return new ResourceReservation(this, accountedBytes);
        }
    }

    int registeredResourceCount() {
        synchronized (ioQuotaLock) { return registeredSubtreeHandles; }
    }

    long registeredResourceBytes() {
        synchronized (ioQuotaLock) { return registeredSubtreeBytes; }
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
