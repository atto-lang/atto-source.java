package dev.oreslang.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Read-only, host-authenticated view of current-main virtual actor ownership.
 *
 * No activation registration, generator, physical worker map, mailbox or
 * persistence state exists here. Every snapshot/page is projected directly
 * from ActorRuntime's ONE generation and physical-retirement authority.
 * This deliberately does not reuse the old mutable ActivationRegistry
 * directory or claim to implement a compiler-backed guest virtual actor.
 *
 * This adapter performs bounded synchronous host metadata reads (not actor
 * guest callbacks). It does not claim #2701's asynchronous CONTROL inspection
 * queue or distributed inspection semantics.
 */
public final class VirtualActorCurrentMainDirectory implements AutoCloseable {
    public static final long MAX_GRANT_TTL_NANOS = TimeUnit.SECONDS.toNanos(5);

    public enum Lifecycle {
        ACTIVATING, PHYSICAL_OWNERSHIP_HELD, TERMINAL_PENDING,
        QUARANTINED, PASSIVATED, ABORTED
    }

    public record Snapshot(
            int schemaVersion,
            VirtualActorActivationRegistry.Identity identity,
            String address,
            ActorRuntime.SupervisorId supervisorId,
            ActorRuntime.ActorGroupId ownerGroupId,
            long activationGeneration,
            Lifecycle lifecycle,
            Optional<ActorRuntime.ActorId> physicalActorId,
            boolean ownerGroupClosed,
            Instant observedAt,
            boolean partial) {
        public Snapshot {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(supervisorId, "supervisorId");
            Objects.requireNonNull(ownerGroupId, "ownerGroupId");
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(physicalActorId, "physicalActorId");
            Objects.requireNonNull(observedAt, "observedAt");
            if (schemaVersion != 1 || activationGeneration <= 0 || !partial)
                throw new IllegalArgumentException("invalid current-main virtual actor snapshot");
        }
    }

    public record Page(
            List<Snapshot> entries, long revision,
            Optional<VirtualActorActivationRegistry.Identity> nextAfter) {
        public Page {
            entries = List.copyOf(entries);
            nextAfter = Objects.requireNonNull(nextAfter, "nextAfter");
            if (revision <= 0)
                throw new IllegalArgumentException("invalid virtual actor page revision");
        }
    }

    /**
     * Identity-bound, short-lived, non-transferable read authority. It is not
     * serializable and does not confer supervisor or activation privileges.
     */
    public static final class Grant {
        private final VirtualActorCurrentMainDirectory owner;
        private final Object session;
        private final long issuedAtNanos;
        private final long ttlNanos;
        private final AtomicBoolean revoked = new AtomicBoolean();
        private final AtomicBoolean reading = new AtomicBoolean();

        private Grant(VirtualActorCurrentMainDirectory owner, Object session,
                      long issuedAtNanos, long ttlNanos) {
            this.owner = owner;
            this.session = session;
            this.issuedAtNanos = issuedAtNanos;
            this.ttlNanos = ttlNanos;
        }
    }

    private final ActorRuntime.ActorGroup group;
    private final String tenant;
    private final AtomicBoolean closed = new AtomicBoolean();

    public VirtualActorCurrentMainDirectory(ActorRuntime.ActorGroup group, String tenant) {
        this.group = Objects.requireNonNull(group, "group");
        // Enforce identical canonical identity admission as the runtime ledger.
        VirtualActorRuntimeGenerationFence.validate(
                new VirtualActorActivationRegistry.Identity(tenant, "Cart", "validation"));
        this.tenant = tenant;
        group.requireVirtualActorDirectoryHost();
    }

    private void requireHost() {
        group.requireVirtualActorDirectoryHost();
        if (closed.get())
            throw new IllegalStateException("virtual actor directory closed");
    }

    /** Returns immutable address data only; does not create a ledger slot. */
    public VirtualActorActivationRegistry.Identity resolve(String declaration, String key) {
        requireHost();
        var identity = new VirtualActorActivationRegistry.Identity(tenant, declaration, key);
        VirtualActorRuntimeGenerationFence.validate(identity);
        return identity;
    }

    public String address(VirtualActorActivationRegistry.Identity identity) {
        requireHost();
        requireTenant(identity);
        return VirtualActorAddressV1.encode(identity);
    }

