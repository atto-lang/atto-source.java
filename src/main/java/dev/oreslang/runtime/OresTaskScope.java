package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

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
        CLOSED
    }

    private static final class Child {
        private volatile OresFuture<?> future;
        private Runnable retireAfterPhysicalDone = () -> { };
        // Guarded by the enclosing scope lock.
        private boolean quiescent;
        private boolean terminal;
        private boolean retirementClaimed;
    }

    private final OresScheduler scheduler;
    private final Consumer<OresTaskScope> onClosed;
    private final Object lock = new Object();
    private final Set<Child> children =
            Collections.newSetFromMap(new IdentityHashMap<>());
    // Runtime barrier: it is intentionally non-cancellable. Cancelling the
    // barrier itself must never manufacture a false reclamation witness.
    private final OresFuture<Void> drained =
            new OresFuture<>(() -> false, () -> { });
    private State state = State.OPEN;

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
        return forkCompilerAdmitted(task, () -> { });
    }

    /**
     * Compiler-only ownership retirement hook. It is not a Future-settlement
     * callback: retirement waits for both scheduler quiescence and irrevocable
     * child terminal authority.
     */
    public <T> OresFuture<T> forkCompilerAdmitted(
            OresScheduler.Task<T> task,
            Runnable retireAfterPhysicalDone) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(
                retireAfterPhysicalDone, "physical Task retirement");
        synchronized (lock) {
            if (state != State.OPEN) {
                throw new IllegalStateException("Task scope is already sealed");
            }

            Child child = new Child();
            child.retireAfterPhysicalDone = retireAfterPhysicalDone;
            children.add(child);
            final OresFuture<T> future;
            try {
                future = scheduler.start(
                        task,
                        () -> childQuiescent(child),
                        () -> childTerminal(child));
            } catch (RuntimeException | Error failure) {
                children.remove(child);
                try {
                    retireAfterPhysicalDone.run();
                } catch (RuntimeException | Error retirementFailure) {
                    failure.addSuppressed(retirementFailure);
                }
                throw failure;
            }

            child.future = future;
            return future;
        }
    }

    /**
     * Seal the scope against new children and complete only after every admitted
     * child is terminal and physically quiescent. Existing children are not cancelled.
     */
    public OresFuture<Void> quiesce() {
        boolean complete = false;
        synchronized (lock) {
            if (state == State.OPEN) {
                state = State.QUIESCING;
            }
            if (state != State.OPEN && children.isEmpty() && state != State.CLOSED) {
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
            if (state != State.CANCELLING) {
                state = State.CANCELLING;
                ArrayList<OresFuture<?>> snapshot = new ArrayList<>(children.size());
                for (Child child : children) {
                    // fork() holds the same lock through start + Future binding,
                    // so every child visible here has a handle.
                    snapshot.add(Objects.requireNonNull(
                            child.future, "Task scope child missing Future"));
                }
                cancel = List.copyOf(snapshot);
            }
            if (children.isEmpty()) {
                state = State.CLOSED;
                complete = true;
            }
        }

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
        if (!claimed.isEmpty()) {
            List<OresFuture.RuntimeCancellation<?>> fallback = new ArrayList<>();
            // Ordinary Future subscribers are not part of the structured
            // lifetime proof. After every child is revoked, publish each
            // Future independently so a blocking subscriber on child A cannot
            // pin the parent carrier or starve notifications for child B.
            for (OresFuture.RuntimeCancellation<?> cancellation : claimed) {
                try {
                    Thread.ofVirtual()
                            .name("ores-task-scope-cancel-publish")
                            .start(cancellation::publish);
                } catch (RuntimeException | Error unavailable) {
                    fallback.add(cancellation);
                }
            }
            // Attempt every asynchronous publication before a synchronous
            // fallback can block. Revocation is already complete for all
            // children, so this path cannot weaken the reclamation invariant.
            for (OresFuture.RuntimeCancellation<?> cancellation : fallback) {
                cancellation.publish();
            }
        }

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

    private void childQuiescent(Child child) {
        synchronized (lock) {
            child.quiescent = true;
        }
        retireChildIfReady(child);
    }

    private void childTerminal(Child child) {
        synchronized (lock) {
            child.terminal = true;
        }
        retireChildIfReady(child);
    }

    private void retireChildIfReady(Child child) {
        Runnable retirement = null;
        synchronized (lock) {
            if (child.quiescent && child.terminal
                    && !child.retirementClaimed
                    && children.contains(child)) {
                child.retirementClaimed = true;
                retirement = child.retireAfterPhysicalDone;
            }
        }
        if (retirement == null) return;

        try {
            retirement.run();
        } catch (RuntimeException | Error failure) {
            java.util.ArrayList<OresFuture<?>> cancel =
                    new java.util.ArrayList<>();
            synchronized (lock) {
                if (state != State.CLOSED) {
                    state = State.CANCELLING;
                }
                for (Child remaining : children) {
                    if (remaining.future != null) {
                        cancel.add(remaining.future);
                    }
                }
            }
            // Fail closed: never remove this child or publish a successful
            // scope-drain witness when compiler-owned retirement failed.
            drained.failFromRuntime(failure);
            for (OresFuture<?> future : cancel) {
                future.cancel(false);
            }
            return;
        }

        boolean complete = false;
        synchronized (lock) {
            if (!children.remove(child)) return;
            if (state != State.OPEN
                    && children.isEmpty()
                    && state != State.CLOSED) {
                state = State.CLOSED;
                complete = true;
            }
        }
        if (complete) completeDrain();
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
