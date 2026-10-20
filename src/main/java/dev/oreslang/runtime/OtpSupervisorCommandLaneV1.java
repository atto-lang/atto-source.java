package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * Host-only, bounded delivery for nonterminal supervisor CONTROL commands.
 *
 * This is deliberately NOT the child-failure CONTROL lane. Terminal notices
 * must retain the pre-reserved, non-dropping ActorSupervisorControlLane slots.
 * Ordinary actor DATA never flows through this queue. An ActorRuntime adapter
 * must assert supervisor authorization and execute command handlers only on
 * the owning serialized actor turn. No actor code executes under this monitor.
 *
 * A command acknowledgement confirms delivery/handling of a CONTROL notice,
 * NOT that a checkpoint, state migration or route publication has succeeded.
 * Those belong to a separately authenticated atomic upgrade coordinator.
 */
final class OtpSupervisorCommandLaneV1 {
    record Target(UUID runtimeId, UUID actorId, long incarnation) {
        Target {
            Objects.requireNonNull(runtimeId, "runtimeId");
            Objects.requireNonNull(actorId, "actorId");
            if (incarnation <= 0) throw new IllegalArgumentException("positive incarnation required");
        }
    }

    enum Kind { PREPARE_UPGRADE, QUIESCE_CHECK, ABORT_UPGRADE, COMMIT_NOTICE, GRACEFUL_STOP }

    record Command(long sequence, Target target, long routeEpoch, Kind kind,
                   String candidateDigest) {
        Command {
            if (sequence <= 0 || routeEpoch <= 0) throw new IllegalArgumentException("positive sequence/epoch required");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(kind, "kind");
            if (kind == Kind.GRACEFUL_STOP) {
                if (candidateDigest != null) throw new IllegalArgumentException("STOP has no code digest");
            } else if (candidateDigest == null || !candidateDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("canonical lowercase code SHA-256 required");
            }
        }
    }

    record Snapshot(int capacity, int outstanding, int queued, int inFlight,
                    long sent, long acknowledged, long retried, long aborted,
                    long admissionRejected, long duplicateTargetRejected,
                    long quarantined) { }

    private static final class Slot {
        final Command command;
        boolean queued;
        Lease activeLease;
        Slot(Command command) { this.command = command; }
    }

    /** Opaque single-use runtime settlement authority, never guest DATA. */
    final class Lease implements AutoCloseable {
        private final Slot slot;
        private boolean settled;
        private Lease(Slot slot) { this.slot = slot; }
        Command command() { return slot.command; }
        void ack() { settle(this, true); }
        void retry() { settle(this, false); }
        @Override public void close() {
            synchronized (OtpSupervisorCommandLaneV1.this) {
                if (!settled && slot.activeLease == this) settle(this, false);
            }
        }
    }

    private final int capacity;
    private final ArrayDeque<Slot> queue = new ArrayDeque<>();
    private final Map<Target, Slot> outstanding = new HashMap<>();
    private Slot inFlight;
    private long nextSequence = 1;
    private long sent;
    private long acknowledged;
    private long retried;
    private long aborted;
    private long admissionRejected;
    private long duplicateTargetRejected;
    private long quarantined;
    private boolean sealed;

    // Audit counters are observations, never authority. Saturate instead
    // of silently wrapping into negative values after long service lifetimes.
    private static long incrementMetric(long current) {
        return current == Long.MAX_VALUE ? current : current + 1L;
    }
    private static long addMetric(long current, int increment) {
        if (increment < 0) throw new IllegalArgumentException("negative metric");
        return current > Long.MAX_VALUE - increment
                ? Long.MAX_VALUE : current + increment;
    }

    OtpSupervisorCommandLaneV1(int capacity) {
        if (capacity <= 0 || capacity > 1_000_000)
            throw new IllegalArgumentException("CONTROL command capacity must be 1..1000000");
        this.capacity = capacity;
    }

