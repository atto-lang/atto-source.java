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
    /**
     * A host-supplied tenant/shard authorization decision. The default
     * context constructor DENIES ALL until a trusted database host service
     * explicitly configures a real authenticated mapping. The callback must
     * be bounded/nonblocking; never dispatch guest code under this monitor.
     */
    @FunctionalInterface
    interface HostTenantAdmission {
        boolean allows(ActorRuntime.ActorId actor, int tenantId, int shardId);
    }

    private record Shard(int tenantId, int shardId) { }
    private enum Phase { ACTIVE, DRAINING, RETIRED }

    static final class Grant {
        private final DbWalTrustedGrantRegistry issuer;
        private final ActorRuntime.ActorId actorId;
        private final Shard shard;
        private final int generation;
        private Phase phase = Phase.ACTIVE;
        private DbDurableWalFile boundFile;

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

        void bindPhysicalFile(DbDurableWalFile file) {
            issuer.bindPhysicalFile(this, file);
        }

        void physicalRetire(DbDurableWalFile file,
                DbWalAuthorityLifecycle lifecycle) {
            issuer.retireAfterPhysicalClose(this, file, lifecycle);
        }
    }

    private final ActorRuntime runtime;
    private final HostTenantAdmission tenantAdmission;
    private final Map<Shard, Grant> owners = new HashMap<>();
    private boolean sealed;

    DbWalTrustedGrantRegistry(ActorRuntime runtime) {
        this(runtime, (actor, tenant, shard) -> false);
    }

    DbWalTrustedGrantRegistry(ActorRuntime runtime,
            HostTenantAdmission tenantAdmission) {
        this.runtime = Objects.requireNonNull(runtime);
        this.tenantAdmission = Objects.requireNonNull(tenantAdmission);
    }

    synchronized Grant issueForCurrentActor(int tenantId, int shardId, int generation) {
        ActorRuntime.ActorId actor = currentActor();
        if (sealed || tenantId <= 0 || shardId < 0 || generation <= 0
                || !tenantAdmission.allows(actor, tenantId, shardId))
            throw new SecurityException(
                    "WAL shard grant denied: no trusted tenant/shard admission");
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
     * Bind the exact physical WAL object once. An open that finishes after
     * drain/revocation cannot publish a usable grant. Never bind by pathname
     * or a guest-supplied file descriptor.
     */
    synchronized void bindPhysicalFile(Grant grant, DbDurableWalFile file) {
        Objects.requireNonNull(grant);
        Objects.requireNonNull(file);
        if (sealed || grant.issuer != this || owners.get(grant.shard) != grant
                || grant.phase != Phase.ACTIVE || grant.boundFile != null
                || file.closed())
            throw new SecurityException("WAL grant already bound, stale or draining");
        grant.boundFile = file;
    }

    /**
     * Host-only final retirement: require same bound file, its real closed
     * state, and the WAL lifecycle's post-completion-callback CLOSED state.
     * Callers must obtain that state through the AsyncNativeIo physical
     * retirement hook; this is NOT a guest-callable close receipt.
     */
    synchronized void retireAfterPhysicalClose(Grant grant,
            DbDurableWalFile file, DbWalAuthorityLifecycle lifecycle) {
        Objects.requireNonNull(grant);
        Objects.requireNonNull(file);
        Objects.requireNonNull(lifecycle);
        if (grant.issuer != this || owners.get(grant.shard) != grant
                || grant.phase != Phase.DRAINING || grant.boundFile != file
                || !file.closed()
                || lifecycle.phase() != DbWalAuthorityLifecycle.Phase.CLOSED)
            throw new IllegalStateException("WAL unverified physical retirement");
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
