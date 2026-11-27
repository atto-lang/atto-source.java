package dev.oreslang.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only inspection facade over the local activation registry.
 *
 * This is a VM-local metadata primitive only. It is not wired to ActorRuntime
 * supervisor CONTROL, does not authenticate tenants, and cannot inspect guest
 * state, actor mailboxes or remote placements.
 */
public final class VirtualActorDirectoryInspection {
    public enum Lifecycle {
        NEVER_ACTIVATED, ACTIVATING, ACTIVE, PASSIVATED, CLOSED
    }

    public record Snapshot(int schemaVersion,
                           VirtualActorActivationRegistry.Identity identity,
                           String address,
                           Lifecycle lifecycle,
                           long activationGeneration,
                           int outstandingAdmissions,
                           Instant observedAt,
                           boolean partial) {
        public Snapshot {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(observedAt, "observedAt");
            if (schemaVersion != 1 || activationGeneration < 0 || outstandingAdmissions < 0)
                throw new IllegalArgumentException("invalid virtual actor inspection snapshot");
        }
    }

    private final VirtualActorActivationRegistry registry;

    public VirtualActorDirectoryInspection(VirtualActorActivationRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Inspects an existing logical identity without resolving/creating it. */
    public Optional<Snapshot> inspect(VirtualActorActivationRegistry.Identity identity) {
        return registry.inspectIdentity(identity).map(this::snapshot);
    }

    /** Decodes and inspects a canonical address without activation side effects. */
    public Optional<Snapshot> inspectAddress(String address) {
        return inspect(VirtualActorAddressV1.decode(address));
    }

    /** Inspects an already-issued local reference without activating it. */
    public Snapshot inspect(VirtualActorActivationRegistry.LogicalRef reference) {
        return snapshot(registry.inspect(reference));
    }

    private Snapshot snapshot(VirtualActorActivationRegistry.Inspection value) {
        Lifecycle lifecycle;
        if (value.registryClosed()) lifecycle = Lifecycle.CLOSED;
        else if (value.published()) lifecycle = Lifecycle.ACTIVE;
        else if (value.activationPresent()) lifecycle = Lifecycle.ACTIVATING;
        else if (value.latestIncarnation() == 0) lifecycle = Lifecycle.NEVER_ACTIVATED;
        else lifecycle = Lifecycle.PASSIVATED;
        return new Snapshot(1, value.identity(), VirtualActorAddressV1.encode(value.identity()),
                lifecycle, value.latestIncarnation(), value.outstanding(),
                Instant.now(), true);
    }
}
