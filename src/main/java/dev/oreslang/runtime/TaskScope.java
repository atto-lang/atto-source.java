package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Structured Task barrier. A cancelled Future alone is NOT a quiescence
 * witness. Each child holds its ticket until both terminal authority and the
 * scheduler's post-carrier physical-quiescence callback have occurred.
 */
public final class TaskScope {
    private final OresFuture<Void> drained;
    private int active;
    private boolean closing;
    private boolean published;
    private Throwable firstFailure;
    // One-way cancellation seal. Volatile publication lets Registration.attach()
    // observe cancellation without acquiring the scope monitor while it holds
    // the registration monitor; reserve(parent) may take those locks in the
    // opposite order while closing, so nested acquisition here would deadlock.
    private volatile boolean cancellationRequested;
    private final java.util.Set<Registration> children =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    public TaskScope() {
        // A join is an internal lifetime gate, never a cancellable user Future.
        // Cancelling the join itself could otherwise forge a reclamation witness.
        drained = new OresFuture<>(() -> false, () -> { });
    }

    public synchronized Registration reserve() {
        return reserve(null);
    }

    /**
     * Root admissions are permitted only while the lexical scope is open.
     * After close(), a child may admit descendants only while holding a live
     * ticket to this exact scope. The ticket is runtime-only authority.
     */
    public synchronized Registration reserve(Registration parent) {
        // Cancellation is stronger than ordinary close: once the cancellation
        // snapshot is published, no descendant may enter behind it even if a
        // previously executing parent still holds an otherwise-live permit.
        if (cancellationRequested) {
            throw new IllegalStateException("Task spawned into cancelled scope");
        }
        if (closing && (parent == null || !parent.maySpawnInto(this))) {
            throw new IllegalStateException(
                    "Task spawned into closing scope without a live child permit");
        }
        if (active == Integer.MAX_VALUE) {
            throw new IllegalStateException("Task scope admission exhausted");
        }
        active++;
        Registration ticket = new Registration(this);
        children.add(ticket);
        return ticket;
    }

    public OresFuture<Void> close() {
        synchronized (this) {
            closing = true;
        }
        publishIfReady();
        return drained;
    }

    /** Observe the internal barrier without closing this lexical scope. */
    public OresFuture<Void> drained() {
        return drained;
    }

    /**
     * Compiler-only retirement notification, including a failed child outcome.
     * This observes physical drain; it neither closes nor cancels the scope.
     * The callback must only retire trusted runtime bookkeeping, never run guest code.
     */
    public void afterPhysicalDrain(Runnable retirement) {
        Objects.requireNonNull(retirement, "retirement");
        drained.whenCompleteRuntime((ignored, failure) -> retirement.run());
    }

    /**
     * Revoke all admitted children without allowing one blocking Future
     * subscriber to prevent revocation of its siblings. Claims happen first,
     * revocation second, publication last.
     */
    public OresFuture<Void> cancelAndDrain() {
        Registration[] admitted;
        synchronized (this) {
            closing = true;
            cancellationRequested = true;
            if (firstFailure == null) {
                firstFailure = new java.util.concurrent.CancellationException(
                        "enclosing scoped Task was cancelled");
            }
            admitted = children.toArray(new Registration[0]);
        }

        ArrayList<OresFuture.RuntimeCancellation<?>> claimed =
                new ArrayList<>(admitted.length);
        for (Registration ticket : admitted) {
            OresFuture.RuntimeCancellation<?> cancellation =
                    ticket.claimAttachedCancellation();
            if (cancellation != null) claimed.add(cancellation);
        }
        for (OresFuture.RuntimeCancellation<?> cancellation : claimed) {
            cancellation.revoke();
        }

        // All producer/task authority is already revoked. Publish ordinary
        // subscribers independently so a blocked callback cannot pin siblings.
        List<OresFuture.RuntimeCancellation<?>> fallback = new ArrayList<>();
        for (OresFuture.RuntimeCancellation<?> cancellation : claimed) {
            try {
                Thread.ofVirtual()
                        .name("ores-task-scope-cancel-publish")
                        .start(cancellation::publish);
            } catch (RuntimeException | Error unavailable) {
                fallback.add(cancellation);
            }
        }
        for (OresFuture.RuntimeCancellation<?> cancellation : fallback) {
            cancellation.publish();
        }

        publishIfReady();
        return drained;
    }

    private void childDone(Registration ticket, Throwable failure) {
        synchronized (this) {
            if (active == 0) {
                throw new IllegalStateException("Task scope ticket underflow");
            }
            if (failure != null) {
                if (firstFailure == null) firstFailure = failure;
                else if (firstFailure != failure) firstFailure.addSuppressed(failure);
            }
            if (!children.remove(ticket)) {
                throw new IllegalStateException("Task scope ticket retired twice");
            }
            active--;
        }
        publishIfReady();
    }

    private void publishIfReady() {
        Throwable failure;
        synchronized (this) {
            if (published || !closing || active != 0) return;
            published = true;
            failure = firstFailure;
        }
        if (failure != null) drained.failFromRuntime(failure);
        else drained.completeFromRuntime(null);
    }

