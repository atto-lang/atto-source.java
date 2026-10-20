package dev.oreslang.types;

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
        implements OresTaskScope.CompilerLoanRetirement {
    private enum State {
        MINTED,
        SCOPE_BOUND,
        CHILD_BOUND,
        RETIRED
    }

    private final TaskAccessVerifier verifier;
    private final TaskAccessVerifier.Ticket ticket;
    private State state = State.MINTED;
    private OresTaskScope.CompilerScopeEpoch runtimeScopeEpoch;
    private OresTask<?> runtimeChild;

    TaskLoanAdmission(
            TaskAccessVerifier verifier,
            TaskAccessVerifier.Ticket ticket) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.ticket = Objects.requireNonNull(ticket, "ticket");
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
        runtimeChild = child;
        state = State.CHILD_BOUND;
    }

    @Override
    public synchronized void retireNeverStarted(
            OresTaskScope.CompilerScopeEpoch scopeEpoch) {
        requireScope(scopeEpoch);
        if (state != State.SCOPE_BOUND && state != State.CHILD_BOUND) {
            throw new IllegalStateException(
                    "never-started Task loan retirement is not valid in current state");
        }
        verifier.retireNeverStarted(ticket);
        state = State.RETIRED;
        runtimeChild = null;
    }

    @Override
    public synchronized void retireAfterPhysicalQuiescence(
            OresTaskScope.CompilerScopeEpoch scopeEpoch, OresTask<?> child) {
        requireScope(scopeEpoch);
        Objects.requireNonNull(child, "runtime child");
        if (state != State.CHILD_BOUND || runtimeChild != child) {
            throw new IllegalArgumentException(
                    "physical Task loan retirement requires the exact bound child");
        }
        verifier.retireAfterPhysicalQuiescence(ticket);
        state = State.RETIRED;
        runtimeChild = null;
    }

    synchronized boolean retiredForTesting() {
        return state == State.RETIRED;
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
