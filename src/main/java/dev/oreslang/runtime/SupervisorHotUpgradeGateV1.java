package dev.oreslang.runtime;

import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Host-only CONTROL authority handover for one stable supervisor identity.
 *
 * This is NOT a guest-visible hot-reload API or an ActorRuntime integration.
 * Its trusted coordinator seal must be owned by an independently authenticated
 * parent (or bootstrap/root coordinator), never obtained from guest code.
 * CONTROL reservations must be acquired on command admission, not dequeue;
 * child DATA turns may continue because they do not borrow supervisor CONTROL.
 * State migration, physical code-image retirement and dispatch publication
 * must be handled by a higher-level coordinator in one verified transaction.
 */
final class SupervisorHotUpgradeGateV1 implements AutoCloseable {
    private static final int MAX_CHILDREN = 4096;
    private static final int MAX_CONTROL_RESERVATIONS = 65536;

    record Image(String codeSha256, String policyAbiSha256, String controlAbiSha256,
                 String capabilitySha256) {
        Image {
            for (String digest : new String[] {
                    codeSha256, policyAbiSha256, controlAbiSha256, capabilitySha256 }) {
                if (digest == null || !digest.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("canonical lowercase SHA-256 required");
                }
            }
        }

        boolean compatible(Image other) {
            return policyAbiSha256.equals(other.policyAbiSha256)
                    && controlAbiSha256.equals(other.controlAbiSha256)
                    && capabilitySha256.equals(other.capabilitySha256);
        }
    }

    record Snapshot(UUID supervisorId, UUID parentSupervisorId, long epoch,
                    Image image, Set<UUID> children, int controlReservations,
                    boolean frozen, boolean closed) { }

    /** One shared registry per actor runtime, never one registry per supervisor. */
    static final class ChildRegistry {
        private final Map<UUID, UUID> parentOf = new HashMap<>();

        private synchronized void claim(UUID childId, UUID supervisorId) {
            Objects.requireNonNull(childId, "childId");
            Objects.requireNonNull(supervisorId, "supervisorId");
            if (parentOf.containsKey(childId)) {
                throw new IllegalStateException("actor already has a supervisor");
            }
            // Reject indirect supervision cycles, not only direct self-parenting.
            // The single registry monitor serializes cycle detection with claims.
            UUID ancestor = supervisorId;
            Set<UUID> visited = new java.util.HashSet<>();
            while (ancestor != null) {
                if (childId.equals(ancestor) || !visited.add(ancestor)) {
                    throw new IllegalStateException("supervisor hierarchy cycle");
                }
                ancestor = parentOf.get(ancestor);
            }
            parentOf.put(childId, supervisorId);
        }

        private synchronized void release(UUID childId, UUID supervisorId) {
            if (!parentOf.remove(childId, supervisorId)) {
                throw new IllegalStateException("stale or foreign child ownership");
            }
        }

        synchronized UUID parentOf(UUID childId) { return parentOf.get(childId); }
    }

    static final class Candidate {
        private final SupervisorHotUpgradeGateV1 owner;
        private final long epoch;
        private final Image next;
        private final Set<UUID> children;

        private Candidate(SupervisorHotUpgradeGateV1 owner, long epoch, Image next,
                          Set<UUID> children) {
            this.owner = owner;
            this.epoch = epoch;
            this.next = next;
            this.children = children;
        }
    }

    final class ControlLease implements AutoCloseable {
        private final long originEpoch;
        private boolean released;

        private ControlLease(long originEpoch) { this.originEpoch = originEpoch; }
        long originEpoch() { return originEpoch; }
        @Override public void close() { release(this); }
    }

    private final UUID supervisorId;
    private final UUID parentSupervisorId;
    private final Object trustedCoordinatorSeal;
    private final ChildRegistry childRegistry;
    private final LinkedHashSet<UUID> children = new LinkedHashSet<>();
    private long epoch = 1;
    private Image image;
    private int reservations;
    private Candidate candidate;
    private boolean closed;

    /** Root supervisors require a distinct, externally controlled bootstrap seal. */
    SupervisorHotUpgradeGateV1(UUID supervisorId, UUID parentSupervisorId,
                               Object trustedCoordinatorSeal, ChildRegistry childRegistry,
                               Image image) {
        this.supervisorId = Objects.requireNonNull(supervisorId, "supervisorId");
        if (supervisorId.equals(parentSupervisorId)) {
            throw new IllegalArgumentException("supervisor cannot parent itself");
        }
        this.parentSupervisorId = parentSupervisorId;
        this.trustedCoordinatorSeal = Objects.requireNonNull(
                trustedCoordinatorSeal, "trustedCoordinatorSeal");
        this.childRegistry = Objects.requireNonNull(childRegistry, "childRegistry");
        this.image = Objects.requireNonNull(image, "image");
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(supervisorId, parentSupervisorId, epoch, image,
                Set.copyOf(children), reservations, candidate != null, closed);
    }

