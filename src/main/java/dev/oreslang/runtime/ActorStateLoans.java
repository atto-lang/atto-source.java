package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * VM-internal exclusive actor-state loan arbitration. A loan belongs to a
 * logical handler identity, not to an OS thread, and survives scheduler
 * suspension until explicitly closed. This is deliberately NOT a guest-facing
 * mutex or a permission to access actor fields: compiler/runtime state access
 * still needs to be bound to the active loan and its handler identity.
 *
 * No guest code, Future completion, or continuation executes under monitor.
 */
public final class ActorStateLoans implements AutoCloseable {
    private final Object monitor = new Object();
    private final int maxPending;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final ArrayDeque<Runnable> completions = new ArrayDeque<>();
    private final Set<Object> owners =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private ExclusiveLoan active;
    private long nextEpoch;
    private boolean closed;
    private boolean publishing;
    /** Pending Future close notifications may be blocked on external subscribers. */
    private boolean failingPendingCallbacks;
    private AuthorityMode authorityMode = AuthorityMode.UNSET;
    private ExecutionContext.AllocationDomain boundAllocation;
    private final Runnable retirementWakeup;
    private boolean retirementWakeupDelivered;

    private enum AuthorityMode { UNSET, LEGACY, EXECUTION_BOUND }

    public ActorStateLoans(int maxPending) {
        this(maxPending, () -> { });
    }

