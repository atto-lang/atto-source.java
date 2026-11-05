package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded, host-owned handoff for terminal actor-failure diagnostics.
 *
 * Admission is NOT execution of a supervisor handler, CONTROL delivery, or
 * acknowledgement. A VM CONTROL dispatcher and checked source-level policy
 * must be connected separately. This substrate never invokes guest code.
 *
 * Entries contain only immutable scalar/UUID diagnostic data. Quotas cover
 * BOTH queued and claimed-but-unacknowledged receipts; the latter cannot
 * silently free capacity. No raw Throwable, actor state, callback or arena
 * lease is stored here.
 */
public final class SupervisorFailureInbox {
    public static final int MAX_CAPACITY = 256;

    /** Public immutable diagnostic view, not a supervisor authority token. */
    public static final class Receipt {
        private final long sequence;
        private final long claimAttempt;
        private final String supervisorIdentity;
        private final String actorIdentity;
        private final long actorGeneration;
        private final ActorFailureTrace.Snapshot trace;

        private Receipt(long sequence, long claimAttempt, String supervisorIdentity,
                String actorIdentity, long actorGeneration,
                ActorFailureTrace.Snapshot trace) {
            this.sequence = sequence;
            this.claimAttempt = claimAttempt;
            this.supervisorIdentity = supervisorIdentity;
            this.actorIdentity = actorIdentity;
            this.actorGeneration = actorGeneration;
            this.trace = trace;
        }

        public long sequence() { return sequence; }
        /** Incremented for every host claim; old retry attempts cannot ack. */
        public long claimAttempt() { return claimAttempt; }
        private Receipt nextClaimAttempt() {
            return new Receipt(sequence, Math.addExact(claimAttempt, 1),
                    supervisorIdentity, actorIdentity, actorGeneration, trace);
        }
        public String supervisorIdentity() { return supervisorIdentity; }
        public String actorIdentity() { return actorIdentity; }
        public long actorGeneration() { return actorGeneration; }
        public ActorFailureTrace.FailureKind failureKind() {
            return trace.failureKind();
        }
        public ActorFailureTrace.Snapshot trace() { return trace; }
        /** Host queue claim is not an executed source supervisor handler. */
        public boolean supervisorControlDelivered() { return false; }
    }

    private record Entry(Receipt receipt, boolean claimed) { }

    private final String supervisorIdentity;
    private final int capacity;
    private final Map<Long, Entry> active = new HashMap<>();
    private final ArrayDeque<Long> queued = new ArrayDeque<>();
    private long nextSequence = 1;