    public static final class Registration {
        private final TaskScope scope;
        private boolean quiescent;
        private boolean terminal;
        private boolean notified;
        private boolean attached;
        private boolean beganExecution;
        private OresFuture<?> attachedFuture;
        private OresFuture<?> terminalFuture;
        private Throwable failure;

        private Registration(TaskScope scope) {
            this.scope = scope;
        }

        private synchronized boolean maySpawnInto(TaskScope expected) {
            // Cancellation terminal authority revokes descendant admission even
            // if this child's carrier has not physically unwound yet.
            return scope == expected && beganExecution
                    && !terminal && !quiescent && !notified;
        }

        /** Reservation alone never grants late descendant-spawn authority. */
        public synchronized void beganExecution() {
            if (terminal || quiescent || notified) {
                throw new IllegalStateException(
                        "retired scoped Task cannot begin execution");
            }
            beganExecution = true;
        }

        /**
         * Bind the exact scheduler Future to this ticket. Terminal authority may
         * race ahead of this method; if so, it must name this same Future.
         */
        public void attach(OresFuture<?> future) {
            Objects.requireNonNull(future, "child Future");
            if (future == scope.drained) {
                throw new IllegalArgumentException(
                        "Task scope cannot await its own completion barrier");
            }

            boolean cancel;
            synchronized (this) {
                if (attached) {
                    throw new IllegalStateException(
                            "Task scope ticket already attached");
                }
                if (notified) {
                    // A tiny child may publish terminal authority and physical
                    // quiescence before start(...) returns its Future to the
                    // lowering site. Accept that late bind only when the exact
                    // same Future already supplied the terminal witness.
                    if (terminalFuture == future) return;
                    throw new IllegalStateException(
                            "retired Task scope ticket cannot attach a different Future");
                }
                if (terminalFuture != null && terminalFuture != future) {
                    throw new IllegalStateException(
                            "Task scope terminal witness belongs to a different Future");
                }
                attached = true;
                attachedFuture = future;
                // Never acquire the scope monitor while holding this ticket.
                // If cancellation wins before this volatile read, cancel below;
                // if it wins afterwards, cancelAndDrain() snapshots this already-
                // attached Future and claims cancellation itself.
                cancel = scope.cancellationRequested && !terminal;
            }

            // Fallback for host/runtime tests or legacy callers that bind a
            // Future not created with the scheduler terminal-ready hook. Real
            // source Task children receive terminalReady(...) before ordinary
            // subscriber drain; this waiter is therefore redundant there.
            future.whenCompleteRuntime((value, cause) -> terminalReady(future));

            // A cancellation racing start/attach may have missed this child in
            // the initial snapshot. Revoking it here is safe because all older
            // snapshot children were already revoked before publication.
            if (cancel) future.cancel(false);
        }

        /**
         * Scheduler terminal-authority hook. Called after cancellation
         * revocation (when applicable) and before ordinary Future subscribers.
         */
        public void terminalReady(OresFuture<?> future) {
            Objects.requireNonNull(future, "terminal Future");
            Object terminalState = future.runtimeTerminalStateOrNull();
            if (terminalState == null) {
                throw new IllegalStateException(
                        "Task scope received terminal hook before terminal authority");
            }
            Throwable cause = OresFuture.runtimeTerminalFailure(terminalState);
            synchronized (this) {
                if (notified) {
                    // Duplicate terminal publication from the same scheduler
                    // Future is harmless. A different Future, or any Future
                    // after failed admission (terminalFuture == null), is an
                    // authority mismatch and must not be silently ignored.
                    if (terminalFuture == future) return;
                    throw new IllegalStateException(
                            "retired Task scope ticket received a different Future");
                }
                if (attachedFuture != null && attachedFuture != future) {
                    throw new IllegalStateException(
                            "Task scope terminal witness belongs to a different Future");
                }
                if (terminalFuture != null && terminalFuture != future) {
                    throw new IllegalStateException(
                            "Task scope terminal witness changed Future identity");
                }
                terminalFuture = future;
                terminal = true;
                failure = cause;
            }
            notifyIfReady();
        }

        private OresFuture.RuntimeCancellation<?> claimAttachedCancellation() {
            OresFuture<?> future;
            synchronized (this) {
                if (terminal || notified) return null;
                future = attachedFuture;
            }
            return future == null ? null : future.claimCancellationFromRuntime();
        }

        public void quiesced() {
            synchronized (this) {
                if (notified) return;
                quiescent = true;
            }
            notifyIfReady();
        }

        public void failedAdmission(Throwable cause) {
            Objects.requireNonNull(cause, "admission failure");
            synchronized (this) {
                if (notified) return;
                if (attached) {
                    throw new IllegalStateException(
                            "cannot retire an attached child as failed admission", cause);
                }
                failure = cause;
                terminal = true;
                quiescent = true;
            }
            notifyIfReady();
        }

        private void notifyIfReady() {
            Throwable cause;
            synchronized (this) {
                if (notified || !quiescent || !terminal) return;
                notified = true;
                cause = failure;
            }
            scope.childDone(this, cause);
        }
    }
}
