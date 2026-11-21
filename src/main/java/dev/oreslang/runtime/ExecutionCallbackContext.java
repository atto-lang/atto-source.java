package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Immutable causal provenance captured when Oreslang registers an escaping host callback.
 *
 * <p>This is deliberately <strong>not</strong> an execution/admission capability. Installing a
 * callback context never changes {@link OresScheduler#currentExecutionContext()}, never grants
 * actor-state or allocation authority, and never authorizes guest entry. It exists so host-only
 * completion bookkeeping and tracing can retain the originating logical lineage across arbitrary
 * producer threads.</p>
 */
public final class ExecutionCallbackContext {
    public enum Boundary {
        HOST_CALLBACK,
        IO,
        TIMER,
        AWAIT,
        ACTOR_MESSAGE,
        SUPERVISOR_CONTROL,
        TASK_JOIN,
        SPAWN
    }

    private static final ThreadLocal<ExecutionCallbackContext> CURRENT = new ThreadLocal<>();

    private final ExecutionContext.ExecutionId sourceExecution;
    private final long sourceDispatchId;
    private final long parentExecution;
    private final long continuation;
    private final Boundary boundary;

    private ExecutionCallbackContext(
            ExecutionContext.ExecutionId sourceExecution,
            long sourceDispatchId,
            long parentExecution,
            long continuation,
            Boundary boundary) {
        this.sourceExecution = sourceExecution;
        if (sourceDispatchId < 0 || parentExecution < 0 || continuation < 0) {
            throw new IllegalArgumentException("callback provenance identifiers cannot be negative");
        }
        this.sourceDispatchId = sourceDispatchId;
        this.parentExecution = parentExecution;
        this.continuation = continuation;
        this.boundary = Objects.requireNonNull(boundary, "boundary");
    }

    /**
     * Capture the current Oreslang logical lineage without capturing guest execution authority.
     * Returns {@code null} when called outside both active guest execution and an existing callback
     * provenance scope.
     */
    static ExecutionCallbackContext captureCurrent(Boundary boundary) {
        Objects.requireNonNull(boundary, "boundary");
        ExecutionContext active = OresScheduler.currentExecutionContext();
        if (active != null) {
            return new ExecutionCallbackContext(
                    active.id(),
                    OresScheduler.currentDispatchId(),
                    active.parentExecution(),
                    active.continuation(),
                    boundary);
        }

        ExecutionCallbackContext inherited = CURRENT.get();
        if (inherited == null) return null;
        return new ExecutionCallbackContext(
                inherited.sourceExecution,
                inherited.sourceDispatchId,
                inherited.parentExecution,
                inherited.continuation,
                boundary);
    }

    /** Current host-only callback provenance, or {@code null} outside such a scope. */
    public static ExecutionCallbackContext current() {
        return CURRENT.get();
    }

    static void runCaptured(ExecutionCallbackContext captured, Runnable action) {
        Objects.requireNonNull(action, "action");
        if (captured == null) {
            action.run();
            return;
        }

        ExecutionCallbackContext previous = CURRENT.get();
        CURRENT.set(captured);
        try {
            action.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public ExecutionContext.ExecutionId sourceExecution() {
        return sourceExecution;
    }

    public long sourceDispatchId() {
        return sourceDispatchId;
    }

    public long parentExecution() {
        return parentExecution;
    }

    public long continuation() {
        return continuation;
    }

    public Boundary boundary() {
        return boundary;
    }

    @Override
    public String toString() {
        return "ExecutionCallbackContext[source=" + sourceExecution
                + ", dispatch=" + sourceDispatchId
                + ", boundary=" + boundary + "]";
    }
}
