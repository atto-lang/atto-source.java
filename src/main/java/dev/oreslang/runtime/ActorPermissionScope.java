package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ActorPermissionScope parent;
    private final RuntimePermissions restrictions;
    private final int depth;
    /** Monotonic revocation, visible to actor turns and async native-I/O workers. */
    private final AtomicBoolean revoked = new AtomicBoolean();

    private ActorPermissionScope(ActorPermissionScope parent, RuntimePermissions restrictions) {
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

    boolean revoke() {
        // Only the specific delegated node is revoked. Independent sibling
        // branches sharing a parent remain unaffected.
        return revoked.compareAndSet(false, true);
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
