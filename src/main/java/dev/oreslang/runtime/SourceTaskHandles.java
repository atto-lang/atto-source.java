package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Links the Future returned by a source {@code task { }} block to the
 * {@link TaskHandle} that owns it.
 *
 * <p>Only Futures registered here expose {@code cancel}, {@code cancel_with},
 * {@code is_cancel_requested} and {@code get_done} to Oreslang code. Any other
 * Future (I/O, timers, channel or actor results) is a borrowed producer and
 * cannot be cancelled through an observer.</p>
 *
 * <p>The capability is attached directly to the creating Future, not kept
 * in a global registry. The Future/handle cycle is GC-collectible after all
 * external references and scheduler work disappear; retaining a Future keeps
 * its legitimate task-control capability available.</p>
 */
public final class SourceTaskHandles {

    private static final OresSymbol SUCCESS = OresSymbol.process("success");
    private static final OresSymbol FAILED = OresSymbol.process("failed");
    private static final OresSymbol PANICKED = OresSymbol.process("panicked");
    private static final OresSymbol CANCELLED = OresSymbol.process("cancelled");
    private static final OresSymbol CLEANUP_FAILED = OresSymbol.process("cleanup_failed");

    private SourceTaskHandles() { }

    /** Called by the source task launcher after successful admission. */
    public static <T> OresFuture<T> register(TaskHandle<T> handle) {
        Objects.requireNonNull(handle, "handle");
        OresFuture<T> result = handle.result();
        result.bindSourceTaskHandle(handle);
        return result;
    }

    /** The owning handle, or null when the Future was not made by {@code task { }}. */
    public static TaskHandle<?> lookup(OresFuture<?> future) {
        return future == null ? null : future.sourceTaskHandle();
    }

    /**
     * {@code await t.get_done()}: settles only after the task and its children
     * have physically stopped and released their locks/leases. Never throws;
     * the outcome is reported as a Symbol.
     */
    public static OresFuture<Object> outcomeSymbol(TaskHandle<?> handle) {
        Objects.requireNonNull(handle, "handle");
        OresFuture<Object> symbol = new OresFuture<>();
        handle.getDone().whenCompleteRuntime((outcome, failure) -> {
            if (failure != null) {
                symbol.completeFromRuntime(classifyFailure(OresFuture.unwrap(failure)));
            } else if (outcome instanceof TaskHandle.Success<?>) {
                symbol.completeFromRuntime(SUCCESS);
            } else if (outcome instanceof TaskHandle.Cancelled<?>) {
                symbol.completeFromRuntime(CANCELLED);
            } else if (outcome instanceof TaskHandle.Failed<?> failed) {
                symbol.completeFromRuntime(classifyFailure(failed.failure()));
            } else {
                symbol.completeFromRuntime(FAILED);
            }
        });
        return symbol;
    }

    /** Longest accepted timeout: one day. Longer waits are almost always bugs. */
    static final long MAX_TIMEOUT_MILLIS = 86_400_000L;

    private static final java.util.concurrent.ScheduledThreadPoolExecutor TIMEOUTS =
            createTimeoutTimer();

    private static java.util.concurrent.ScheduledThreadPoolExecutor createTimeoutTimer() {
        java.util.concurrent.ScheduledThreadPoolExecutor timer =
                new java.util.concurrent.ScheduledThreadPoolExecutor(1,
                        runnable -> Thread.ofPlatform().daemon(true)
                                .name("ores-task-timeout").unstarted(runnable));
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    /**
     * Cancel {@code handle} with reason {@code timeout} after {@code millis},
     * unless it has finished by then. The timer callback only requests
     * cancellation; the task unwinds its defers and guards on its own
     * scheduler. Returns false when the task has already finished.
     */
    public static boolean cancelAfter(TaskHandle<?> handle, long millis) {
        Objects.requireNonNull(handle, "handle");
        if (millis < 0 || millis > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(
                    "timeout must be between 0 and " + MAX_TIMEOUT_MILLIS + " ms");
        }
        if (handle.getDone().isDone() || handle.result().isDone()) return false;
        java.util.concurrent.ScheduledFuture<?> timer = TIMEOUTS.schedule(
                () -> handle.cancel("timeout"),
                millis, java.util.concurrent.TimeUnit.MILLISECONDS);
        // Drop the timer as soon as the task stops, so idle timers never pile up.
        handle.result().whenCompleteRuntime((value, failure) -> timer.cancel(false));
        return true;
    }

    /**
     * {@code task_cancel_requested()} (Kotlin {@code isActive} inverted): true
     * when the task block currently running has been asked to cancel. Lets
     * long work stop early on its own terms; cancellation is still delivered
     * at the next suspension point or loop back-edge regardless.
     */
    public static boolean currentTaskCancelRequested() {
        return TaskHandle.currentCancellationRequested();
    }

    /**
     * {@code Future.first([...])} (Loom {@code ShutdownOnSuccess}): settles with
     * the first child to settle, like {@code race}, then cancels every losing
     * child that is an owned {@code task { }} handle. Borrowed Futures are
     * only unsubscribed, never cancelled.
     */
    public static OresFuture<Object> first(java.util.List<?> children) {
        Objects.requireNonNull(children, "children");
        // Take a single authority snapshot BEFORE subscribing to completions.
        // OresFutures.race internally snapshots its inputs, but its snapshot
        // must not diverge from the list used below to cancel owned losers.
        // A mutable caller list otherwise permits canceling a later-added
        // unrelated task, or silently omitting an original losing task.
        if (children.size() > OresFutures.MAX_FAN_IN) {
            throw new IllegalArgumentException(
                    "Future.first exceeds maximum fan-in " + OresFutures.MAX_FAN_IN);
        }
        java.util.List<?> snapshot = java.util.List.copyOf(children);
        OresFuture<Object> winner = OresFutures.race(snapshot);
        winner.whenCompleteRuntime((value, failure) -> {
            for (Object child : snapshot) {
                if (child instanceof OresFuture<?> future && !future.isDone()) {
                    TaskHandle<?> loser = lookup(future);
                    if (loser != null) loser.cancel("lost Future.first");
                }
            }
        });
        return winner;
    }

    /** {@code task scope deadline(ms)}: cancel the scope's children on expiry. */
    public static java.util.concurrent.ScheduledFuture<?> cancelScopeAfter(
            TaskScope scope, long millis) {
        Objects.requireNonNull(scope, "scope");
        if (millis < 0 || millis > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(
                    "task scope deadline must be between 0 and " + MAX_TIMEOUT_MILLIS + " ms");
        }
        return TIMEOUTS.schedule(() -> scope.cancel("timeout"),
                millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static OresSymbol classifyFailure(Throwable failure) {
        if (failure instanceof TaskOwnedLeases.CleanupFailure
                || failure instanceof OresScheduler.CleanupIncompleteError) {
            return CLEANUP_FAILED;
        }
        if (failure instanceof UntrappableTaskFailure) return PANICKED;
        return FAILED;
    }
}
