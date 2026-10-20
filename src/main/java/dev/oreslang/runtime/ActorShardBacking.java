package dev.oreslang.runtime;

/**
 * Package-private lifecycle hook for actor-owned DOD shard storage.
 *
 * <p>The runtime invokes this before returning the shard's allocation budget,
 * so Java arrays are scrubbed/detached and private direct-memory leases are
 * revoked before the actor is physically finalized.</p>
 */
sealed interface ActorShardBacking permits ActorShardColumns, ActorDenseIndexColumns {
    void revokeBackingFromLease();
    boolean ownedBy(ActorRuntime runtime, ActorRuntime.ActorId actor);
    boolean isPrivateBacked();
}
