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
    private final ActorPermissionScope parent;
    private final RuntimePermissions restrictions;

    private ActorPermissionScope(ActorPermissionScope parent, RuntimePermissions restrictions) {
        this.parent = parent;
        this.restrictions = Objects.requireNonNull(restrictions, "restrictions");
    }

    static ActorPermissionScope root(RuntimePermissions ceiling) {
        return new ActorPermissionScope(null, ceiling);
    }

    ActorPermissionScope attenuate(RuntimePermissions requested) {
        return new ActorPermissionScope(this, requested);
    }

    void require(RuntimePermissions.Permission permission, String resource, String operation) {
        if (parent != null) parent.require(permission, resource, operation);
        restrictions.require(permission, resource, operation);
    }
}
