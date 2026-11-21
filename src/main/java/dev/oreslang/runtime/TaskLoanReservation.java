package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Compiler/runtime-only bridge binding one borrowed Task's execution authority
 * to one exact structured scope and one exact child Future.
 *
 * <p>The reservation is intentionally package-private. Guest code cannot mint
 * one, and cannot turn cancellation or Future settlement into memory-release
 * authority. Retirement is accepted only from the exact scope epoch that
 * prepared the reservation and, after admission, the exact child identity that
 * was bound to it. The captured ExecutionPermit generation must still be live
 * immediately before the trusted retirement action runs.</p>
 */
final class TaskLoanReservation<R, A extends ExecutionPermit.AccessSet<A>> {
    interface Retirement<R, A extends ExecutionPermit.AccessSet<A>> {
        void retireNeverStarted(ExecutionPermit<R, A> permit);
        void retireAfterPhysicalQuiescence(ExecutionPermit<R, A> permit);
    }

    private enum State {
        PREPARED,
        BOUND,
        RETIRING,
        RETIRED,
        FAILED
    }

    private final OresTaskScope scope;
    private final Object scopeEpoch;
    private final ExecutionPermit<R, A> permit;
    private final long permitGeneration;
    private final Retirement<R, A> retirement;
    private State state = State.PREPARED;
    private OresFuture<?> child;

    private TaskLoanReservation(
            OresTaskScope scope,
            ExecutionPermit<R, A> permit,
            Retirement<R, A> retirement) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.scopeEpoch = Objects.requireNonNull(
                scope.loanScopeEpochIdentity(), "scope epoch");
        this.permit = Objects.requireNonNull(permit, "permit");
        this.permitGeneration = permit.generation();
        this.retirement = Objects.requireNonNull(retirement, "retirement");
        requirePermitLive();
    }

    static <R, A extends ExecutionPermit.AccessSet<A>> TaskLoanReservation<R, A> prepare(
            OresTaskScope scope,
            ExecutionPermit<R, A> permit,
            Retirement<R, A> retirement) {
        return new TaskLoanReservation<>(scope, permit, retirement);
    }

    synchronized void bind(OresTaskScope actualScope, OresFuture<?> actualChild) {
        requireExactScope(actualScope);
        Objects.requireNonNull(actualChild, "child");
        if (state != State.PREPARED) {
            throw new IllegalStateException("Task loan reservation cannot bind from " + state);
        }
        requirePermitLive();
        child = actualChild;
        state = State.BOUND;
    }

    synchronized void retireNeverStarted(OresTaskScope actualScope) {
        requireExactScope(actualScope);
        if (state != State.PREPARED) {
            throw new IllegalStateException(
                    "never-started retirement requires a prepared reservation");
        }
        retire(true);
    }

    synchronized void retireAfterPhysicalQuiescence(
            OresTaskScope actualScope,
            OresFuture<?> actualChild) {
        requireExactScope(actualScope);
        Objects.requireNonNull(actualChild, "child");
        if (state != State.BOUND) {
            throw new IllegalStateException(
                    "physical retirement requires a bound reservation");
        }
        if (actualChild != child) {
            throw new IllegalArgumentException(
                    "Task loan retirement child identity mismatch");
        }
        retire(false);
    }

    synchronized boolean isRetired() {
        return state == State.RETIRED;
    }

    private void retire(boolean neverStarted) {
        state = State.RETIRING;
        try {
            requirePermitLive();
            if (neverStarted) {
                retirement.retireNeverStarted(permit);
            } else {
                retirement.retireAfterPhysicalQuiescence(permit);
            }
            state = State.RETIRED;
        } catch (RuntimeException | Error failure) {
            state = State.FAILED;
            throw failure;
        }
    }

    private void requireExactScope(OresTaskScope actualScope) {
        Objects.requireNonNull(actualScope, "scope");
        if (actualScope != scope
                || actualScope.loanScopeEpochIdentity() != scopeEpoch) {
            throw new IllegalArgumentException(
                    "Task loan reservation scope epoch mismatch");
        }
    }

    private void requirePermitLive() {
        if (permit.generation() != permitGeneration || !permit.isLive()) {
            throw new IllegalStateException(
                    "Task loan reservation permit generation is stale or consumed");
        }
    }
}