    synchronized void attachChild(UUID childId) {
        requireMutable();
        Objects.requireNonNull(childId, "childId");
        if (supervisorId.equals(childId)) {
            throw new IllegalArgumentException("supervisor cannot supervise itself");
        }
        if (children.contains(childId)) {
            throw new IllegalStateException("child already attached");
        }
        if (children.size() >= MAX_CHILDREN) {
            throw new IllegalStateException("supervisor child limit exceeded");
        }
        childRegistry.claim(childId, supervisorId);
        try {
            if (!children.add(childId)) {
                throw new IllegalStateException("child set disagrees with registry");
            }
        } catch (RuntimeException | Error failure) {
            // Exception-atomic attachment: no registry claim survives a failed
            // local admission. If rollback fails, preserve the error trail.
            try {
                childRegistry.release(childId, supervisorId);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    synchronized void detachChild(UUID childId) {
        requireMutable();
        if (!children.contains(Objects.requireNonNull(childId, "childId"))) {
            throw new IllegalStateException("child not attached");
        }
        // A real runtime must also fence that child's pending CONTROL work.
        // Do not silently detach a child while any CONTROL reservation exists.
        if (reservations != 0) {
            throw new IllegalStateException("CONTROL reservations prevent detach");
        }
        childRegistry.release(childId, supervisorId);
        children.remove(childId);
    }

    /** Reserve a child CONTROL command from its admission until final ACK/abort. */
    synchronized ControlLease reserveControl(UUID childId) {
        requireMutable();
        if (!children.contains(Objects.requireNonNull(childId, "childId"))) {
            throw new IllegalStateException("unregistered supervisor child");
        }
        if (reservations == MAX_CONTROL_RESERVATIONS) {
            throw new IllegalStateException("supervisor CONTROL reservation limit exceeded");
        }
        ControlLease lease = new ControlLease(epoch); // allocate before charging
        ++reservations;
        return lease;
    }

    /** The coordinator seal is an opaque identity, never a digest or caller UUID. */
    synchronized Candidate prepare(Object coordinatorSeal, long expectedEpoch, Image proposed) {
        requireMutable();
        if (coordinatorSeal != trustedCoordinatorSeal) {
            throw new SecurityException("untrusted supervisor upgrade coordinator");
        }
        if (expectedEpoch != epoch) {
            throw new IllegalStateException("stale supervisor generation");
        }
        Objects.requireNonNull(proposed, "proposed");
        if (!image.compatible(proposed)) {
            throw new SecurityException("supervisor policy, CONTROL ABI or capability drift requires migration");
        }
        if (image.codeSha256().equals(proposed.codeSha256())) {
            throw new IllegalArgumentException("identical code image");
        }
        candidate = new Candidate(this, epoch, proposed, Set.copyOf(children));
        return candidate;
    }

    /** Publication is a gate transition only, NOT live ActorRuntime route switching. */
    synchronized boolean publish(Object coordinatorSeal, Candidate prepared) {
        verifyCandidate(coordinatorSeal, prepared);
        if (reservations != 0) return false;
        if (!children.equals(prepared.children)) {
            throw new IllegalStateException("supervisor child membership changed");
        }
        if (epoch == Long.MAX_VALUE) {
            throw new IllegalStateException("supervisor epoch exhausted");
        }
        image = prepared.next;
        ++epoch;
        candidate = null;
        return true;
    }

    synchronized void abort(Object coordinatorSeal, Candidate prepared) {
        verifyCandidate(coordinatorSeal, prepared);
        candidate = null;
    }

    // A closed supervisor still owns its children until a separate trusted STOP/
    // reparent transaction releases those claims. Do NOT report retirement early.
    synchronized boolean retired() { return closed && reservations == 0 && children.isEmpty(); }

    private void requireMutable() {
        if (closed || candidate != null) {
            throw new IllegalStateException("supervisor closed or upgrade frozen");
        }
    }

    private void verifyCandidate(Object coordinatorSeal, Candidate prepared) {
        if (coordinatorSeal != trustedCoordinatorSeal) {
            throw new SecurityException("untrusted supervisor upgrade coordinator");
        }
        if (closed || prepared == null || prepared.owner != this
                || prepared != candidate || prepared.epoch != epoch) {
            throw new IllegalStateException("stale, foreign or retired supervisor candidate");
        }
    }

    private synchronized void release(ControlLease lease) {
        if (lease.released) return;
        if (reservations <= 0) {
            throw new IllegalStateException("supervisor CONTROL reservation underflow");
        }
        --reservations;
        lease.released = true;
    }

    @Override public synchronized void close() {
        closed = true;
        candidate = null;
        // Retain outstanding leases and child registry ownership until the
        // runtime's independently verified STOP/reparent transaction runs.
    }
}
