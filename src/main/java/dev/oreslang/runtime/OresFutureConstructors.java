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
        return OresFuture.fromCallback(callback ->
                operation.run(normalizeExternalRejection(callback)));
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
        return OresFuture.fromCallback(callback ->
                operation.run(normalizeExternalRejection(callback)));
    }

    /**
     * A malformed null failure from a retained foreign callback must not
     * strand an otherwise pending Future. Keep OresFuture's single-shot
     * authority and duplicate-completion semantics in the underlying target.
     * This adapter neither runs guest code nor changes scheduler ownership.
     */
    private static <T> OresFuture.Callback<T> normalizeExternalRejection(
            OresFuture.Callback<T> target) {
        return new OresFuture.Callback<>() {
            @Override
            public void resolve(T value) {
                target.resolve(value);
            }

            @Override
            public void reject(Throwable failure) {
                target.reject(failure != null ? failure
                        : new NullPointerException("callback rejection failure must not be null"));
            }

            @Override
            public void cancel() {
                target.cancel();
            }

            @Override
            public boolean isDone() {
                return target.isDone();
            }
        };
    }

    /**
     * Buffer terminal callbacks until the registrar has returned a valid
     * detach action. A foreign API is permitted to call its completion
     * callback synchronously inside register(), but that must not commit a
     * result before registration has become cleanup-safe.
     */
    private static final class StagedCallback<T> implements OresFuture.Callback<T> {
        private record Outcome<T>(T value, Throwable error, boolean cancelled) { }

        // The foreign subscriber may retain this StagedCallback indefinitely.
        // Revoke and clear both the Future completion authority and any captured
        // success payload once the Future reaches a terminal state.
        private final AtomicReference<OresFuture.Callback<T>> target;
        private final AtomicReference<Outcome<T>> pending = new AtomicReference<>();
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicBoolean delivered = new AtomicBoolean();
        private final AtomicBoolean revoked = new AtomicBoolean();

        private StagedCallback(OresFuture.Callback<T> target) {
            this.target = new AtomicReference<>(Objects.requireNonNull(target, "target"));
        }

        @Override
        public void resolve(T value) {
            claim(new Outcome<>(value, null, false));
        }

        @Override
        public void reject(Throwable failure) {
            // Foreign APIs can violate their contract and call reject(null)
            // after returning from register(). Throwing here would strand the
            // Future and its retained detach hook forever. Turn the malformed
            // signal into a terminal failure so cleanup can still run.
            Throwable terminalFailure = failure != null ? failure
                    : new NullPointerException("callback rejection failure must not be null");
            claim(new Outcome<>(null, terminalFailure, false));
        }

        @Override
        public void cancel() {
            claim(new Outcome<>(null, null, true));
        }

        @Override
        public boolean isDone() {
            OresFuture.Callback<T> callback = target.get();
            return claimed.get() || revoked.get()
                    || (callback != null && callback.isDone());
        }

        private void claim(Outcome<T> outcome) {
            if (!claimed.compareAndSet(false, true)) {
                throw new OresFuture.AlreadySettledException(
                        "callback Future already settled; duplicate completion");
            }
            // The first foreign callback after consumer cancellation or failed
            // registration is harmless, but must not recover settlement rights.
            if (revoked.get()) return;
            pending.set(outcome);
            // A terminal revocation may race between the check and store.
            // Clear the captured value again if it has already won.
            if (revoked.get()) {
                pending.set(null);
                return;
            }
            deliverIfReady();
        }

        private void arm() {
            armed.set(true);
            deliverIfReady();
        }

        private void revoke() {
            revoked.set(true);
            pending.set(null);
            target.set(null);
        }

        private void deliverIfReady() {
            if (!armed.get() || revoked.get()) return;
            Outcome<T> outcome = pending.get();
            if (outcome == null || !delivered.compareAndSet(false, true)) return;
            OresFuture.Callback<T> callback = target.get();
            // Consumer cancellation may have revoked completion authority
            // before the delivering thread captured its callback.
            if (callback == null) return;
            if (outcome.cancelled()) callback.cancel();
            else if (outcome.error() != null) callback.reject(outcome.error());
            else callback.resolve(outcome.value());
        }
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
        AtomicReference<StagedCallback<T>> registration = new AtomicReference<>();

        OresFuture<T> future = OresFuture.fromCallback(callback -> {
            StagedCallback<T> staged = new StagedCallback<>(callback);
            registration.set(staged);
            Runnable action = Objects.requireNonNull(operation.register(staged),
                    "callback registrar must provide a detach action");
            detachAction.set(action);
            // Only after a valid detach action exists can synchronous
            // registration callbacks publish completion.
            staged.arm();
        });

        // The Future is not exposed to callers until this method returns.
        // Foreign callbacks remain staged until after detachAction is stored
        // and arm() is called. A Future that settles before this subscription
        // is installed notifies this runtime waiter immediately, so no
        // separate terminal flag / second detach path is necessary.
        future.whenCompleteRuntime((value, failure) -> {
            StagedCallback<T> staged = registration.getAndSet(null);
            if (staged != null) staged.revoke();
            Runnable detach = detachAction.getAndSet(null);
            if (detach != null) detach.run();
        });
        return future;
    }
}