    SupervisorFailureInbox(UUID supervisorId, int capacity) {
        supervisorIdentity = Objects.requireNonNull(
                supervisorId, "supervisorId").toString();
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException(
                    "supervisor failure inbox capacity must be 1.." + MAX_CAPACITY);
        }
        this.capacity = capacity;
    }

    public int capacity() { return capacity; }

    /**
     * Trusted runtime-only admission. Check the terminal actor incarnation and
     * the FIRST supervisor association, not a later ancestor. The caller is
     * responsible for establishing group ownership at terminal admission.
     *
     * A full inbox returns false; callers MUST quarantine/retry rather than
     * pretend a terminal CONTROL receipt was delivered or discard the failure.
     */
    synchronized boolean offerTrusted(ActorFailureTrace.Snapshot trace) {
        Objects.requireNonNull(trace, "trace");
        List<ActorFailureTrace.Boundary> path = trace.boundaries();
        if (!trace.supervisorAssociationKnown() || path.isEmpty()) return false;
        ActorFailureTrace.Boundary terminal = path.get(0);
        if (terminal.kind() != ActorFailureTrace.BoundaryKind.ACTOR_TERMINAL
                || terminal.actorGeneration() <= 0) return false;
        String firstSupervisor = null;
        for (ActorFailureTrace.Boundary boundary : path) {
            if (boundary.kind() == ActorFailureTrace.BoundaryKind.SUPERVISOR_ASSOCIATION) {
                firstSupervisor = boundary.identity();
                break;
            }
        }
        if (!supervisorIdentity.equals(firstSupervisor)) return false;
        for (Entry entry : active.values()) {
            Receipt known = entry.receipt();
            if (known.actorIdentity().equals(terminal.identity())
                    && known.actorGeneration() == terminal.actorGeneration()) {
                return true; // Idempotent admission; no second terminal receipt.
            }
        }
        if (active.size() >= capacity || nextSequence == Long.MAX_VALUE) return false;
        long sequence = nextSequence;
        Receipt receipt = new Receipt(sequence, 0, supervisorIdentity,
                terminal.identity(), terminal.actorGeneration(), trace);
        Entry queuedEntry = new Entry(receipt, false);
        // The HashMap and ArrayDeque are two parts of ONE admission. An OOM
        // between their writes cannot leave a phantom outstanding receipt
        // which blocks capacity forever or can never be polled. Do not
        // consume sequence space until both structures are consistent.
        try {
            active.put(sequence, queuedEntry);
            queued.addLast(sequence);
        } catch (RuntimeException | OutOfMemoryError incompleteAdmission) {
            active.remove(sequence);
            queued.remove(sequence);
            throw incompleteAdmission;
        }
        nextSequence = sequence + 1;
        return true;
    }

    /** Host-only observation; claim does not mean guest supervisor execution. */
    synchronized Optional<Receipt> pollHost() {
        Long sequence = queued.peekFirst();
        if (sequence == null) return Optional.empty();
        Entry entry = active.get(sequence);
        if (entry == null || entry.claimed()) {
            throw new IllegalStateException("failure inbox receipt accounting violated");
        }
        // Never consume a queued receipt if attempt numbering is exhausted.
        Receipt claimed = entry.receipt().nextClaimAttempt();
        active.put(sequence, new Entry(claimed, true));
        queued.removeFirst();
        return Optional.of(claimed);
    }

    /**
     * Acknowledge only a receipt previously claimed from THIS inbox. The
     * actor-generation comparison prevents an old incarnation acknowledging
     * the newer one's receipt. A repeated ack returns false.
     */
    synchronized boolean acknowledgeHost(
            long sequence, String actorIdentity, long actorGeneration,
            long claimAttempt) {
        Objects.requireNonNull(actorIdentity, "actorIdentity");
        Entry entry = active.get(sequence);
        if (entry == null || !entry.claimed()) return false;
        Receipt receipt = entry.receipt();
        if (!receipt.actorIdentity().equals(actorIdentity)
                || receipt.actorGeneration() != actorGeneration
                || receipt.claimAttempt() != claimAttempt) return false;
        active.remove(sequence);
        return true;
    }

    /**
     * On host CONTROL coordinator restart, unacknowledged claims can be
     * retried with the SAME sequence. This is at-least-once handoff, not
     * proof of exactly-once guest policy execution; the eventual handler
     * must fence/ack its terminal key idempotently.
     */
    synchronized int requeueUnacknowledgedHost() {
        java.util.ArrayList<Long> claimed = new java.util.ArrayList<>();
        for (Map.Entry<Long, Entry> item : active.entrySet()) {
            if (item.getValue().claimed()) claimed.add(item.getKey());
        }
        claimed.sort(Long::compareTo);
        for (int i = claimed.size() - 1; i >= 0; i--) {
            Long sequence = claimed.get(i);
            Entry entry = active.get(sequence);
            // Allocate the replacement BEFORE changing either index.
            // Queuing first prevents the OOM hole where active says the
            // receipt is unclaimed but its sequence is no longer queued.
            Entry replayable = new Entry(entry.receipt(), false);
            queued.addFirst(sequence);
            try {
                // Replacing an existing exact HashMap key does not grow it.
                active.put(sequence, replayable);
            } catch (RuntimeException | Error failedCommit) {
                // A failed index update may not strand a phantom receipt.
                queued.removeFirstOccurrence(sequence);
                throw failedCommit;
            }
        }
        return claimed.size();
    }

    public synchronized int outstandingCount() { return active.size(); }
    public synchronized int queuedCount() { return queued.size(); }
}
