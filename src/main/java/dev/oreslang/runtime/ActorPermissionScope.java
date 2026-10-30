package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final AtomicLong NEXT_SCOPE_SERIAL = new AtomicLong();
    private final ActorPermissionScope parent;
    private final RuntimePermissions restrictions;
    private final int depth;
    /** Monotonic revocation, visible to actor turns and async native-I/O workers. */
    private final AtomicBoolean revoked = new AtomicBoolean();
    private final long serial;
    /** Guarded by this scope's monitor. Includes all descendant admissions. */
    private int activePhysicalReceipts;
    /** No drain until an independently proven recovery from quarantined ownership. */
    private boolean physicalDrainQuarantined;

    private ActorPermissionScope(ActorPermissionScope parent, RuntimePermissions restrictions) {
        long next = NEXT_SCOPE_SERIAL.incrementAndGet();
        if (next <= 0) throw new IllegalStateException("actor permission scope identities exhausted");
        this.serial = next;
        this.parent = parent;
        this.restrictions = Objects.requireNonNull(restrictions, "restrictions");
        this.depth = parent == null ? 1 : parent.depth + 1;
        if (depth > MAX_RESTRICTION_LAYERS) {
            throw new SecurityException("actor permission delegation depth limit exceeded");
        }
    }

    static ActorPermissionScope root(RuntimePermissions ceiling) {
        return new ActorPermissionScope(null, ceiling);
    }

    ActorPermissionScope attenuate(RuntimePermissions requested) {
        return new ActorPermissionScope(this, requested);
    }

    /**
     * Revocation is serialized with physical lease admission at this node.
     * It seals future descendant admissions but never blocks for retirement.
     */
    synchronized boolean revoke() {
        return revoked.compareAndSet(false, true);
    }

    /** Immutable proof of no outstanding tracked physical leases at a point in time. */
    record DrainReceipt(long scopeSerial) { }

    /**
     * No callbacks and no waiting: callers can poll this from a supervisor
     * without blocking an actor/CONTROL carrier. A receipt is issued only
     * after revocation sealed admissions and every counted descendant loan
     * or native operation has finished its physical retirement callbacks.
     */
    synchronized Optional<DrainReceipt> tryDrainReceipt() {
        return revoked.get() && activePhysicalReceipts == 0 && !physicalDrainQuarantined
                ? Optional.of(new DrainReceipt(serial))
                : Optional.empty();
    }

    /**
     * Exceptional ownership escape: a handle created after shutdown starts
     * is retained for eventual context close, but it did not acquire a new
     * lease because new admissions were sealed. Invalidate every ancestor's
     * proof rather than misreport a fully drained delegated subtree.
     * This is intentionally monotonic until an independently reviewed
     * reconciliation path exists.
     */
    void quarantinePhysicalDrain() {
        for (ActorPermissionScope node = this; node != null; node = node.parent) {
            synchronized (node) {
                node.physicalDrainQuarantined = true;
            }
        }
    }

    static final class PhysicalLease implements AutoCloseable {
        private final ActorPermissionScope[] chain;
        private final AtomicBoolean retired = new AtomicBoolean();

        private PhysicalLease(ActorPermissionScope[] chain) {
            this.chain = chain;
        }

        @Override public void close() {
            if (!retired.compareAndSet(false, true)) {
                throw new IllegalStateException("duplicate actor physical I/O retirement receipt");
            }
            for (int i = chain.length - 1; i >= 0; --i) {
                ActorPermissionScope node = chain[i];
                synchronized (node) {
                    if (node.activePhysicalReceipts <= 0) {
                        throw new IllegalStateException("actor physical I/O receipt underflow");
                    }
                    node.activePhysicalReceipts--;
                }
            }
        }
    }

    PhysicalLease acquirePhysicalLease(String operation) {
        ActorPermissionScope[] chain = new ActorPermissionScope[depth];
        ActorPermissionScope next = this;
        for (int i = depth - 1; i >= 0; i--) {
            chain[i] = next;
            next = next.parent;
        }
        int acquired = 0;
        try {
            // Root-first admission: no revoked ancestor can regain authority.
            for (ActorPermissionScope node : chain) {
                synchronized (node) {
                    if (node.revoked.get()) {
                        throw new SecurityException("revoked actor physical I/O admission for " + operation);
                    }
                    if (node.activePhysicalReceipts == Integer.MAX_VALUE) {
                        throw new SecurityException("actor physical I/O receipt capacity exhausted");
                    }
                    node.activePhysicalReceipts++;
                }
                acquired++;
            }
            return new PhysicalLease(chain);
        } catch (RuntimeException | Error failure) {
            for (int i = acquired - 1; i >= 0; --i) {
                synchronized (chain[i]) {
                    chain[i].activePhysicalReceipts--;
                }
            }
            throw failure;
        }
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