    /**
     * Nonfatal CONTROL can reject admission: never borrow failure reservations.
     * Authoritative runtime callers must validate supervision rights before use.
     * One unsettled command per exact actor incarnation prevents reordering
     * PREPARE/COMMIT/ABORT and avoids unbounded per-target command backlogs.
     */
    synchronized Command submit(Target target, long routeEpoch, Kind kind, String candidateDigest) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(kind, "kind");
        if (sealed) throw new IllegalStateException("actor CONTROL command lane terminated");
        if (outstanding.containsKey(target)) {
            duplicateTargetRejected = incrementMetric(duplicateTargetRejected);
            throw new IllegalStateException("CONTROL command already outstanding for target incarnation");
        }
        if (outstanding.size() >= capacity) {
            admissionRejected = incrementMetric(admissionRejected);
            throw new RejectedExecutionException("supervisor command capacity exhausted");
        }
        // Check allocation/serialization before consuming a sequence or slot.
        Command command = new Command(nextSequence, target, routeEpoch, kind, candidateDigest);
        if (nextSequence == Long.MAX_VALUE) throw new IllegalStateException("CONTROL sequence exhausted");
        Slot slot = new Slot(command);
        outstanding.put(target, slot);
        try {
            queue.addLast(slot);
        } catch (RuntimeException | Error failure) {
            outstanding.remove(target, slot);
            throw failure;
        }
        slot.queued = true;
        nextSequence++;
        sent = incrementMetric(sent);
        return command;
    }

    synchronized Optional<Lease> poll() {
        if (inFlight != null) throw new IllegalStateException("single CONTROL command delivery lease required");
        Slot slot = queue.peekFirst();
        if (slot == null) return Optional.empty();
        if (!slot.queued || outstanding.get(slot.command.target()) != slot)
            throw new IllegalStateException("CONTROL queue/index corruption");
        // Preallocate the lease BEFORE removing the queued event. An OOME
        // cannot silently lose an acknowledged-or-retryable CONTROL command.
        Lease lease = new Lease(slot);
        if (queue.removeFirst() != slot)
            throw new IllegalStateException("CONTROL queue head unexpectedly changed");
        slot.queued = false;
        slot.activeLease = lease;
        inFlight = slot;
        return Optional.of(lease);
    }

    private synchronized void settle(Lease lease, boolean ack) {
        Objects.requireNonNull(lease, "lease");
        Slot slot = lease.slot;
        if (lease.settled || slot.activeLease != lease || inFlight != slot
                || outstanding.get(slot.command.target()) != slot)
            throw new IllegalStateException("stale or revoked CONTROL command lease");
        if (ack) {
            if (!outstanding.remove(slot.command.target(), slot))
                throw new IllegalStateException("CONTROL missing target index");
            acknowledged = incrementMetric(acknowledged);
        } else {
            // Retry does not consume a new credit or mint a new command ID.
            // Requeue at the back: a failing upgrade of actor A must not
            // indefinitely block a healthy actor B's supervisor command.
            // One outstanding per target preserves each actor's own order.
            queue.addLast(slot);
            slot.queued = true;
            retried = incrementMetric(retried);
        }
        lease.settled = true;
        slot.activeLease = null;
        inFlight = null;
    }

    /**
     * Called ONLY after the trusted upgrade coordinator has separately
     * rolled back/quarantined this incarnation. This is not a silent drop:
     * caller obtains exact command identity for audit + terminal response.
     */
    synchronized Optional<Command> abortAfterRollback(Target target, long expectedRouteEpoch) {
        Objects.requireNonNull(target, "target");
        Slot slot = outstanding.get(target);
        if (slot == null) return Optional.empty();
        if (slot.command.routeEpoch() != expectedRouteEpoch)
            throw new IllegalStateException("stale supervisor upgrade abort epoch");
        if (slot.queued && !queue.remove(slot))
            throw new IllegalStateException("CONTROL queued command missing");
        if (inFlight == slot) {
            slot.activeLease.settled = true;
            slot.activeLease = null;
            inFlight = null;
        }
        if (!outstanding.remove(target, slot))
            throw new IllegalStateException("CONTROL target missing on abort");
        slot.queued = false;
        aborted = incrementMetric(aborted);
        return Optional.of(slot.command);
    }

    /**
     * Physical actor termination is NOT an upgrade commit/rollback.
     * Revoke pending/in-flight command leases and account them as quarantined,
     * never ACK. The trusted host must separately settle rollback/cleanup.
     * The fatal actor failure still follows its independently reserved lane.
     */
    synchronized int quarantineOnTermination() {
        if (sealed) return 0;
        sealed = true;
        int count = outstanding.size();
        for (var slot : outstanding.values()) {
            if (slot.activeLease != null) {
                slot.activeLease.settled = true;
                slot.activeLease = null;
            }
            slot.queued = false;
        }
        queue.clear();
        outstanding.clear();
        inFlight = null;
        quarantined = addMetric(quarantined, count);
        return count;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(capacity, outstanding.size(), queue.size(), inFlight == null ? 0 : 1,
                sent, acknowledged, retried, aborted, admissionRejected,
                duplicateTargetRejected, quarantined);
    }
}
