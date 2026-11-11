package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/**
 * Bounded, non-parking actor-state reader/writer admission.
 *
 * <p>Authority is the logical execution identity plus its generation-fenced
 * execution handle and actor allocation generation. Physical JVM carrier
 * threads are intentionally irrelevant. Reads may coexist; writes are
 * exclusive; queued writers prevent later readers from bypassing them.</p>
 *
 * <p>This class allocates one Future/loan per acquisition today. It is a
 * correctness baseline, not the final low-allocation representation. Oreslang
 * lowering may later erase/value-lower loan capabilities after proving scope.</p>
 */
public final class ActorStateRwLoans implements AutoCloseable {
    public enum Access { READ, WRITE }

    private record Authority(
            ExecutionContext.ExecutionId id,
            ExecutionContext.ExecutionHandle handle,
            ExecutionContext.AllocationDomain allocation) { }

    private final Object monitor = new Object();
    private final int maxPending;
    private final int maxReaders;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final ArrayDeque<Runnable> completions = new ArrayDeque<>();
    private final Map<Authority, StateLoan> readers = new LinkedHashMap<>();
    private final Set<Authority> reserved = new HashSet<>();
    // A queued Future may be logically removed before its terminal host
    // notifications physically finish. Neither cancellation nor STOP may
    // reclaim the actor arena while such a callback is still executing.
    private int pendingNotificationDrains;
    private StateLoan writer;
    private final ExecutionContext.AllocationDomain boundAllocation;
    private long nextEpoch;
    private boolean closed;
    private boolean publishing;
    private final Runnable retirementWakeup;
    private boolean retirementWakeupDelivered;

    /**
     * The actor's region owner is selected at allocation time, NEVER by its
     * first reader/writer. Package-private constructor: callers outside the
     * runtime package cannot forge an independent state lock from a value
     * descriptor. Production admission must use ActorRuntime's one-use
     * StateRegionMint and ActorStateSupervisorLoans; ordinary allocation
     * records are NOT bearer capabilities.
     */
    ActorStateRwLoans(
            ExecutionContext.AllocationDomain ownerAllocation,
            int maxPending, int maxReaders) {
        this(ownerAllocation, maxPending, maxReaders, () -> { });
    }

    /**
     * Host/runtime constructor. Wakeup fires only after close() seals new
     * admission and the final physical reader/writer/completion retires.
     * The active-reader limit is separate from the pending queue limit.
     */
    ActorStateRwLoans(
            ExecutionContext.AllocationDomain ownerAllocation,
            int maxPending, int maxReaders, Runnable retirementWakeup) {
        this.boundAllocation = Objects.requireNonNull(
                ownerAllocation, "ownerAllocation");
        if (boundAllocation.kind() != ExecutionContext.AllocationKind.ACTOR ||
                !boundAllocation.isBound()) {
            throw new IllegalArgumentException(
                    "state RW region must be bound to an actor allocation at construction");
        }
        if (maxPending < 0 || maxPending > 8192) {
            throw new IllegalArgumentException(
                    "state RW pending capacity outside 0..8192");
        }
        if (maxReaders < 1 || maxReaders > 65536) {
            throw new IllegalArgumentException("state RW reader capacity outside 1..65536");
        }
        this.maxPending = maxPending;
        this.maxReaders = maxReaders;
        this.retirementWakeup = Objects.requireNonNull(
                retirementWakeup, "retirementWakeup");
    }

    /** Nonblocking physical-retirement receipt for ActorRuntime finalization. */
    public boolean physicallyRetired() {
        synchronized (monitor) {
            return physicallyRetiredLocked();
        }
    }

    private boolean physicallyRetiredLocked() {
        return closed && writer == null && readers.isEmpty()
                && reserved.isEmpty() && pending.isEmpty()
                && pendingNotificationDrains == 0
                && completions.isEmpty() && !publishing;
    }

    private void maybeWakeRetirement() {
        boolean wake = false;
        synchronized (monitor) {
            if (physicallyRetiredLocked() && !retirementWakeupDelivered) {
                retirementWakeupDelivered = true;
                wake = true;
            }
        }
        if (wake) retirementWakeup.run();
    }

    public OresFuture<StateLoan> acquireReadAsync(ExecutionContext execution) {
        return acquireAsync(execution, Access.READ);
    }

    public OresFuture<StateLoan> acquireWriteAsync(ExecutionContext execution) {
        return acquireAsync(execution, Access.WRITE);
    }

