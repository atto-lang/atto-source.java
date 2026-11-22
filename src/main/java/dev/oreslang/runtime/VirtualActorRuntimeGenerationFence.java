package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.ArrayList;

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
    private static final Comparator<VirtualActorActivationRegistry.Identity> ID_ORDER =
            Comparator.comparing(VirtualActorActivationRegistry.Identity::domain)
                    .thenComparing(VirtualActorActivationRegistry.Identity::declaration)
                    .thenComparing(VirtualActorActivationRegistry.Identity::key);
    private final NavigableMap<VirtualActorActivationRegistry.Identity, Slot> slots =
            new TreeMap<>(ID_ORDER);
    private final Map<ActorRuntime.ActorId, VirtualActorActivationRegistry.Identity> byActor =
            new HashMap<>();
    // Metadata revision, NOT another activation generation authority.
    private long directoryRevision = 1L;

    /** Conservative host inspection: PHYSICAL_OWNERSHIP_HELD includes STOP in progress. */
    enum DirectoryPhase {
        ACTIVATING, PHYSICAL_OWNERSHIP_HELD, TERMINAL_PENDING,
        QUARANTINED, PASSIVATED, ABORTED
    }

    record DirectoryEntry(
            VirtualActorActivationRegistry.Identity identity,
            ActorRuntime.SupervisorId supervisor,
            ActorRuntime.ActorGroupId group,
            long generation,
            DirectoryPhase phase,
            Optional<ActorRuntime.ActorId> physicalActor,
            boolean groupClosed) {
        DirectoryEntry {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(supervisor, "supervisor");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(phase, "phase");
            physicalActor = Objects.requireNonNull(physicalActor, "physicalActor");
            if (generation <= 0)
                throw new IllegalArgumentException("invalid directory generation");
        }
    }

    record DirectoryPage(List<DirectoryEntry> entries, long revision,
                         Optional<VirtualActorActivationRegistry.Identity> nextAfter) {
        DirectoryPage {
            entries = List.copyOf(entries);
            nextAfter = Objects.requireNonNull(nextAfter, "nextAfter");
            if (revision <= 0) throw new IllegalArgumentException("invalid directory revision");
        }
    }

    static final class StaleDirectoryPageException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        StaleDirectoryPageException() { super("virtual actor directory page is stale"); }
    }

    private void advanceDirectoryRevision() {
        // Even at saturation, activation/retirement must not partially fail
        // AFTER mutating the authoritative slot. Pagination instead fails
        // closed forever; never wrap revision and accept a stale cursor.
        if (directoryRevision != Long.MAX_VALUE) directoryRevision++;
    }

    private static DirectoryEntry directoryEntry(
            VirtualActorActivationRegistry.Identity identity, Slot slot) {
        DirectoryPhase phase = slot.terminalControlFailed || slot.physicalCleanupFailed
                ? DirectoryPhase.QUARANTINED
                : slot.terminalControlPending || slot.terminalDecisionInFlight
                    ? DirectoryPhase.TERMINAL_PENDING
                    : slot.physical != null
                        ? DirectoryPhase.PHYSICAL_OWNERSHIP_HELD
                        : slot.pending != null
                            ? DirectoryPhase.ACTIVATING
                            : slot.lastRetiredGeneration > 0 &&
                              slot.lastRetiredGeneration == slot.generation
                                ? DirectoryPhase.PASSIVATED
                                : DirectoryPhase.ABORTED;
        return new DirectoryEntry(identity, slot.supervisor, slot.lastGroup,
                slot.generation, phase, Optional.ofNullable(slot.physical),
                slot.lastGroupClosed);
    }

    /** Pure observation: does not resolve a ref, allocate a slot, or activate. */
    Optional<DirectoryEntry> inspectDirectory(
            ActorRuntime.SupervisorId supervisor,
            VirtualActorActivationRegistry.Identity identity) {
        Objects.requireNonNull(supervisor, "supervisor");
        validate(identity);
        synchronized (monitor) {
            Slot slot = slots.get(identity);
            return slot == null || !slot.supervisor.equals(supervisor)
                    ? Optional.empty() : Optional.of(directoryEntry(identity, slot));
        }
    }

    /**
     * Bounded, supervisor-and-tenant-scoped page. The revision changes on
     * every lifecycle mutation; a cursor pinned to an old revision fails closed.
     * No separate activation counters, mirror maps or guest state are read.
     */
    DirectoryPage listDirectory(
            ActorRuntime.SupervisorId supervisor, String domain, int limit,
            long expectedRevision, VirtualActorActivationRegistry.Identity after) {
        Objects.requireNonNull(supervisor, "supervisor");
        validatePart(domain, "domain", 128);
        if (limit < 1 || limit > 64 || expectedRevision < 0)
            throw new IllegalArgumentException("invalid virtual actor directory page request");
        if (after != null) {
            if (expectedRevision == 0)
                throw new IllegalArgumentException(
                        "directory page continuation requires pinned revision");
            validate(after);
            if (!domain.equals(after.domain()))
                throw new SecurityException("directory page cursor outside tenant");
        }
        synchronized (monitor) {
            if (directoryRevision == Long.MAX_VALUE ||
                    (expectedRevision != 0 && expectedRevision != directoryRevision))
                throw new StaleDirectoryPageException();
            var rows = after == null ? slots.entrySet() : slots.tailMap(after, false).entrySet();
            List<DirectoryEntry> entries = new ArrayList<>(limit);
            boolean more = false;
            for (var row : rows) {
                if (!domain.equals(row.getKey().domain())
                        || !supervisor.equals(row.getValue().supervisor)) continue;
                if (entries.size() == limit) { more = true; break; }
                entries.add(directoryEntry(row.getKey(), row.getValue()));
            }
            return new DirectoryPage(entries, directoryRevision,
                    more ? Optional.of(entries.getLast().identity()) : Optional.empty());
        }
    }

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
        boolean terminalControlPending;
        boolean terminalControlFailed;
        boolean physicalCleanupFailed;

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
        if (value.isBlank() || value.length() > maxBytes ||
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
            if (slot.physical != null || slot.physicalCleanupFailed)
                throw new IllegalStateException("prior virtual actor has not physically retired");
            if (slot.terminalDecisionInFlight || slot.terminalControlPending
                    || slot.terminalControlFailed)
                throw new IllegalStateException(
                        "virtual terminal CONTROL has not safely settled");
            if (slot.pending != null)
                throw new IllegalStateException("virtual actor generation already pending");
            if (nextGeneration <= slot.generation)
                throw new IllegalStateException("stale or reused virtual actor generation");
            slot.generation = nextGeneration; // Burn immediately, even if init aborts.
            slot.lastGroup = group;
            slot.lastGroupClosed = false;
            slot.pending = nextGeneration;
            advanceDirectoryRevision();
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
            advanceDirectoryRevision();
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
            slot.terminalControlPending = false;
            slot.terminalControlFailed = false;
            slot.physicalCleanupFailed = false;
            slot.pending = null;
            advanceDirectoryRevision();
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

        /** A failed policy decision cannot authorize a replacement. */
        void quarantine() {
            synchronized (monitor) {
                if (released)
                    throw new IllegalStateException("terminal decision already settled");
                Slot slot = slots.get(identity);
                if (slot == null || !slot.terminalDecisionInFlight ||
                        slot.lastBoundGeneration != generation ||
                        !physical.equals(slot.lastBoundActor))
                    throw new IllegalStateException("terminal decision lease authority changed");
                slot.terminalControlFailed = true;
                advanceDirectoryRevision();
            }
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
                // Mark the automatic CONTROL receipt delivered only if its
                // asynchronous policy completed without any quarantine.
                if (!slot.terminalControlFailed)
                    slot.terminalControlPending = false;
                slot.terminalDecisionInFlight = false;
                released = true;
                advanceDirectoryRevision();
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
            advanceDirectoryRevision();
            return lease;
        }
    }

    boolean bound(ActorRuntime.ActorId physical) {
        Objects.requireNonNull(physical, "physical");
        synchronized (monitor) {
            return byActor.containsKey(physical);
        }
    }

    /**
     * Invoked only after successful physical and ActorGroup retirement.
     * An opted-in failure receipt is marked pending atomically with physical
     * release, so no successor can overtake CONTROL scheduling.
     */
    void retire(ActorRuntime.ActorId physical, boolean requiresTerminalControl) {
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
            if (requiresTerminalControl) slot.terminalControlPending = true;
            advanceDirectoryRevision();
        }
    }

    /**
     * Rejected CONTROL scheduling, lost delivery or failed asynchronous
     * decision permanently quarantines this runtime-local generation.
     * A stale receipt must not poison a newer identity.
     */
    void failTerminalDelivery(
            ActorRuntime.SupervisorId supervisor,
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
            if (slot == null || !slot.supervisor.equals(supervisor)
                    || !group.equals(slot.lastGroup)
                    || generation != slot.generation
                    || generation != slot.lastBoundGeneration
                    || !physical.equals(slot.lastBoundActor))
                return; // A superseded receipt must not fence a new worker.
            slot.terminalControlFailed = true;
            slot.terminalControlPending = true;
            advanceDirectoryRevision();
        }
    }

    /**
     * A failed host finalization retains the exact physical worker binding.
     * Inspection must report QUARANTINED, never active or passivated, and
     * the original identity remains unavailable for a replacement.
     */
    void quarantinePhysical(ActorRuntime.ActorId physical) {
        Objects.requireNonNull(physical, "physical");
        synchronized (monitor) {
            var identity = byActor.get(physical);
            if (identity == null) return; // Ordinary nonvirtual worker.
            Slot slot = slots.get(identity);
            if (slot == null || !physical.equals(slot.physical))
                throw new IllegalStateException("virtual actor cleanup authority mismatch");
            if (!slot.physicalCleanupFailed) {
                slot.physicalCleanupFailed = true;
                advanceDirectoryRevision();
            }
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
            boolean changed = false;
            for (Slot slot : slots.values()) {
                if (group.equals(slot.lastGroup) &&
                        (!slot.lastGroupClosed || slot.pending != null)) {
                    slot.lastGroupClosed = true;
                    if (slot.pending != null) slot.pending = null;
                    changed = true;
                }
            }
            if (changed) advanceDirectoryRevision();
        }
    }
}
