package dev.oreslang.runtime;

import java.util.Objects;

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

    void requireNetworkListen(String resource, String operation) {
        // Listener admission must satisfy every parent restriction too.
        for (ActorPermissionScope layer = this; layer != null; layer = layer.parent) {
            layer.restrictions.requireNetworkListen(resource, operation);
        }
    }

    void require(RuntimePermissions.Permission permission, String resource, String operation) {
        // No recursive stack growth, even with adversarial nested delegation.
        for (ActorPermissionScope layer = this; layer != null; layer = layer.parent) {
            layer.restrictions.require(permission, resource, operation);
        }
    }
}
