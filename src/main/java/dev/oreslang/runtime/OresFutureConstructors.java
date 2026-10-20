package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host-side reference lowering targets for user-created Oreslang Futures.
 *
 * <p>This is an integration seam, not source-language admission of
 * {@code new Future<T>(...)}. Guest lowering must supply an authorized scheduler,
 * enforce ownership/capability checks, and dispatch continuations through that
 * scheduler rather than a native producer thread.</p>
 *
 * <p>The callback registration overloads run the registrar immediately on the
 * calling thread. Only trusted host code may use them outside an authorized
 * OresScheduler turn. The compiler must not lower arbitrary guest operations
 * to these overloads from an I/O or producer thread.</p>
 */
public final class OresFutureConstructors {
    private OresFutureConstructors() { }

    /** One asynchronous producer of T, with automatic completion on return. */
    @FunctionalInterface
    public interface AsyncRun<T> {
        T run() throws Exception;
    }

    /**
     * One callback-based producer; returning does not complete its Future.
     * Registrations with retained external callbacks should instead use
     * CancellableRunCallback so cancellation can release the registration.
     */
    @FunctionalInterface
    public interface RunCallback<T> {
        void run(OresFuture.Callback<T> callback);
    }

    /**
     * External callback registration with a corresponding runtime-only
     * no-throw, nonblocking detach action. Detach must not run guest code.
     *
     * <p>When registration fails after partially attaching a listener, the
     * registrar must roll back that partial registration before throwing,
     * since it cannot return a detach action after failure.</p>
     */
    @FunctionalInterface
    public interface CancellableRunCallback<T> {
        Runnable register(OresFuture.Callback<T> callback);
    }

    /**
     * Start an AsyncRun on a runtime-owned carrier and observe its completion.
     * This is an interpreter-compatibility bridge, not permission to block an
     * actor turn or spawn a platform thread in source semantics.
     */
    public static <T> OresFuture<T> fromAsyncRun(
            AsyncRuntime runtime, AsyncRun<T> operation) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(operation, "operation");
        return runtime.submit(operation::run);
    }

    /**
     * Immediate host-only callback registrar. A completed registrar does not
     * imply a completed Future. For retained external registrations prefer
     * fromCancellableRunCallback to avoid holding a cancelled Future alive.
     */
    public static <T> OresFuture<T> fromRunCallback(RunCallback<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return OresFuture.fromCallback(operation::run);
    }

    /**
     * Checked lowering seam for guest callback registration when executing on
     * a tracked OresScheduler task. The exact guest actor/root turn admission
     * is separately enforced by their compiler/runtime entry points.
     */
    public static <T> OresFuture<T> fromRunCallbackOnCurrentTask(
            RunCallback<T> operation) {
        Objects.requireNonNull(operation, "operation");
        OresScheduler.requireCurrent();
        return OresFuture.fromCallback(operation::run);
    }

    /**
     * Wrap an external subscription that can be released at terminal state.
     * Cleanup runs exactly once for resolution, failure or cancellation,
     * including synchronous completion during register and concurrent
     * completion immediately after register.
     *
     * <p>The callback only settles the Future; it does not execute guest
     * continuations. The returned detach action is trusted runtime plumbing:
     * it must be nonblocking, nonthrowing, and must not run guest callbacks.
     * Detaching a subscription alone does not prove physical callback
     * quiescence; callers requiring that proof must separately await it.</p>
     */
    public static <T> OresFuture<T> fromCancellableRunCallback(
            CancellableRunCallback<T> operation) {
        Objects.requireNonNull(operation, "operation");

        AtomicReference<Runnable> detachAction = new AtomicReference<>();
        AtomicBoolean terminal = new AtomicBoolean();

        OresFuture<T> future = OresFuture.fromCallback(callback -> {
            Runnable action = Objects.requireNonNull(operation.register(callback),
                    "callback registrar must provide a detach action");
            detachAction.set(action);
            if (terminal.get()) {
                Runnable late = detachAction.getAndSet(null);
                if (late != null) late.run();
            }
        });

        future.whenCompleteRuntime((value, failure) -> {
            terminal.set(true);
            Runnable detach = detachAction.getAndSet(null);
            if (detach != null) detach.run();
        });
        return future;
    }
}
