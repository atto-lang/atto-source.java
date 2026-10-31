package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime-owned immutable permission attenuation chain.
 *
 * <p>Physical drain is evidence only; it never independently authorizes actor
 * reclamation. ActorRuntime composes this scope proof with its generation-bound
 * PhysicalRetirementReceipt before arena release/DONE publication.</p>
 */
final class ActorPermissionScope {
    private static final int MAX_RESTRICTION_LAYERS = 128;
    private static final AtomicLong NEXT_SCOPE_SERIAL = new AtomicLong();

    private final ActorPermissionScope parent;
    private final RuntimePermissions restrictions;
    private final int depth;
    private final AtomicBoolean revoked = new AtomicBoolean();
    private final long serial;
    private final Runnable stateChanged;

    /** Guarded by this scope's monitor; includes descendant physical leases. */
    private int activePhysicalReceipts;
    /** Monotonic fail-closed marker for ownership that escaped trusted accounting. */
    private boolean physicalDrainQuarantined;
    /** Stable receipt identity once this revoked subtree is genuinely drained. */
    private DrainReceipt issuedReceipt;

    private ActorPermissionScope(
            ActorPermissionScope parent,
            RuntimePermissions restrictions,
            Runnable stateChanged) {
        long next = NEXT_SCOPE_SERIAL.incrementAndGet();
        if (next <= 0) {
            throw new IllegalStateException("actor permission scope identities exhausted");
        }
        this.serial = next;
        this.parent = parent;
        this.restrictions = Objects.requireNonNull(restrictions, "restrictions");
        this.stateChanged = Objects.requireNonNull(stateChanged, "stateChanged");
        this.depth = parent == null ? 1 : parent.depth + 1;
        if (depth > MAX_RESTRICTION_LAYERS) {
            throw new SecurityException("actor permission delegation depth limit exceeded");
        }
    }

    static ActorPermissionScope root(
            RuntimePermissions ceiling,
            Runnable stateChanged) {
        return new ActorPermissionScope(null, ceiling, stateChanged);
    }

    ActorPermissionScope attenuate(
            RuntimePermissions requested,
            Runnable delegatedStateChanged) {
        requireActive("permission delegation");
        return new ActorPermissionScope(
                this,
                Objects.requireNonNull(requested, "requested restrictions"),
                delegatedStateChanged);
    }

    record DrainReceipt(long scopeSerial) { }

    boolean revoke() {
        final boolean changed;
        final boolean notify;
        synchronized (this) {
            changed = revoked.compareAndSet(false, true);
            notify = issueIfReadyLocked();
        }
        if (changed || notify) stateChanged.run();
        return changed;
    }

    Optional<DrainReceipt> tryDrainReceipt() {
        synchronized (this) {
            issueIfReadyLocked();
            return Optional.ofNullable(issuedReceipt);
        }
    }

    boolean isDrained() {
        synchronized (this) {
            issueIfReadyLocked();
            return issuedReceipt != null && !physicalDrainQuarantined;
        }
    }

    void quarantinePhysicalDrain() {
        List<Runnable> callbacks = new ArrayList<>();
        for (ActorPermissionScope node = this; node != null; node = node.parent) {
            boolean changed = false;
            synchronized (node) {
                if (!node.physicalDrainQuarantined) {
                    node.physicalDrainQuarantined = true;
                    node.issuedReceipt = null;
                    changed = true;
                }
            }
            if (changed) callbacks.add(node.stateChanged);
        }
        for (Runnable callback : callbacks) callback.run();
    }

    static final class PhysicalLease implements AutoCloseable {
        private final ActorPermissionScope[] chain;
        private final AtomicBoolean retired = new AtomicBoolean();

        private PhysicalLease(ActorPermissionScope[] chain) {
            this.chain = chain;
        }

        @Override
        public void close() {
            if (!retired.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "duplicate actor permission physical retirement");
            }
            List<Runnable> callbacks = new ArrayList<>();
            for (int i = chain.length - 1; i >= 0; --i) {
                ActorPermissionScope node = chain[i];
                boolean notify;
                synchronized (node) {
                    if (node.activePhysicalReceipts <= 0) {
                        throw new IllegalStateException(
                                "actor permission physical receipt underflow");
                    }
                    node.activePhysicalReceipts--;
                    notify = node.issueIfReadyLocked();
                }
                if (notify) callbacks.add(node.stateChanged);
            }
            for (Runnable callback : callbacks) callback.run();
        }
    }

    PhysicalLease acquirePhysicalLease(String operation) {
        Objects.requireNonNull(operation, "operation");
        ActorPermissionScope[] chain = new ActorPermissionScope[depth];
        ActorPermissionScope next = this;
        for (int i = depth - 1; i >= 0; --i) {
            chain[i] = next;
            next = next.parent;
        }

        int acquired = 0;
        try {
            for (ActorPermissionScope node : chain) {
                synchronized (node) {
                    if (node.revoked.get()) {
                        throw new SecurityException(
                                "revoked actor physical I/O admission for " + operation);
                    }
                    if (node.physicalDrainQuarantined) {
                        throw new SecurityException(
                                "quarantined actor permission scope cannot admit " + operation);
                    }
                    if (node.activePhysicalReceipts == Integer.MAX_VALUE) {
                        throw new SecurityException(
                                "actor permission physical receipt capacity exhausted");
                    }
                    node.activePhysicalReceipts++;
                    node.issuedReceipt = null;
                }
                acquired++;
            }
            return new PhysicalLease(chain);
        } catch (RuntimeException | Error failure) {
            List<Runnable> callbacks = new ArrayList<>();
            for (int i = acquired - 1; i >= 0; --i) {
                ActorPermissionScope node = chain[i];
                boolean notify;
                synchronized (node) {
                    node.activePhysicalReceipts--;
                    notify = node.issueIfReadyLocked();
                }
                if (notify) callbacks.add(node.stateChanged);
            }
            for (Runnable callback : callbacks) callback.run();
            throw failure;
        }
    }

    void requireActive(String operation) {
        for (ActorPermissionScope layer = this;
             layer != null;
             layer = layer.parent) {
            if (layer.revoked.get()) {
                throw new SecurityException(
                        "revoked actor permission delegation for " + operation);
            }
            synchronized (layer) {
                if (layer.physicalDrainQuarantined) {
                    throw new SecurityException(
                            "quarantined actor permission delegation for " + operation);
                }
            }
        }
    }

    void requireNetworkListen(String resource, String operation) {
        requireActive(operation);
        for (ActorPermissionScope layer = this;
             layer != null;
             layer = layer.parent) {
            layer.restrictions.require(
                    RuntimePermissions.Permission.NET,
                    resource,
                    operation);
        }
        requireActive(operation);
    }

    void require(
            RuntimePermissions.Permission permission,
            String resource,
            String operation) {
        requireActive(operation);
        for (ActorPermissionScope layer = this;
             layer != null;
             layer = layer.parent) {
            layer.restrictions.require(permission, resource, operation);
        }
        requireActive(operation);
    }

    private boolean issueIfReadyLocked() {
        if (!revoked.get()
                || activePhysicalReceipts != 0
                || physicalDrainQuarantined
                || issuedReceipt != null) {
            return false;
        }
        issuedReceipt = new DrainReceipt(serial);
        return true;
    }
}
