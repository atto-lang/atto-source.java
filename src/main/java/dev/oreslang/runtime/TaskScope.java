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
    // Prevent the last child from publishing a successful drain while
    // lexical close hooks are still running and may themselves fail.
    private int closeHookDispatches;
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
        Registration[] admitted;
        synchronized (this) {
            closing = true;
            closeHookDispatches++;
            admitted = children.toArray(new Registration[0]);
        }
        try {
            requestCloseHooks(admitted);
        } finally {
            finishCloseHookDispatch();
        }
        return drained;
    }

    /** Observe the internal barrier without closing this lexical scope. */
    public OresFuture<Void> drained() {
        return drained;
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
            closeHookDispatches++;
            if (firstFailure == null) {
                firstFailure = new java.util.concurrent.CancellationException(
                        "enclosing scoped Task was cancelled");
            }
            admitted = children.toArray(new Registration[0]);
        }

        try {
            requestCloseHooks(admitted);
        } finally {
            finishCloseHookDispatch();
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

    /**
     * A faulty resource close callback must neither bypass the remaining
     * callbacks nor publish a false successful scope join. Callbacks run
     * outside the scope lock, and all failures reach the final join receipt.
     */
    private void requestCloseHooks(Registration[] admitted) {
        for (Registration ticket : admitted) {
            try {
                ticket.requestScopeClose();
            } catch (Throwable failure) {
                synchronized (this) {
                    if (firstFailure == null) firstFailure = failure;
                    else if (firstFailure != failure) firstFailure.addSuppressed(failure);
                }
            }
        }
    }

    private void finishCloseHookDispatch() {
        synchronized (this) {
            closeHookDispatches--;
        }
        publishIfReady();
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
            if (published || !closing || active != 0 || closeHookDispatches != 0) return;
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
        private Runnable scopeCloseHook;
        private boolean scopeCloseRequested;
        private boolean scopeCloseHookRun;

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
         * Install a host-only close signal for a resource-like structured
         * child. The hook is requested at most once when the lexical scope
         * begins normal close or cancellation. It must not run guest code on
         * the caller thread and should return without waiting for retirement.
         */
        public void onScopeClose(Runnable hook) {
            Objects.requireNonNull(hook, "scope close hook");
            boolean runNow = false;
            synchronized (this) {
                if (scopeCloseHook != null) {
                    throw new IllegalStateException(
                            "Task scope ticket already has a close hook");
                }
                scopeCloseHook = hook;
                if (scopeCloseRequested && !scopeCloseHookRun) {
                    scopeCloseHookRun = true;
                    runNow = true;
                }
            }
            if (runNow) hook.run();
        }

        private void requestScopeClose() {
            Runnable hook = null;
            synchronized (this) {
                scopeCloseRequested = true;
                if (scopeCloseHook != null && !scopeCloseHookRun) {
                    scopeCloseHookRun = true;
                    hook = scopeCloseHook;
                }
            }
            if (hook != null) hook.run();
        }

        /**
         * Retire a lazy structured child that was reserved but was closed
         * before its producer acquired scheduler Future identity.
         */
        public void retireUnstarted() {
            synchronized (this) {
                if (notified) return;
                if (attached || beganExecution || terminalFuture != null) {
                    throw new IllegalStateException(
                            "started Task scope ticket cannot retire as unstarted");
                }
                terminal = true;
                quiescent = true;
            }
            notifyIfReady();
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
            terminalReady(future, false);
        }

        /**
         * Resource-like structured children may deliberately cancel their
         * private backing Future as part of a successful close. The exact
         * Future identity still supplies terminal authority; only the logical
         * cancellation failure is suppressed. Physical quiescence remains
         * mandatory.
         */
        public void terminalReadyAsSuccessfulClose(OresFuture<?> future) {
            terminalReady(future, true);
        }

        private void terminalReady(
                OresFuture<?> future,
                boolean successfulClose) {
            Objects.requireNonNull(future, "terminal Future");
            Object terminalState = future.runtimeTerminalStateOrNull();
            if (terminalState == null) {
                throw new IllegalStateException(
                        "Task scope received terminal hook before terminal authority");
            }
            Throwable cause = successfulClose
                    ? null
                    : OresFuture.runtimeTerminalFailure(terminalState);
            synchronized (this) {
                if (notified) {
                    if (terminalFuture == future) return;
                    throw new IllegalStateException(
                            "retired Task scope ticket received a different Future");
                }
                // Scheduler terminal hooks run before ordinary Future waiters.
                // Treat a repeated witness from the same exact Future as
                // idempotent even before physical quiescence, so a fallback
                // completion waiter cannot replace an already-classified
                // successful resource close with CancellationException.
                if (terminal && terminalFuture == future) return;
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
