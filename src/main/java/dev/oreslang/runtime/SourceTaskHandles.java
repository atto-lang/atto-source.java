package dev.oreslang.runtime;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Links the Future returned by a source {@code task { }} block to the
 * {@link TaskHandle} that owns it.
 *
 * <p>Only Futures registered here expose {@code cancel}, {@code cancel_with},
 * {@code is_cancel_requested} and {@code get_done} to Oreslang code. Any other
 * Future (I/O, timers, channel or actor results) is a borrowed producer and
 * cannot be cancelled through an observer.</p>
 *
 * <p>Entries are weak: a handle lives only as long as its Future is reachable.
 * The TaskScope or scheduler keeps the task itself alive while it runs.</p>
 */
public final class SourceTaskHandles {
    private static final Map<OresFuture<?>, TaskHandle<?>> HANDLES =
            Collections.synchronizedMap(new WeakHashMap<>());

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
        HANDLES.put(result, handle);
        return result;
    }

    /** The owning handle, or null when the Future was not made by {@code task { }}. */
    public static TaskHandle<?> lookup(OresFuture<?> future) {
        return HANDLES.get(future);
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

    private static OresSymbol classifyFailure(Throwable failure) {
        if (failure instanceof TaskOwnedLeases.CleanupFailure
                || failure instanceof OresScheduler.CleanupIncompleteError) {
            return CLEANUP_FAILED;
        }
        if (failure instanceof UntrappableTaskFailure) return PANICKED;
        return FAILED;
    }
}
