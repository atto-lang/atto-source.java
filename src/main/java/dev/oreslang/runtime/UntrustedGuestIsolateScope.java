package dev.oreslang.runtime;

import org.graalvm.polyglot.Context;

/**
 * Host-only capability to an already constructed untrusted polyglot context.
 * A pooled group may admit many remote actors; a dedicated isolate lease must
 * admit at most one. No raw Context or guest Value travels in actor messages.
 */
interface UntrustedGuestIsolateScope {
    Context contextForSupervisor();

    /**
     * Called before guest actor construction. Dedicated leases consume their
     * one admission even when construction fails, rather than retrying against
     * potentially partially initialized guest state.
     */
    default void admitActorSpawn() { }
}
