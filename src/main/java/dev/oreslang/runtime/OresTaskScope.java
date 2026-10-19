package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
    }

    private final OresScheduler scheduler;
    private final Object lock = new Object();
    private final Set<Child> children =
            Collections.newSetFromMap(new IdentityHashMap<>());
    // Runtime barrier: cancellation must not let a caller mistake logical
    // settlement for physical child quiescence.
    private final OresFuture<Void> drained =
            new OresFuture<>(() -> false, () -> { });
    private State state = State.OPEN;

    public OresTaskScope(OresScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Start one child Task owned by this scope.
     *
     * <p>The scope keeps the child live until OresScheduler's trusted
     * onQuiescent callback fires after the final carrier turn has unwound.</p>
     */
    public <T> OresFuture<T> fork(OresScheduler.Task<T> task) {
        Objects.requireNonNull(task, "task");
        synchronized (lock) {
            if (state != State.OPEN) {
                throw new IllegalStateException("Task scope is already sealed");
            }

            Child child = new Child();
            children.add(child);
            try {
                OresFuture<T> future =
                        scheduler.start(task, () -> childQuiescent(child));
                child.future = future;
                return future;
            } catch (RuntimeException | Error failure) {
                // Admission can fail before TaskRunner ownership exists, in which
                // case no onQuiescent callback will arrive.
                children.remove(child);
                throw failure;
            }
        }
    }

    /**
     * Seal the scope against new children and complete only after every admitted
     * child has physically quiesced. Existing children are not cancelled.
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
        if (complete) drained.completeFromRuntime(null);
        return drained;
    }

    /**
     * Seal the scope, request cancellation of all remaining children, and complete
     * only after scheduler-confirmed physical quiescence of every child.
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
                    OresFuture<?> future = child.future;
                    if (future != null) snapshot.add(future);
                }
                cancel = List.copyOf(snapshot);
            }
            if (children.isEmpty()) {
                state = State.CLOSED;
                complete = true;
            }
        }

        // Never run Future cancellation hooks or completion subscribers while
        // holding the scope lock. A queued/never-started child may report
        // quiescence synchronously from its cancellation hook.
        for (OresFuture<?> child : cancel) {
            child.cancel(false);
        }
        if (complete) drained.completeFromRuntime(null);
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
        boolean complete = false;
        synchronized (lock) {
            if (!children.remove(child)) return;
            if (state != State.OPEN && children.isEmpty() && state != State.CLOSED) {
                state = State.CLOSED;
                complete = true;
            }
        }
        if (complete) drained.completeFromRuntime(null);
    }
}
