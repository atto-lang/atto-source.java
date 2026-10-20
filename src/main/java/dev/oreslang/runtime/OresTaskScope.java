package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Trusted runtime primitive for lexical structured Task lifetimes.
 *
 * <p>This is intentionally not a guest-visible ownership capability. The compiler may
 * lower a future scoped-Task construct onto this object only after it has proved capture
 * lifetimes and region access. The scope tracks scheduler-confirmed <em>physical
 * quiescence</em>, not merely Future settlement: cancellation can make a Future terminal
 * while its guest carrier is still unwinding.</p>
 *
 * <p>Scope exit is asynchronous. Blocking a scheduler carrier while waiting for child
 * Tasks can deadlock a one-carrier scheduler, so callers seal the scope and await the
 * returned Future. Normal exit uses {@link #quiesce()}; exceptional/cancelled exit uses
 * {@link #cancelAndQuiesce()}.</p>
 */
public final class OresTaskScope {
    private enum State {
        OPEN,
        QUIESCING,
        CANCELLING,
        POISONED,
        CLOSED
    }

    private static final class Child {
        private final TaskLoanReservation<?, ?> reservation;
        private volatile OresFuture<?> future;
        // Guarded by the enclosing scope lock.
        private boolean quiescent;
        private boolean terminal;
        private boolean retirementStarted;

        private Child(TaskLoanReservation<?, ?> reservation) {
            this.reservation = reservation;
        }
    }

    private final OresScheduler scheduler;
    private final Consumer<OresTaskScope> onClosed;
    private final Object lock = new Object();
    /** Unforgeable identity for reservations minted by this exact scope instance. */
    private final Object loanScopeEpoch = new Object();
    private final Set<Child> children =
            Collections.newSetFromMap(new IdentityHashMap<>());
    // Runtime barrier: it is intentionally non-cancellable. Cancelling the
    // barrier itself must never manufacture a false reclamation witness.
    private final OresFuture<Void> drained =
            new OresFuture<>(() -> false, () -> { });
    private State state = State.OPEN;
    // A loan-retirement failure is not a normal exceptional drain: the memory
    // authority was not proven released. Keep the drain pending forever.
    private Throwable retirementFailure;

    public OresTaskScope(OresScheduler scheduler) {
        this(scheduler, ignored -> { }, ignored -> { });
    }

    OresTaskScope(
            OresScheduler scheduler,
            Consumer<OresTaskScope> registrar,
            Consumer<OresTaskScope> onClosed) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
        Objects.requireNonNull(registrar, "registrar").accept(this);
    }

    /**
     * Open a lexical structured scope owned by the currently executing Task.
     * This is the entry point intended for future guest lowering.
     */
    public static OresTaskScope openCurrent() {
        return OresScheduler.openCurrentTaskScope();
    }

    /**
     * Start one child Task owned by this scope.
     *
     * <p>A child retires from the scope only after both of these independent
     * conditions hold: the scheduler reports physical quiescence, and terminal
     * authority is irrevocably established (including cancellation revocation).
     * Ordinary Future subscriber delivery is deliberately outside this lifetime
     * proof and may finish later.</p>
     */
    public <T> OresFuture<T> fork(OresScheduler.Task<T> task) {
        return forkInternal(task, () -> null);
    }

    /**
     * Compiler/runtime-only borrowed-child admission. Guest code cannot mint an
     * ExecutionPermit or TaskLoanReservation. The reservation is created only
     * after this exact scope is confirmed open, then bound to the exact Future
     * returned by scheduler admission before callbacks may retire the child.
     */
    <T, R, A extends ExecutionPermit.AccessSet<A>> OresFuture<T> forkBorrowed(
            OresScheduler.Task<T> task,
            ExecutionPermit<R, A> permit,
            TaskLoanReservation.Retirement<R, A> retirement) {
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(retirement, "retirement");
        return forkInternal(
                task,
                () -> TaskLoanReservation.prepare(this, permit, retirement));
    }

    private <T> OresFuture<T> forkInternal(
            OresScheduler.Task<T> task,
            Supplier<TaskLoanReservation<?, ?>> reservationFactory) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(reservationFactory, "reservationFactory");

        Child child;
        OresFuture<T> future = null;
        Throwable admissionFailure = null;
        Throwable bindingFailure = null;

        synchronized (lock) {
            if (state != State.OPEN) {
                throw new IllegalStateException("Task scope is already sealed");
            }

            TaskLoanReservation<?, ?> reservation = reservationFactory.get();
            child = new Child(reservation);
            children.add(child);

            try {
                future = scheduler.start(
                        task,
                        () -> childQuiescent(child),
                        () -> childTerminal(child));
            } catch (RuntimeException | Error failure) {
                admissionFailure = failure;
                child.retirementStarted = reservation != null;
                if (reservation == null) {
                    children.remove(child);
                }
            }

            if (future != null) {
                child.future = future;
                if (reservation != null) {
                    try {
                        reservation.bind(this, future);
                    } catch (RuntimeException | Error failure) {
                        // A task now exists, so this is not the never-started
                        // path. Prevent any retirement attempt from releasing
                        // authority and poison the scope after dropping the lock.
                        child.retirementStarted = true;
                        bindingFailure = failure;
                    }
                }
            }
        }

        if (admissionFailure != null) {
            if (child.reservation != null) {
                try {
                    child.reservation.retireNeverStarted(this);
                    finishRetiredChild(child);
                } catch (RuntimeException | Error retirementError) {
                    admissionFailure.addSuppressed(retirementError);
                    poisonRetirement(retirementError);
                }
            }
            throw propagate(admissionFailure);
        }

        if (bindingFailure != null) {
            cancelFutures(List.of(Objects.requireNonNull(future, "bound child Future")));
            poisonRetirement(bindingFailure);
            throw propagate(bindingFailure);
        }

        return Objects.requireNonNull(future, "Task child Future");
    }

    /**
     * Seal the scope against new children and complete only after every admitted
     * child is terminal and physically quiescent. Existing children are not cancelled.
     */
    public OresFuture<Void> quiesce() {
        boolean complete = false;
        synchronized (lock) {
            if (state == State.POISONED || state == State.CLOSED) {
                return drained;
            }
            if (state == State.OPEN) {
                state = State.QUIESCING;
            }
            if (children.isEmpty()) {
                state = State.CLOSED;
                complete = true;
            }
        }
        if (complete) completeDrain();
        return drained;
    }

    /**
     * Seal the scope, request cancellation of all remaining children, and complete
     * only after every child's terminal state and scheduler-confirmed physical
     * quiescence have both been observed.
     *
     * <p>Cancellation is a request for logical termination, not a reclamation
     * witness. A running child's Future can become cancelled before this barrier
     * completes.</p>
     */
    public OresFuture<Void> cancelAndQuiesce() {
        List<OresFuture<?>> cancel = List.of();
        boolean complete = false;
        synchronized (lock) {
            if (state == State.CLOSED) return drained;
            if (state == State.POISONED) {
                cancel = liveChildFuturesLocked();
            } else if (state != State.CANCELLING) {
                state = State.CANCELLING;
                cancel = liveChildFuturesLocked();
            }
            if (state != State.POISONED && children.isEmpty()) {
                state = State.CLOSED;
                complete = true;
            }
        }

        cancelFutures(cancel);

        if (complete) completeDrain();
        return drained;
    }

    public boolean isOpen() {
        synchronized (lock) {
            return state == State.OPEN;
        }
    }

    public int activeChildren() {
        synchronized (lock) {
            return children.size();
        }
    }

    Object loanScopeEpochIdentity() {
        return loanScopeEpoch;
    }

    Throwable retirementFailure() {
        synchronized (lock) {
            return retirementFailure;
        }
    }

    private List<OresFuture<?>> liveChildFuturesLocked() {
        ArrayList<OresFuture<?>> snapshot = new ArrayList<>(children.size());
        for (Child child : children) {
            if (child.future != null) {
                snapshot.add(child.future);
            }
        }
        return List.copyOf(snapshot);
    }

    private void cancelFutures(List<OresFuture<?>> cancel) {
        // Two-phase structured cancellation: first claim every still-pending
        // child, then revoke every claimed child before publishing any ordinary
        // Future subscribers. A blocking subscriber on child A must never
        // prevent revocation of child B.
        ArrayList<OresFuture.RuntimeCancellation<?>> claimed =
                new ArrayList<>(cancel.size());
        for (OresFuture<?> child : cancel) {
            OresFuture.RuntimeCancellation<?> cancellation =
                    child.claimCancellationFromRuntime();
            if (cancellation != null) claimed.add(cancellation);
        }
        for (OresFuture.RuntimeCancellation<?> cancellation : claimed) {
            cancellation.revoke();
        }
        if (claimed.isEmpty()) return;

        List<OresFuture.RuntimeCancellation<?>> fallback = new ArrayList<>();
        // Ordinary Future subscribers are not part of the structured lifetime
        // proof. After every child is revoked, publish each Future independently
        // so a blocking subscriber cannot pin the parent carrier.
        for (OresFuture.RuntimeCancellation<?> cancellation : claimed) {
            try {
                Thread.ofVirtual()
                        .name("ores-task-scope-cancel-publish")
                        .start(cancellation::publish);
            } catch (RuntimeException | Error unavailable) {
                fallback.add(cancellation);
            }
        }
        // Attempt every asynchronous publication before a synchronous fallback
        // can block. Revocation is already complete for all children.
        for (OresFuture.RuntimeCancellation<?> cancellation : fallback) {
            cancellation.publish();
        }
    }

    private void childQuiescent(Child child) {
        childLifecycle(child, true);
    }

    private void childTerminal(Child child) {
        childLifecycle(child, false);
    }

    private void childLifecycle(Child child, boolean quiescent) {
        TaskLoanReservation<?, ?> reservation = null;
        boolean complete = false;

        synchronized (lock) {
            if (quiescent) {
                child.quiescent = true;
            } else {
                child.terminal = true;
            }

            if (!child.quiescent || !child.terminal || child.retirementStarted) {
                return;
            }
            child.retirementStarted = true;

            if (child.reservation == null) {
                if (!children.remove(child)) {
                    throw new IllegalStateException("Task scope child retired twice");
                }
                complete = closeIfDrainedLocked();
            } else {
                reservation = child.reservation;
            }
        }

        if (reservation != null) {
            try {
                reservation.retireAfterPhysicalQuiescence(
                        this,
                        Objects.requireNonNull(child.future, "Task scope child missing Future"));
            } catch (RuntimeException | Error failure) {
                poisonRetirement(failure);
                return;
            }
            complete = finishRetiredChild(child);
        }

        if (complete) completeDrain();
    }

    private boolean finishRetiredChild(Child child) {
        synchronized (lock) {
            if (!children.remove(child)) {
                throw new IllegalStateException("Task scope child retired twice");
            }
            return closeIfDrainedLocked();
        }
    }

    private boolean closeIfDrainedLocked() {
        if (state != State.OPEN
                && state != State.POISONED
                && children.isEmpty()
                && state != State.CLOSED) {
            state = State.CLOSED;
            return true;
        }
        return false;
    }

    private void poisonRetirement(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        final List<OresFuture<?>> cancel;
        synchronized (lock) {
            if (retirementFailure != null) return;
            retirementFailure = failure;
            state = State.POISONED;
            cancel = liveChildFuturesLocked();
        }

        // A failed retirement means memory authority was not proven released.
        // Never complete/fail the drain barrier: an exception could be caught
        // by source code and misused as a reclamation witness. Seal, revoke
        // remaining children, and leave the join slot permanently live.
        cancelFutures(cancel);
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("unexpected checked Task runtime failure", failure);
    }

    private void completeDrain() {
        try {
            onClosed.accept(this);
        } catch (RuntimeException | Error failure) {
            // The drain Future is a lifetime/reclamation witness. Never report
            // successful quiescence if the owning TaskRunner failed to
            // deregister/finalize this scope. Surface the owner failure to the
            // waiter and keep success fail-closed.
            if (!drained.failFromRuntime(failure)) {
                throw new IllegalStateException(
                        "Task scope drain was already settled before owner failure",
                        failure);
            }
            return;
        }
        if (!drained.completeFromRuntime(null)) {
            throw new IllegalStateException(
                    "Task scope drain completed more than once");
        }
    }
}
