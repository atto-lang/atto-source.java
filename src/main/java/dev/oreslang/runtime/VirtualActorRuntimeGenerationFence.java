package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime-local, host-only virtual-actor generation authority. The exact
 * physical actor remains owned by ActorRuntime/ActorGroup, not by a guest
 * logical reference. No guest messages, persistence, or CONTROL receipts are
 * executed here; those integrations must consult this same authority.
 *
 * All transitions and the ActorId reverse index share a single monitor.
 * A retirement gate is only released after ActorRuntime confirms physical
 * cleanup AND successful group deregistration. Failed cleanup quarantines the
 * binding instead of admitting a replacement.
 *
 * Generations are runtime-local. A durable persistence provider must issue
 * separate fencing epochs before state restoration/commits can be considered
 * safe across process restarts.
 */
final class VirtualActorRuntimeGenerationFence {
    private final Object monitor = new Object();
    private final int capacity;
    private final Map<VirtualActorActivationRegistry.Identity, Slot> slots = new HashMap<>();
    private final Map<ActorRuntime.ActorId, VirtualActorActivationRegistry.Identity> byActor =
            new HashMap<>();

    private static final class Slot {
        final ActorRuntime.SupervisorId supervisor;
        ActorRuntime.ActorGroupId lastGroup;
        boolean lastGroupClosed;
        long generation;
        long lastBoundGeneration;
        long lastRetiredGeneration;
        ActorRuntime.ActorId lastBoundActor;
        Long pending;
        ActorRuntime.ActorId physical;
        boolean terminalDecisionClaimed;
        boolean terminalDecisionInFlight;

        Slot(ActorRuntime.SupervisorId supervisor) {
            this.supervisor = supervisor;
        }
    }

    VirtualActorRuntimeGenerationFence(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("virtual actor capacity must be positive");
        this.capacity = capacity;
    }