    /**
     * Host/runtime constructor. The wakeup is invoked only after close() has
     * sealed new admission and the final physical loan/completion has retired.
     */
    public ActorStateLoans(int maxPending, Runnable retirementWakeup) {
        if (maxPending < 0) throw new IllegalArgumentException("negative loan queue capacity");
        this.maxPending = maxPending;
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
        return closed && active == null && pending.isEmpty()
                && completions.isEmpty() && !publishing
                && !failingPendingCallbacks;
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

    public int pendingCount() {
        synchronized (monitor) { return pending.size(); }
    }

    public int activeCount() {
        synchronized (monitor) { return active == null ? 0 : 1; }
    }

    /**
     * Runtime supplies a unique identity for each logical handler. Re-entry
     * by the same handler is rejected rather than self-deadlocking.
     */
    public OresFuture<ExclusiveLoan> acquireAsync(Object handlerIdentity) {
        Objects.requireNonNull(handlerIdentity, "handlerIdentity");
        // Do not let a caller erase the static type to bypass execution
        // generation / actor-allocation authority checks.
        if (handlerIdentity instanceof ExecutionContext execution) {
            return acquireAsync(execution);
        }
        return acquireInternal(handlerIdentity, null);
    }

    /**
     * Preferred actor-state path. Authority follows the logical execution
     * across carrier-thread migration but is generation-fenced against stale
     * scheduler slots and actor-arena incarnations.
     */
    public OresFuture<ExclusiveLoan> acquireAsync(ExecutionContext execution) {
        Objects.requireNonNull(execution, "execution");
        if (!validStateExecution(execution)) {
            return OresFuture.failed(new IllegalArgumentException(
                    "actor state loan requires bound actor execution context"));
        }
        return acquireInternal(execution, execution);
    }

    private OresFuture<ExclusiveLoan> acquireInternal(
            Object handlerIdentity, ExecutionContext executionOwner) {
        synchronized (monitor) {
            if (closed) return OresFuture.failed(new IllegalStateException("actor state loans closed"));
            if (executionOwner == null) {
                if (authorityMode == AuthorityMode.EXECUTION_BOUND) {
                    return OresFuture.failed(new IllegalStateException(
                            "legacy state-loan identity cannot enter an execution-bound arbiter"));
                }
                authorityMode = AuthorityMode.LEGACY;
            } else {
                if (authorityMode == AuthorityMode.LEGACY) {
                    return OresFuture.failed(new IllegalStateException(
                            "execution-bound state authority cannot mix with legacy identity"));
                }
                if (boundAllocation == null) {
                    boundAllocation = executionOwner.allocationDomain();
                } else if (!boundAllocation.equals(executionOwner.allocationDomain())) {
                    return OresFuture.failed(new IllegalArgumentException(
                            "actor state loan belongs to a different actor allocation incarnation"));
                }
                authorityMode = AuthorityMode.EXECUTION_BOUND;
                if (hasExecutionAuthorityLocked(executionOwner)) {
                    return OresFuture.failed(new IllegalStateException(
                            "logical execution already holds or awaits a state loan"));
                }
            }
            if (!owners.add(handlerIdentity)) {
                return OresFuture.failed(new IllegalStateException(
                        "handler already holds or awaits a state loan"));
            }
            if (active == null && pending.isEmpty()) {
                try {
                    ExclusiveLoan loan = createLocked(handlerIdentity, executionOwner);
                    return OresFuture.completed(loan);
                } catch (RuntimeException | Error failure) {
                    owners.remove(handlerIdentity);
                    throw failure;
                }
            }
            if (pending.size() >= maxPending) {
                owners.remove(handlerIdentity);
                return OresFuture.failed(new IllegalStateException("actor state loan queue full"));
            }
            Pending waiter = new Pending(handlerIdentity, executionOwner);
            pending.addLast(waiter);
            return waiter.future;
        }
    }

    private static boolean validStateExecution(ExecutionContext execution) {
        return execution.executionHandle().isBound() &&
                execution.allocationDomain().kind() == ExecutionContext.AllocationKind.ACTOR &&
                execution.allocationDomain().isBound();
    }

    private static boolean sameExecutionAuthority(
            ExecutionContext left, ExecutionContext right) {
        return left != null && right != null &&
                left.id().equals(right.id()) &&
                left.executionHandle().equals(right.executionHandle()) &&
                left.allocationDomain().equals(right.allocationDomain());
    }

    private boolean hasExecutionAuthorityLocked(ExecutionContext execution) {
        if (active != null && sameExecutionAuthority(active.executionOwner, execution)) {
            return true;
        }
        for (Pending waiter : pending) {
            if (sameExecutionAuthority(waiter.executionOwner, execution)) return true;
        }
        return false;
    }

    private ExclusiveLoan createLocked(Object owner, ExecutionContext executionOwner) {
        if (nextEpoch == Long.MAX_VALUE) {
            throw new IllegalStateException("actor state loan epochs exhausted");
        }
        ExclusiveLoan loan = new ExclusiveLoan(owner, executionOwner, ++nextEpoch);
        active = loan;
        return loan;
    }

    private void promoteLocked() {
        if (closed || active != null) return;
        while (!pending.isEmpty()) {
            Pending waiter = pending.removeFirst();
            if (waiter.future.isCancelled()) {
                owners.remove(waiter.owner);
                continue;
            }
            ExclusiveLoan loan = createLocked(waiter.owner, waiter.executionOwner);
            completions.addLast(() -> {
                // The Future may have been cancelled after its queue removal.
                // A losing grant must release its loan and promote the next.
                if (!waiter.future.completeFromRuntime(loan)) loan.close();
            });
            return;
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
                // A broken completion must not strand every later FIFO grant.
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        // Last physical loan may close from inside a completion subscriber
        // while publishing=true; only the outer publisher exit can wake the
        // actor finalizer once the synchronous notifications have unwound.
        maybeWakeRetirement();
        if (firstFailure != null) {
            if (firstFailure instanceof Error fatal) throw fatal;
            if (firstFailure instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException("actor state loan completion failed", firstFailure);
        }
    }

    private final class Pending {
        final Object owner;
        final ExecutionContext executionOwner;
        final OresFuture<ExclusiveLoan> future;
        Pending(Object owner, ExecutionContext executionOwner) {
            this.owner = owner;
            this.executionOwner = executionOwner;
            future = new OresFuture<>(() -> {
                synchronized (monitor) {
                    if (pending.remove(this)) owners.remove(owner);
                }
            });
        }
    }

    public final class ExclusiveLoan implements AutoCloseable {
        private final Object owner;
        private final ExecutionContext executionOwner;
        private final long epoch;
        private boolean released;

        private ExclusiveLoan(
                Object owner, ExecutionContext executionOwner, long epoch) {
            this.owner = owner;
            this.executionOwner = executionOwner;
            this.epoch = epoch;
        }

        /** Monotonic instance-local epoch, for future generation fencing. */
        public long epoch() { return epoch; }

        /** Observational only: never use this as a check-then-access guard. */
        public boolean heldBy(Object handlerIdentity) {
            synchronized (monitor) {
                return !closed && !released && active == this && owner == handlerIdentity;
            }
        }

        /**
         * Value-based logical authority check for a scheduler execution.
         * Physical Java thread identity is intentionally irrelevant.
         */
        public boolean heldBy(ExecutionContext execution) {
            Objects.requireNonNull(execution, "execution");
            synchronized (monitor) {
                return !closed && !released && active == this &&
                        sameExecutionAuthority(executionOwner, execution);
            }
        }

        @Override public void close() {
            synchronized (monitor) {
                if (released) return;
                released = true;
                if (active != this) {
                    throw new IllegalStateException("actor state loan ownership corrupted");
                }
                active = null;
                owners.remove(owner);
                promoteLocked();
            }
            publish();
            maybeWakeRetirement();
        }
    }

    @Override public void close() {
        ArrayDeque<Pending> abandoned;
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            abandoned = new ArrayDeque<>(pending);
            pending.clear();
            for (Pending waiter : abandoned) owners.remove(waiter.owner);
            failingPendingCallbacks = !abandoned.isEmpty();
        }
        Throwable firstFailure = null;
        try {
            for (Pending waiter : abandoned) {
                try {
                    waiter.future.failFromRuntime(
                            new IllegalStateException("actor state loans closed"));
                } catch (Throwable callbackFailure) {
                    if (firstFailure == null) firstFailure = callbackFailure;
                    else firstFailure.addSuppressed(callbackFailure);
                }
            }
        } finally {
            synchronized (monitor) { failingPendingCallbacks = false; }
            maybeWakeRetirement();
        }
        if (firstFailure instanceof Error fatal) throw fatal;
        if (firstFailure instanceof RuntimeException failure) throw failure;
        if (firstFailure != null) {
            throw new IllegalStateException("state loan close completion failed", firstFailure);
        }
        // The active loan stays physically held until its handler's
        // finally/cleanup closes it. Closing only fences further state access.
    }
}