    /** The caller must have already authenticated this host session. */
    public Grant issueGrant(Object authenticatedSession, long ttlNanos) {
        requireHost();
        Objects.requireNonNull(authenticatedSession, "authenticatedSession");
        if (ttlNanos <= 0 || ttlNanos > MAX_GRANT_TTL_NANOS)
            throw new IllegalArgumentException("virtual actor inspection grant TTL out of range");
        return new Grant(this, authenticatedSession, System.nanoTime(), ttlNanos);
    }

    public void revoke(Grant grant) {
        requireHost();
        if (grant == null || grant.owner != this)
            throw new SecurityException("foreign virtual actor inspection grant");
        grant.revoked.set(true);
    }

    private void requireGrant(Grant grant, Object session) {
        // Validate authorization FIRST. Do not decode addresses or probe an
        // identity/cursor until the caller proves this exact session/grant.
        if (closed.get() || grant == null || grant.owner != this ||
                grant.session != session || session == null || grant.revoked.get())
            throw new SecurityException("virtual actor inspection denied");
        long elapsed = System.nanoTime() - grant.issuedAtNanos;
        if (elapsed < 0 || elapsed >= grant.ttlNanos)
            throw new SecurityException("virtual actor inspection grant expired");
    }

    private <T> T read(Grant grant, Object session, Supplier<T> operation) {
        requireGrant(grant, session);
        if (!grant.reading.compareAndSet(false, true))
            throw new IllegalStateException("virtual actor inspection grant already in use");
        try {
            requireGrant(grant, session); // Revalidate TTL/revocation after claim.
            requireHost();
            return operation.get();
        } finally {
            grant.reading.set(false);
        }
    }

    private void requireTenant(VirtualActorActivationRegistry.Identity identity) {
        VirtualActorRuntimeGenerationFence.validate(identity);
        if (!tenant.equals(identity.domain()))
            throw new SecurityException("virtual actor identity outside inspected tenant");
    }

    private Snapshot snapshot(VirtualActorRuntimeGenerationFence.DirectoryEntry entry) {
        return new Snapshot(1, entry.identity(), VirtualActorAddressV1.encode(entry.identity()),
                entry.supervisor(), entry.group(), entry.generation(),
                Lifecycle.valueOf(entry.phase().name()), entry.physicalActor(),
                entry.groupClosed(), Instant.now(), true);
    }

    /** Missing/nonowned identity is absent; reads never resolve or activate it. */
    public Optional<Snapshot> inspect(
            Grant grant, Object authenticatedSession,
            VirtualActorActivationRegistry.Identity identity) {
        return read(grant, authenticatedSession, () -> {
            requireTenant(identity);
            return group.inspectVirtualActorDirectory(identity).map(this::snapshot);
        });
    }

    /** Decode only AFTER authenticating the grant; malformed input cannot probe state. */
    public Optional<Snapshot> inspectAddress(
            Grant grant, Object authenticatedSession, String address) {
        return read(grant, authenticatedSession, () -> {
            var identity = VirtualActorAddressV1.decode(address);
            requireTenant(identity);
            return group.inspectVirtualActorDirectory(identity).map(this::snapshot);
        });
    }

    /**
     * Revision-fenced page. Pass 0 for the first page, then reuse the returned
     * revision with nextAfter. Mutations make the previous cursor fail closed.
     * A nextAfter is valid only when a further matching identity exists.
     */
    public Page list(
            Grant grant, Object authenticatedSession,
            int limit, long expectedRevision,
            VirtualActorActivationRegistry.Identity after) {
        return read(grant, authenticatedSession, () -> {
            if (after != null) {
                requireTenant(after);
                if (group.inspectVirtualActorDirectory(after).isEmpty())
                    throw new SecurityException("foreign virtual actor page cursor");
            }
            var page = group.pageVirtualActorDirectory(
                    tenant, limit, expectedRevision, after);
            return new Page(page.entries().stream().map(this::snapshot).toList(),
                    page.revision(), page.nextAfter());
        });
    }

    /** Does not close the ActorGroup, a physical actor, or the generation ledger. */
    @Override public void close() {
        group.requireVirtualActorDirectoryHost();
        closed.set(true);
    }
}
