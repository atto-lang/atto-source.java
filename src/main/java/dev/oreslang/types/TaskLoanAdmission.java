package dev.oreslang.types;

import dev.oreslang.runtime.OresScheduler;
import dev.oreslang.runtime.OresTask;
import dev.oreslang.runtime.OresTaskScope;

import java.util.Objects;

/**
 * Opaque compiler-issued authority binding one verified Task loan reservation
 * to one exact runtime scope epoch and, once allocated, one exact Task owner.
 *
 * <p>Guest source cannot construct this class. Runtime cancellation/result
 * settlement carries no retirement authority: only the distinct never-started
 * or physical-quiescence lifecycle callbacks may release the verifier ticket.</p>
 */
public final class TaskLoanAdmission
        implements OresTaskScope.CompilerReadLoanRetirement {
    private enum State {
        MINTED,
        SCOPE_BOUND,
        OWNER_BOUND,
        SCHEDULER_BOUND,
        RETIRED
    }

    private final TaskAccessVerifier verifier;
    private final TaskAccessVerifier.Ticket ticket;
    private State state = State.MINTED;
    private OresTaskScope.CompilerScopeEpoch runtimeScopeEpoch;
    private OresTask<?> runtimeOwner;
    private OresScheduler.CompilerTaskHandle<?> schedulerChild;

    TaskLoanAdmission(
            TaskAccessVerifier verifier,
            TaskAccessVerifier.Ticket ticket) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.ticket = Objects.requireNonNull(ticket, "ticket");
        if (verifier.loanKind(ticket)
                != TaskAccessVerifier.LoanKind.SCOPED_READ_BORROW) {
            throw new IllegalArgumentException(
                    "runtime TaskLoanAdmission requires SCOPED_READ_BORROW authority");
        }
    }

    @Override
    public synchronized void bindScope(
            OresTaskScope.CompilerScopeEpoch scopeEpoch) {
        Objects.requireNonNull(scopeEpoch, "runtime scope epoch");
        if (state != State.MINTED) {
            throw new IllegalStateException(
                    "Task loan admission scope may be bound exactly once");
        }
        runtimeScopeEpoch = scopeEpoch;
        state = State.SCOPE_BOUND;
    }

    @Override
    public synchronized void bindChild(
            OresTaskScope.CompilerScopeEpoch scopeEpoch, OresTask<?> child) {
        requireScope(scopeEpoch);
        Objects.requireNonNull(child, "runtime child");
        if (state != State.SCOPE_BOUND) {
            throw new IllegalStateException(
                    "Task loan child may be bound only after exact scope binding");
        }
        runtimeOwner = child;
        state = State.OWNER_BOUND;
    }

    @Override
    public synchronized void bindSchedulerChild(
            OresTaskScope.CompilerScopeEpoch scopeEpoch,
            OresTask<?> child,
            OresScheduler.CompilerTaskHandle<?> exactSchedulerChild) {
        requireScope(scopeEpoch);
        Objects.requireNonNull(child, "runtime owner");
        Objects.requireNonNull(
                exactSchedulerChild, "compiler scheduler child");
        if (state != State.OWNER_BOUND || runtimeOwner != child
                || schedulerChild != null) {
            throw new IllegalArgumentException(
                    "scheduler-child binding requires the exact scope-owned Task owner");
        }
        schedulerChild = exactSchedulerChild;
        state = State.SCHEDULER_BOUND;
    }

    @Override
    public synchronized void retireBeforeChildBound(
            OresTaskScope.CompilerScopeEpoch scopeEpoch) {
        requireScope(scopeEpoch);
        if (state != State.SCOPE_BOUND
                || runtimeOwner != null || schedulerChild != null) {
            throw new IllegalStateException(
                    "pre-child Task loan retirement requires scope-only binding");
        }
        verifier.retireNeverStarted(ticket);
        state = State.RETIRED;
    }

    @Override
    public synchronized void retireBeforeSchedulerChildBound(
            OresTaskScope.CompilerScopeEpoch scopeEpoch,
            OresTask<?> child) {
        requireScope(scopeEpoch);
        Objects.requireNonNull(child, "runtime owner");
        if (state != State.OWNER_BOUND
                || runtimeOwner != child || schedulerChild != null) {
            throw new IllegalArgumentException(
                    "pre-scheduler Task loan retirement requires the exact owner and no scheduler child");
        }
        verifier.retireNeverStarted(ticket);
        state = State.RETIRED;
        runtimeOwner = null;
    }

    @Override
    public synchronized void retireNeverStarted(
            OresTaskScope.CompilerScopeEpoch scopeEpoch,
            OresTask<?> child,
            OresScheduler.CompilerTaskHandle<?> exactSchedulerChild) {
        requireExactSchedulerChild(
                scopeEpoch, child, exactSchedulerChild, "never-started");
        verifier.retireNeverStarted(ticket);
        state = State.RETIRED;
        runtimeOwner = null;
        schedulerChild = null;
    }

    @Override
    public synchronized void retireAfterPhysicalQuiescence(
            OresTaskScope.CompilerScopeEpoch scopeEpoch,
            OresTask<?> child,
            OresScheduler.CompilerTaskHandle<?> exactSchedulerChild) {
        requireExactSchedulerChild(
                scopeEpoch, child, exactSchedulerChild, "physical");
        verifier.retireAfterPhysicalQuiescence(ticket);
        state = State.RETIRED;
        runtimeOwner = null;
        schedulerChild = null;
    }

    synchronized boolean retiredForTesting() {
        return state == State.RETIRED;
    }

    synchronized boolean schedulerBoundForTesting() {
        return state == State.SCHEDULER_BOUND && schedulerChild != null;
    }

    synchronized boolean scopedReadBorrowForTesting() {
        return verifier.loanKind(ticket)
                == TaskAccessVerifier.LoanKind.SCOPED_READ_BORROW;
    }

    private void requireExactSchedulerChild(
            OresTaskScope.CompilerScopeEpoch scopeEpoch,
            OresTask<?> child,
            OresScheduler.CompilerTaskHandle<?> exactSchedulerChild,
            String edge) {
        requireScope(scopeEpoch);
        Objects.requireNonNull(child, "runtime owner");
        Objects.requireNonNull(
                exactSchedulerChild, "compiler scheduler child");
        if (state != State.SCHEDULER_BOUND
                || runtimeOwner != child
                || schedulerChild != exactSchedulerChild) {
            throw new IllegalArgumentException(
                    edge + " Task loan retirement requires the exact owner and scheduler child");
        }
    }

    private void requireScope(
            OresTaskScope.CompilerScopeEpoch scopeEpoch) {
        Objects.requireNonNull(scopeEpoch, "runtime scope epoch");
        if (runtimeScopeEpoch != scopeEpoch) {
            throw new IllegalArgumentException(
                    "Task loan admission belongs to a different runtime scope epoch");
        }
        if (state == State.MINTED || state == State.RETIRED) {
            throw new IllegalStateException(
                    "Task loan admission is not live for this lifecycle event");
        }
    }
}