    private static void validatePart(String value, String name, int maxBytes) {
        Objects.requireNonNull(value, name);
        // Bound allocation and Unicode normalization checks before UTF-8 encoding.
        if (value.isEmpty() || value.length() > maxBytes ||
                value.getBytes(StandardCharsets.UTF_8).length > maxBytes ||
                !Normalizer.isNormalized(value, Normalizer.Form.NFC))
            throw new IllegalArgumentException("invalid or noncanonical virtual actor " + name);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch))
                throw new IllegalArgumentException("control character in virtual actor " + name);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
                    throw new IllegalArgumentException("unpaired surrogate in virtual actor " + name);
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("unpaired surrogate in virtual actor " + name);
            }
        }
    }

    static void validate(VirtualActorActivationRegistry.Identity identity) {
        Objects.requireNonNull(identity, "identity");
        validatePart(identity.domain(), "domain", 128);
        validatePart(identity.declaration(), "declaration", 128);
        validatePart(identity.key(), "key", 256);
    }

    /** Mint the next runtime-local generation under the same authority lock. */
    long reserveNext(ActorRuntime.SupervisorId supervisor,
                     ActorRuntime.ActorGroupId group,
                     VirtualActorActivationRegistry.Identity identity) {
        Objects.requireNonNull(supervisor, "supervisor");
        Objects.requireNonNull(group, "group");
        validate(identity);
        synchronized (monitor) {
            Slot slot = slots.get(identity);
            if (slot != null && slot.generation == Long.MAX_VALUE)
                throw new IllegalStateException("virtual actor generation exhausted");
            long next = slot == null ? 1L : slot.generation + 1L;
            // Reentrant monitor: no second reservation can interleave between
            // the watermark read and its monotonic, owner-checked publication.
            reserve(supervisor, group, identity, next);
            return next;
        }
    }

    void reserve(ActorRuntime.SupervisorId supervisor,
                 ActorRuntime.ActorGroupId group,
                 VirtualActorActivationRegistry.Identity identity,
                 long nextGeneration) {
        Objects.requireNonNull(supervisor, "supervisor");
        Objects.requireNonNull(group, "group");
        validate(identity);
        if (nextGeneration <= 0) throw new IllegalArgumentException("nonpositive virtual generation");
        synchronized (monitor) {
            Slot slot = slots.get(identity);
            if (slot == null) {
                if (slots.size() >= capacity)
                    throw new IllegalStateException("virtual actor identity capacity exhausted");
                slot = new Slot(supervisor);
                slots.put(identity, slot);
            }
            if (!slot.supervisor.equals(supervisor))
                throw new SecurityException("virtual actor belongs to another supervisor");
            if (slot.lastGroup != null && !slot.lastGroup.equals(group) &&
                    !slot.lastGroupClosed)
                throw new IllegalStateException(
                        "virtual actor group ownership transfer requires prior group closure");
            if (slot.physical != null)
                throw new IllegalStateException("prior virtual actor has not physically retired");
            if (slot.terminalDecisionInFlight)
                throw new IllegalStateException(
                        "terminal CONTROL decision must settle before replacement");
            if (slot.pending != null)
                throw new IllegalStateException("virtual actor generation already pending");
            if (nextGeneration <= slot.generation)
                throw new IllegalStateException("stale or reused virtual actor generation");
            slot.generation = nextGeneration; // Burn immediately, even if init aborts.
            slot.lastGroup = group;
            slot.lastGroupClosed = false;
            slot.pending = nextGeneration;
        }
    }

    void abort(ActorRuntime.SupervisorId supervisor,
               ActorRuntime.ActorGroupId group,
               VirtualActorActivationRegistry.Identity identity,
               long generation) {
        Objects.requireNonNull(supervisor, "supervisor");
        Objects.requireNonNull(group, "group");
        validate(identity);
        synchronized (monitor) {
            Slot slot = slots.get(identity);
            if (slot == null || !slot.supervisor.equals(supervisor) ||
                    !group.equals(slot.lastGroup) ||
                    !Objects.equals(slot.pending, generation))
                throw new IllegalStateException("virtual actor reservation does not match");
            slot.pending = null; // Generation watermark does not roll back.
        }
    }

    void bind(ActorRuntime.SupervisorId supervisor,
              ActorRuntime.ActorGroupId group,
              VirtualActorActivationRegistry.Identity identity,
              long generation,
              ActorRuntime.ActorId physical) {
        Objects.requireNonNull(supervisor, "supervisor");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(physical, "physical");
        validate(identity);
        synchronized (monitor) {
            Slot slot = slots.get(identity);
            if (slot == null || !slot.supervisor.equals(supervisor) ||
                    !group.equals(slot.lastGroup) ||
                    !Objects.equals(slot.pending, generation) ||
                    slot.physical != null)
                throw new IllegalStateException("virtual actor activation not reserved");
            if (byActor.containsKey(physical))
                throw new IllegalStateException("physical worker already bound to virtual actor");
            byActor.put(physical, identity);
            slot.physical = physical;
            slot.lastBoundGeneration = generation;
            slot.lastBoundActor = physical;
            slot.terminalDecisionClaimed = false;
            slot.pending = null;
        }
    }

    /**
     * Advisory generation watermark query, not terminal policy admission.
     * It can be true while the corresponding worker is still live. CONTROL
     * decisions must claim the exact retired ActorId using the one-shot
     * beginTerminalDecision lease; abort never restores old generations.
     */
    boolean isCurrent(ActorRuntime.SupervisorId supervisor,
                      ActorRuntime.ActorGroupId group,
                      VirtualActorActivationRegistry.Identity identity,
                      long generation) {
        Objects.requireNonNull(supervisor, "supervisor");
        Objects.requireNonNull(group, "group");
        validate(identity);
        synchronized (monitor) {
            Slot slot = slots.get(identity);
            // A reservation or aborted initializer has no physical receipt.
            // Only a generation that actually bound a worker may authorize a
            // terminal policy decision, even after that worker has retired.
            return slot != null && slot.supervisor.equals(supervisor) &&
                    group.equals(slot.lastGroup) && slot.pending == null &&
                    generation > 0 && generation == slot.generation &&
                    generation == slot.lastBoundGeneration;
        }
    }

    /**
     * Non-atomic diagnostic query for an unclaimed exact terminal receipt.
     * A matching logical generation alone cannot authorize another actor's
     * notice. A live or still-cleaning-up physical worker is ineligible.
     * Policy must first claim a TerminalDecisionLease atomically with
     * replacement reservation and retain it through the policy side effect.
     */
    boolean isCurrentTerminal(ActorRuntime.SupervisorId supervisor,
                              ActorRuntime.ActorGroupId group,
                              VirtualActorActivationRegistry.Identity identity,
                              long generation,
                              ActorRuntime.ActorId physical) {
        Objects.requireNonNull(supervisor, "supervisor");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(physical, "physical");
        validate(identity);
        synchronized (monitor) {
            Slot slot = slots.get(identity);
            return slot != null && slot.supervisor.equals(supervisor) &&
                    group.equals(slot.lastGroup) && slot.pending == null &&
                    generation > 0 && generation == slot.generation &&
                    generation == slot.lastBoundGeneration &&
                    generation == slot.lastRetiredGeneration &&
                    physical.equals(slot.lastBoundActor) &&
                    !slot.terminalDecisionClaimed;
        }
    }

    /**
     * One-use terminal CONTROL decision lease. Capturing an old notice is not
     * permission to apply policy after a newer generation has been reserved.
     * Claim publication and replacement reservation are serialized under
     * the same monitor; the trusted supervisor executes its bounded policy
     * outside this monitor, then closes the lease in a finally block.
     *
     * A lost lease quarantines the logical identity rather than incorrectly
     * admitting a replacement. An already claimed notice cannot be replayed.
     */
    final class TerminalDecisionLease implements AutoCloseable {
        private final VirtualActorActivationRegistry.Identity identity;
        private final long generation;
        private final ActorRuntime.ActorId physical;
        private boolean released;

        private TerminalDecisionLease(
                VirtualActorActivationRegistry.Identity identity,
                long generation, ActorRuntime.ActorId physical) {
            this.identity = identity;
            this.generation = generation;
            this.physical = physical;
        }

        @Override public void close() {
            synchronized (monitor) {
                if (released) return;
                Slot slot = slots.get(identity);
                if (slot == null || !slot.terminalDecisionInFlight ||
                        !slot.terminalDecisionClaimed ||
                        slot.lastBoundGeneration != generation ||
                        !physical.equals(slot.lastBoundActor))
                    throw new IllegalStateException("terminal decision lease authority changed");
                slot.terminalDecisionInFlight = false;
                released = true;
            }
        }
    }

    TerminalDecisionLease beginTerminalDecision(
            ActorRuntime.SupervisorId supervisor,
            ActorRuntime.ActorGroupId group,
            VirtualActorActivationRegistry.Identity identity,
            long generation, ActorRuntime.ActorId physical) {
        Objects.requireNonNull(supervisor, "supervisor");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(physical, "physical");
        validate(identity);
        synchronized (monitor) {
            Slot slot = slots.get(identity);
            if (slot == null || !slot.supervisor.equals(supervisor) ||
                    !group.equals(slot.lastGroup) || slot.lastGroupClosed ||
                    slot.pending != null || slot.terminalDecisionClaimed ||
                    slot.terminalDecisionInFlight || generation <= 0 ||
                    generation != slot.generation ||
                    generation != slot.lastBoundGeneration ||
                    generation != slot.lastRetiredGeneration ||
                    !physical.equals(slot.lastBoundActor))
                throw new IllegalStateException("stale or claimed virtual terminal decision");
            // Allocate the lease before mutating authority: allocation failure
            // cannot leave a claimed in-flight decision with no close handle.
            TerminalDecisionLease lease = new TerminalDecisionLease(identity, generation, physical);
            slot.terminalDecisionClaimed = true;
            slot.terminalDecisionInFlight = true;
            return lease;
        }
    }

    boolean bound(ActorRuntime.ActorId physical) {
        Objects.requireNonNull(physical, "physical");
        synchronized (monitor) {
            return byActor.containsKey(physical);
        }
    }

    /** Invoked only after successful physical and ActorGroup retirement. */
    void retire(ActorRuntime.ActorId physical) {
        Objects.requireNonNull(physical, "physical");
        synchronized (monitor) {
            var identity = byActor.get(physical);
            if (identity == null) return; // Ordinary nonvirtual worker.
            Slot slot = slots.get(identity);
            if (slot == null || !physical.equals(slot.physical))
                throw new IllegalStateException("virtual actor retirement authority mismatch");
            byActor.remove(physical);
            slot.physical = null;
            // Only the exact runtime finalization path may mark the physical
            // generation retired, after the ActorGroup and ActorCell cleanup.
            slot.lastRetiredGeneration = slot.lastBoundGeneration;
        }
    }

    /**
     * Close fences a group as a logical activation owner. Burn its pending
     * generations, preserve prior receipts and allow the SAME supervisor
     * to rehome only after physical cleanup has also completed.
     */
    void abortPendingForGroup(ActorRuntime.ActorGroupId group) {
        Objects.requireNonNull(group, "group");
        synchronized (monitor) {
            for (Slot slot : slots.values()) {
                if (group.equals(slot.lastGroup)) {
                    slot.lastGroupClosed = true;
                    if (slot.pending != null) slot.pending = null;
                }
            }
        }
    }
}
