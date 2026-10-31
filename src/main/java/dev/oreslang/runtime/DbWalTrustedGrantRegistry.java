package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Host-only, context-owned WAL shard admission. The issuing host service must
 * authenticate the tenant/shard BEFORE calling issueForCurrentActor. Merely
 * knowing a numeric tenant ID never grants access to the filesystem.
 *
 * Grants are non-serializable object identities, unexported to Oreslang DATA,
 * and bound to one ActorRuntime, physical actor identity and generation.
 * A DRAINING grant keeps its shard reserved until verified file/loan close.
 * This is an internal registry, not a public native guest capability.
 */
final class DbWalTrustedGrantRegistry {
    private record Shard(int tenantId, int shardId) { }
    private enum Phase { ACTIVE, DRAINING, RETIRED }

    static final class Grant {
        private final DbWalTrustedGrantRegistry issuer;
        private final ActorRuntime.ActorId actorId;
        private final Shard shard;
        private final int generation;
        private Phase phase = Phase.ACTIVE;

        private Grant(DbWalTrustedGrantRegistry issuer,
                ActorRuntime.ActorId actorId, Shard shard, int generation) {
            this.issuer = issuer;
            this.actorId = actorId;
            this.shard = shard;
            this.generation = generation;
        }

        int generation() { return generation; }

        boolean issuedBy(DbWalTrustedGrantRegistry registry) {
            return issuer == registry;
        }

        void requireLive(ActorRuntime expectedRuntime) {
            issuer.requireCurrentActor(this, expectedRuntime);
        }

        void beginDrain(ActorRuntime expectedRuntime) {
            issuer.beginDrainForCurrentActor(this, expectedRuntime);
        }

        void physicalRetire() {
            issuer.retireAfterPhysicalClose(this);
        }
    }

    private final ActorRuntime runtime;
    private final Map<Shard, Grant> owners = new HashMap<>();
    private boolean sealed;

    DbWalTrustedGrantRegistry(ActorRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    synchronized Grant issueForCurrentActor(int tenantId, int shardId, int generation) {
        ActorRuntime.ActorId actor = currentActor();
        if (sealed || tenantId <= 0 || shardId < 0 || generation <= 0)
            throw new SecurityException("WAL shard grant issuance denied");
        Shard shard = new Shard(tenantId, shardId);
        // Even a draining previous generation prevents a new incarnation
        // from admitting writes until verified physical close+release.
        if (owners.containsKey(shard))
            throw new IllegalStateException("WAL shard already owned or draining");
        Grant grant = new Grant(this, actor, shard, generation);
        owners.put(shard, grant);
        return grant;
    }

    synchronized void requireCurrentActor(Grant grant, ActorRuntime expectedRuntime) {
        Objects.requireNonNull(grant);
        if (runtime != expectedRuntime || sealed || grant.issuer != this
                || owners.get(grant.shard) != grant
                || grant.phase != Phase.ACTIVE
                || !grant.actorId.equals(currentActor()))
            throw new SecurityException("foreign, stale, or inactive WAL shard grant");
    }

    synchronized void beginDrainForCurrentActor(
            Grant grant, ActorRuntime expectedRuntime) {
        Objects.requireNonNull(grant);
        if (runtime != expectedRuntime || grant.issuer != this
                || owners.get(grant.shard) != grant
                || grant.phase == Phase.RETIRED
                || !grant.actorId.equals(currentActor()))
            throw new SecurityException("WAL shard drain requires its exact owner");
        grant.phase = Phase.DRAINING;
    }

    /**
     * Called only by the trusted physical close path after actual FD close
     * and its last resource loan have retired. This method does NOT itself
     * prove that condition. No guest message may invoke it.
     */
    synchronized void retireAfterPhysicalClose(Grant grant) {
        Objects.requireNonNull(grant);
        if (grant.issuer != this || owners.get(grant.shard) != grant
                || grant.phase != Phase.DRAINING)
            throw new IllegalStateException("WAL physical retirement out of order");
        grant.phase = Phase.RETIRED;
        owners.remove(grant.shard);
    }

    // Seal future issuance on context termination. Keep existing grants
    // strongly reachable until trusted physical close/reclamation completes.
    synchronized void seal() { sealed = true; }

    synchronized int activeShardCount() { return owners.size(); }

    private ActorRuntime.ActorId currentActor() {
        if (ActorRuntime.currentActorRuntime() != runtime)
            throw new SecurityException("WAL grant crossed ActorRuntime boundary");
        return ActorRuntime.currentActorId().orElseThrow(() ->
                new SecurityException("WAL grants require actor execution context"));
    }
}
