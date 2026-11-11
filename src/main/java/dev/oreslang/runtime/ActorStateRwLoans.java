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
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final ArrayDeque<Runnable> completions = new ArrayDeque<>();
    private final Map<Authority, StateLoan> readers = new LinkedHashMap<>();
    private final Set<Authority> reserved = new HashSet<>();
    // These separate identity fences prevent a rematerialized task from
    // acquiring twice by changing its slot, or a reused slot from serving
    // two different logical tasks at the same time.
    private final Set<ExecutionContext.ExecutionId> reservedExecutions = new HashSet<>();
    private final Set<ExecutionContext.ExecutionHandle> reservedHandles = new HashSet<>();
    // Generations fence ABA, but they do not permit two physically live
    // generations of the same scheduler slot to hold state concurrently.
    private final Set<Long> reservedSlots = new HashSet<>();
    private StateLoan writer;
    private ExecutionContext.AllocationDomain boundAllocation;
    private long nextEpoch;
    private boolean closed;
    private boolean publishing;
    // Every queued Future owns exactly one retirement pin from queue admission
    // through terminal notification drain, even if promotion/cancellation wins
    // a CAS after the waiter leaves pending. No removal path may omit this pin.
    private long terminalNotificationsInFlight;
    // If a Future's cancellation revoker fails, the owner cannot treat
    // terminal notification drain as proof that its actor arena was safely
    // released. Quarantine physical retirement until an authorized runtime
    // owner diagnoses the failed revocation. Never silently drop this error.
    private Throwable retirementQuarantineCause;
    private final Runnable retirementWakeup;
    private boolean retirementWakeupDelivered;

    public ActorStateRwLoans(int maxPending) {
        this(maxPending, () -> { });
    }

    /**
     * Host/runtime constructor. Wakeup fires only after close() seals new
     * admission and the final physical reader/writer/completion retires.
     */
    public ActorStateRwLoans(int maxPending, Runnable retirementWakeup) {
        this(maxPending, retirementWakeup, null);
    }

    /**
     * Production actor-state allocation must bind its owner BEFORE the first
     * acquisition. Otherwise an unrelated execution can claim an uninitialized
     * region and make its own allocation appear authoritative. The nullable
     * compatibility overload above remains for isolated host-only test ledgers.
     */
    ActorStateRwLoans(int maxPending, Runnable retirementWakeup,
            ExecutionContext.AllocationDomain owningAllocation) {
        if (maxPending < 0) throw new IllegalArgumentException("negative state RW queue capacity");
        if (owningAllocation != null
                && (owningAllocation.kind() != ExecutionContext.AllocationKind.ACTOR
                    || !owningAllocation.isBound())) {
            throw new IllegalArgumentException(
                    "actor state RW owner must be a live actor allocation domain");
        }
        this.maxPending = maxPending;
        this.retirementWakeup = Objects.requireNonNull(
                retirementWakeup, "retirementWakeup");
        this.boundAllocation = owningAllocation;
    }

    /** Nonblocking physical-retirement receipt for ActorRuntime finalization. */
    public boolean physicallyRetired() {
        synchronized (monitor) {
            return physicallyRetiredLocked();
        }
    }

    /** Runtime-only diagnostic; a failed cancellation revoker blocks reclamation. */
    Throwable retirementQuarantineCause() {
        synchronized (monitor) {
            return retirementQuarantineCause;
        }
    }

    private boolean physicallyRetiredLocked() {
        return closed && writer == null && readers.isEmpty()
                && pending.isEmpty() && completions.isEmpty() && !publishing
                && reserved.isEmpty() && reservedExecutions.isEmpty()
                && reservedHandles.isEmpty() && reservedSlots.isEmpty()
                && terminalNotificationsInFlight == 0
                && retirementQuarantineCause == null;
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
            // Report the recorded revocation failure even after automatic
            // shutdown, rather than hiding it behind a generic "closed".
            if (retirementQuarantineCause != null) {
                return OresFuture.failed(new IllegalStateException(
                        "actor state RW loans quarantined after failed revocation",
                        retirementQuarantineCause));
            }
            if (closed) {
                return OresFuture.failed(new IllegalStateException(
                        "actor state RW loans closed"));
            }
            if (boundAllocation == null) {
                boundAllocation = execution.allocationDomain();
            } else if (!boundAllocation.equals(execution.allocationDomain())) {
                return OresFuture.failed(new IllegalArgumentException(
                        "actor state RW loans belong to a different actor allocation incarnation"));
            }
            if (!reserveLocked(authority)) {
                return OresFuture.failed(new IllegalStateException(
                        "logical execution or execution slot already holds or awaits a state RW loan"));
            }

            if (pending.isEmpty() && canGrantLocked(access)) {
                try {
                    return OresFuture.completed(
                            createLocked(authority, access, null));
                } catch (RuntimeException | Error failure) {
                    unreserveLocked(authority);
                    throw failure;
                }
            }

            if (pending.size() >= maxPending) {
                unreserveLocked(authority);
                return OresFuture.failed(new IllegalStateException(
                        "actor state RW loan queue full"));
            }
            Pending waiter = new Pending(authority, access);
            pending.addLast(waiter);
            // The Future has not escaped, and cannot settle before this
            // registration. Account once at admission rather than when a
            // cancellation or promotion eventually removes the queue node.
            // This fences every terminal-CAS / producer-promotion race.
            terminalNotificationsInFlight++;
            trackNotificationDrain(waiter);
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

    // All identity indexes are changed together while holding monitor.
    // The execution-id fence also prevents an old released handle from
    // claiming that a newly rematerialized execution can safely suspend.
    private boolean reserveLocked(Authority candidate) {
        if (reserved.contains(candidate)
                || reservedExecutions.contains(candidate.id())
                || reservedHandles.contains(candidate.handle())
                || reservedSlots.contains(candidate.handle().slot())) {
            return false;
        }
        reserved.add(candidate);
        reservedExecutions.add(candidate.id());
        reservedHandles.add(candidate.handle());
        reservedSlots.add(candidate.handle().slot());
        return true;
    }

    private void unreserveLocked(Authority authority) {
        if (!reserved.remove(authority)
                || !reservedExecutions.remove(authority.id())
                || !reservedHandles.remove(authority.handle())
                || !reservedSlots.remove(authority.handle().slot())) {
            throw new IllegalStateException("state RW execution reservation ledger corrupted");
        }
    }

    private boolean canGrantLocked(Access access) {
        if (access == Access.WRITE) {
            return writer == null && readers.isEmpty();
        }
        return writer == null;
    }

    private StateLoan createLocked(Authority authority, Access access, Pending owner) {
        if (nextEpoch == Long.MAX_VALUE) {
            throw new IllegalStateException("actor state RW loan epochs exhausted");
        }
        StateLoan loan = new StateLoan(authority, access, ++nextEpoch, owner);
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
        // A queued Future's authority belongs to both the granted loan
        // and its terminal notification drain; neither may release it alone.
        if (owner != null) owner.loan = loan;
        return loan;
    }

    /**
     * FIFO writer fairness: when readers are live, only a contiguous head
     * cohort of queued readers may join them. A writer at the head blocks
     * every later reader until the current readers retire and the writer runs.
     */
    private void promoteLocked() {
        // An observed cancellation-revoker failure means the actor's
        // physical state is no longer trustworthy. Never grant another
        // queued loan while the arena is quarantined.
        if (closed || retirementQuarantineCause != null || writer != null) return;

        while (!pending.isEmpty()) {
            Pending head = pending.peekFirst();
            if (head.future.isCancelled()) {
                // This future's notification receipt was registered when it
                // entered pending. Its cancellation CAS may precede its hook;
                // removing the node here does not retire the notification pin.
                pending.removeFirst();
                head.abandoned = true;
                continue;
            }

            if (head.access == Access.WRITE) {
                if (!readers.isEmpty()) return;
                pending.removeFirst();
                StateLoan loan = createLocked(head.authority, Access.WRITE, head);
                completions.addLast(() -> {
                    if (!head.future.completeFromRuntime(loan)) loan.close();
                });
                return;
            }

            // Head reader is eligible whenever no writer is active. Admit the
            // entire contiguous reader cohort, then stop before the next writer.
            pending.removeFirst();
            StateLoan loan = createLocked(head.authority, Access.READ, head);
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
        // The last completion may retire during close() while publishing is
        // still true. No other caller is guaranteed to issue a wakeup once
        // the publisher clears that flag, so perform the final retirement
        // check here even when completion callbacks failed.
        maybeWakeRetirement();
        if (firstFailure != null) {
            if (firstFailure instanceof Error fatal) throw fatal;
            if (firstFailure instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException(
                    "actor state RW completion failed", firstFailure);
        }
    }

    private void trackNotificationDrain(Pending waiter) {
        waiter.future.whenNotificationsDrainedRuntime(() -> {
            // A successful promotion may close its physical loan from inside
            // one callback while other callbacks still run. Conversely,
            // cancellation/close can remove the queued node before its
            // terminal hook or callbacks finish. The authority is reusable
            // only after BOTH the loan (if granted) and notifications drain.
            Throwable revocationFailure = waiter.future.cancellationHookFailureFromRuntime();
            boolean firstQuarantine = false;
            synchronized (monitor) {
                if (revocationFailure != null && retirementQuarantineCause == null) {
                    retirementQuarantineCause = revocationFailure;
                    firstQuarantine = true;
                }
                if (terminalNotificationsInFlight <= 0 || waiter.notificationsDrained) {
                    throw new IllegalStateException("state RW notification receipt ledger corrupted");
                }
                waiter.notificationsDrained = true;
                if (waiter.abandoned || (waiter.loan != null && waiter.loan.released)) {
                    unreserveLocked(waiter.authority);
                } else if (waiter.loan == null) {
                    // A terminal Future with neither abandonment nor a loan
                    // lacks an accountable physical owner. Quarantine it.
                    if (retirementQuarantineCause == null) {
                        retirementQuarantineCause = new IllegalStateException(
                                "state RW terminal waiter has no retirement owner");
                    }
                }
                terminalNotificationsInFlight--;
            }
            if (firstQuarantine) {
                // Quarantining admission without settling already queued
                // Futures strands their callers. Seal logical access and fail
                // every still-pending request. close() performs callbacks
                // OUTSIDE monitor; keep the arena quarantined regardless of
                // any observer errors during this fail-closed shutdown.
                close();
            }
            maybeWakeRetirement();
        });
    }

    private final class Pending {
        final Authority authority;
        final Access access;
        final OresFuture<StateLoan> future;
        // All mutable members below are protected by the outer monitor.
        StateLoan loan;
        boolean abandoned;
        boolean notificationsDrained;

        Pending(Authority authority, Access access) {
            this.authority = authority;
            this.access = access;
            future = new OresFuture<>(() -> {
                boolean removed;
                synchronized (monitor) {
                    removed = pending.remove(this);
                    if (removed) {
                        // Keep the execution ID and raw slot reserved until
                        // OresFuture completes its physical notification drain.
                        abandoned = true;
                        promoteLocked();
                    }
                }
                if (removed) publish();
            });
        }
    }

    public final class StateLoan implements AutoCloseable {
        private final Authority authority;
        private final Access access;
        private final long epoch;
        private final Pending owner;
        private boolean released;

        private StateLoan(Authority authority, Access access, long epoch, Pending owner) {
            this.authority = authority;
            this.access = access;
            this.epoch = epoch;
            this.owner = owner;
        }

        public Access access() { return access; }
        public long epoch() { return epoch; }

        /** Observational only; actual state access must be coupled to this loan. */
        public boolean heldBy(ExecutionContext execution) {
            Objects.requireNonNull(execution, "execution");
            Authority candidate = authority(execution);
            synchronized (monitor) {
                if (closed || retirementQuarantineCause != null
                        || released || !authority.equals(candidate)) return false;
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
                return released && retirementQuarantineCause == null
                        && !reservedExecutions.contains(authority.id())
                        && !reservedHandles.contains(authority.handle())
                        && !reservedSlots.contains(authority.handle().slot());
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
                // Direct grants own their reservation outright. For queued
                // grants, a callback may still run after the loan is closed;
                // keep its authority fenced until the Future receipt.
                if (owner == null || owner.notificationsDrained) {
                    unreserveLocked(authority);
                }
                promoteLocked();
            }
            publish();
            maybeWakeRetirement();
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
            for (Pending waiter : abandoned) {
                // The queued Future retains this authority through failure
                // notification drain, even though the queue slot is freed.
                waiter.abandoned = true;
            }
            // Every abandoned Future already has a terminal drain pin from
            // queue admission; shutdown must neither add nor duplicate one.
        }

        Throwable firstFailure = null;
        for (Pending waiter : abandoned) {
            try {
                Throwable quarantine;
                synchronized (monitor) { quarantine = retirementQuarantineCause; }
                waiter.future.failFromRuntime(quarantine == null
                        ? new IllegalStateException("actor state RW loans closed")
                        : new IllegalStateException(
                                "actor state RW loans quarantined after failed revocation",
                                quarantine));
            } catch (Throwable failure) {
                // Attempt to settle the other abandoned requests too. A
                // failed/undrained Future retains its retirement fence.
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }

        // Active loans remain physically held until handler cleanup closes
        // them; logical close merely fences further state access.
        maybeWakeRetirement();
        if (firstFailure instanceof Error fatal) throw fatal;
        if (firstFailure instanceof RuntimeException failure) throw failure;
        if (firstFailure != null) {
            throw new IllegalStateException("state RW shutdown notification failed", firstFailure);
        }
    }
}
