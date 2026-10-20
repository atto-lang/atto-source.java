package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Host-side reference lowering targets for user-created Oreslang Futures.
 *
 * <p>This is an integration seam, not source-language admission of
 * {@code new Future<T>(...)}. Guest lowering must supply an authorized scheduler,
 * enforce ownership/capability checks, and dispatch continuations through that
 * scheduler rather than a native producer thread.</p>
 */
public final class OresFutureConstructors {
    private OresFutureConstructors() { }

    /** One asynchronous producer of T, with automatic completion on return. */
    @FunctionalInterface
    public interface AsyncRun<T> {
        T run() throws Exception;
    }

    /** One callback-based producer; returning does not complete its Future. */
    @FunctionalInterface
    public interface RunCallback<T> {
        void run(OresFuture.Callback<T> callback);
    }

    /**
     * Start an AsyncRun on a runtime-owned carrier and observe its completion.
     * The returned Future is cancellable and uses AsyncRuntime's existing
     * cancellation and scheduler ownership behavior.
     */
    public static <T> OresFuture<T> fromAsyncRun(
            AsyncRuntime runtime, AsyncRun<T> operation) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(operation, "operation");
        return runtime.submit(operation::run);
    }

    /**
     * Invoke a callback registrar on the CURRENT authorized call turn.
     * A completed registrar does not imply a completed Future. All settlement
     * is mediated by OresFuture.fromCallback's single-shot authority.
     *
     * <p>Compiler-generated guest RunCallback bodies must execute on their
     * owning scheduler turn; callers must never invoke this adapter from an
     * arbitrary I/O or host completion callback with guest code.</p>
     */
    public static <T> OresFuture<T> fromRunCallback(RunCallback<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return OresFuture.fromCallback(operation::run);
    }
}