    private OresFuture<StateLoan> acquireAsync(
            ExecutionContext execution, Access access) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(access, "access");
        if (!validStateExecution(execution)) {
            return OresFuture.failed(new IllegalArgumentException(
                    "actor state RW loan requires bound actor execution context"));
        }
        Authority authority = authority(execution);
        synchronized (monitor) {
            if (closed) {
                return OresFuture.failed(new IllegalStateException(
                        "actor state RW loans closed"));
            }
            if (!boundAllocation.equals(execution.allocationDomain())) {
                return OresFuture.failed(new IllegalArgumentException(
                        "actor state RW loans belong to a different actor allocation incarnation"));
            }
            // Each queued request may need an eventual distinct grant epoch.
            // Reserve arithmetic capacity before accepting it so rollover
            // cannot strand a removed FIFO head in promoteLocked().
            if (nextEpoch >= Long.MAX_VALUE - pending.size()) {
                return OresFuture.failed(new IllegalStateException(
                        "actor state RW loan epochs exhausted"));
            }
            if (!reserved.add(authority)) {
                return OresFuture.failed(new IllegalStateException(
                        "logical execution already holds or awaits a state RW loan"));
            }

            if (pending.isEmpty() && canGrantLocked(access)) {
                try {
                    return OresFuture.completed(
                            createLocked(authority, access));
                } catch (RuntimeException | Error failure) {
                    reserved.remove(authority);
                    throw failure;
                }
            }

            if (pending.size() >= maxPending) {
                reserved.remove(authority);
                return OresFuture.failed(new IllegalStateException(
                        "actor state RW loan queue full"));
            }
            Pending waiter = new Pending(authority, access);
            pending.addLast(waiter);
            return waiter.future;
        }
    }

    public int activeReaderCount() {
        synchronized (monitor) { return readers.size(); }
    }

    public int activeWriterCount() {
        synchronized (monitor) { return writer == null ? 0 : 1; }
    }

    public int pendingCount() {
        synchronized (monitor) { return pending.size(); }
    }

    /**
     * Observational suspension precondition for this region. This is NOT an
     * authorization capability: scheduler admission must independently check
     * the authenticated execution/actor generation and all its region loans.
     * A stale released handle cannot attest clearance while a new request
     * from the same logical execution is reserved.
     */
    public boolean noOutstandingLoanFor(ExecutionContext execution) {
        Objects.requireNonNull(execution, "execution");
        if (!validStateExecution(execution) ||
                !boundAllocation.equals(execution.allocationDomain())) return false;
        synchronized (monitor) {
            // An empty loan ledger on a stopped actor is NOT a suspension
            // permit. The generation is revoked once close() begins.
            return !closed && !reserved.contains(authority(execution));
        }
    }

    private static boolean validStateExecution(ExecutionContext execution) {
        return execution.executionHandle().isBound() &&
                execution.allocationDomain().kind() ==
                        ExecutionContext.AllocationKind.ACTOR &&
                execution.allocationDomain().isBound();
    }

    private static Authority authority(ExecutionContext execution) {
        return new Authority(
                execution.id(),
                execution.executionHandle(),
                execution.allocationDomain());
    }

    private boolean canGrantLocked(Access access) {
        if (access == Access.WRITE) {
            return writer == null && readers.isEmpty();
        }
        return writer == null && readers.size() < maxReaders;
    }

    private StateLoan createLocked(Authority authority, Access access) {
        if (nextEpoch == Long.MAX_VALUE) {
            throw new IllegalStateException("actor state RW loan epochs exhausted");
        }
        StateLoan loan = new StateLoan(authority, access, ++nextEpoch);
        if (access == Access.WRITE) {
            if (writer != null || !readers.isEmpty()) {
                throw new IllegalStateException("state RW writer grant overlaps live loan");
            }
            writer = loan;
        } else {
            if (writer != null || readers.putIfAbsent(authority, loan) != null) {
                throw new IllegalStateException("state RW reader grant overlaps authority");
            }
        }
        return loan;
    }

    /**
     * FIFO writer fairness: when readers are live, only a contiguous head
     * cohort of queued readers may join them. A writer at the head blocks
     * every later reader until the current readers retire and the writer runs.
     */
    private void promoteLocked() {
        if (closed || writer != null) return;

        while (!pending.isEmpty()) {
            Pending head = pending.peekFirst();
            if (head.future.isCancelled()) {
                pending.removeFirst();
                reserved.remove(head.authority);
                continue;
            }

            if (head.access == Access.WRITE) {
                if (!readers.isEmpty()) return;
                pending.removeFirst();
                StateLoan loan = createLocked(head.authority, Access.WRITE);
                completions.addLast(() -> {
                    if (!head.future.completeFromRuntime(loan)) loan.close();
                });
                return;
            }

            // Admit only as many FIFO readers as there is physical capacity.
            // A reader left queued at the head blocks later writers; once its
            // predecessor retires, its reserved slot is promoted first.
            if (readers.size() >= maxReaders) return;
            pending.removeFirst();
            StateLoan loan = createLocked(head.authority, Access.READ);
            completions.addLast(() -> {
                if (!head.future.completeFromRuntime(loan)) loan.close();
            });
            if (!pending.isEmpty() && pending.peekFirst().access == Access.WRITE) {
                return;
            }
        }
    }

    private void publish() {
        synchronized (monitor) {
            if (publishing) return;
            publishing = true;
        }
        Throwable firstFailure = null;
        for (;;) {
            Runnable completion;
            synchronized (monitor) {
                completion = completions.pollFirst();
                if (completion == null) {
                    publishing = false;
                    break;
                }
            }
            try {
                completion.run();
            } catch (Throwable failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        // The final actor loan may have been released by a different thread
        // while this publisher was still delivering an earlier completion.
        // In that case StateLoan.close() sees publishing=true and cannot
        // notify retirement; the publisher must recheck after it drains.
        maybeWakeRetirement();
        if (firstFailure != null) {
            if (firstFailure instanceof Error fatal) throw fatal;
            if (firstFailure instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException(
                    "actor state RW completion failed", firstFailure);
        }
    }

    private final class Pending {
        final Authority authority;
        final Access access;
        final OresFuture<StateLoan> future;

        Pending(Authority authority, Access access) {
            this.authority = authority;
            this.access = access;
            future = new OresFuture<>(() -> {
                boolean removed;
                synchronized (monitor) {
                    removed = pending.remove(this);
                    if (removed) {
                        reserved.remove(authority);
                        promoteLocked();
                    }
                }
                if (removed) publish();
            });
            // All Pending instances are constructed with monitor held.
            // Track the *physical* drain, not merely the terminal CAS or
            // removal from pending/reserved. The Future guarantees that this
            // receipt runs once after host runtime subscribers unwind, for
            // cancellation, an ordinary grant, and shutdown failure alike.
            pendingNotificationDrains++;
            future.whenNotificationsDrainedRuntime(() -> {
                synchronized (monitor) {
                    if (pendingNotificationDrains <= 0) {
                        throw new IllegalStateException(
                                "state RW notification-drain ledger corrupted");
                    }
                    pendingNotificationDrains--;
                }
                maybeWakeRetirement();
            });
        }
    }

    public final class StateLoan implements AutoCloseable {
        private final Authority authority;
        private final Access access;
        private final long epoch;
        private boolean released;

        private StateLoan(Authority authority, Access access, long epoch) {
            this.authority = authority;
            this.access = access;
            this.epoch = epoch;
        }

        public Access access() { return access; }
        public long epoch() { return epoch; }

        /** Observational only; actual state access must be coupled to this loan. */
        public boolean heldBy(ExecutionContext execution) {
            Objects.requireNonNull(execution, "execution");
            Authority candidate = authority(execution);
            synchronized (monitor) {
                if (closed || released || !authority.equals(candidate)) return false;
                return access == Access.WRITE
                        ? writer == this
                        : readers.get(authority) == this;
            }
        }

        /**
         * Protected operations may not create actor-visible suspension while
         * this capability is live. Worker budget checkpoints are a separate
         * scheduler mechanism and do not call this method.
         */
        public boolean readyToSuspend(ExecutionContext execution) {
            Objects.requireNonNull(execution, "execution");
            // heldBy() intentionally becomes false as soon as close() fences
            // logical access. That is NOT physical retirement: a live loan
            // must still block suspension while its handler unwinds.
            //
            // Do not invert heldBy(): a foreign or stale execution would then
            // appear free to suspend while another execution owns this loan.
            if (!authority.equals(authority(execution))) return false;
            synchronized (monitor) {
                // A previously released handle is not proof that this same
                // execution has no *new* active or queued loan. Consult the
                // current ledger as well as the physical release receipt.
                return released && !reserved.contains(authority);
            }
        }

        @Override
        public void close() {
            synchronized (monitor) {
                if (released) return;
                released = true;
                if (access == Access.WRITE) {
                    if (writer != this) {
                        throw new IllegalStateException(
                                "actor state RW writer ownership corrupted");
                    }
                    writer = null;
                } else {
                    if (readers.remove(authority) != this) {
                        throw new IllegalStateException(
                                "actor state RW reader ownership corrupted");
                    }
                }
                reserved.remove(authority);
                promoteLocked();
            }
            // Publication can invoke callbacks that fail. Their failures
            // must not suppress the terminal physical-retirement notification.
            try {
                publish();
            } finally {
                maybeWakeRetirement();
            }
        }
    }

    @Override
    public void close() {
        ArrayDeque<Pending> abandoned;
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            abandoned = new ArrayDeque<>(pending);
            pending.clear();
            for (Pending waiter : abandoned) reserved.remove(waiter.authority);
        }
        for (Pending waiter : abandoned) {
            waiter.future.failFromRuntime(new IllegalStateException(
                    "actor state RW loans closed"));
        }
        // Active loans remain physically held until handler cleanup closes them;
        // logical close merely fences further state access.
        maybeWakeRetirement();
    }
}
