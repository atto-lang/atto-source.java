package dev.oreslang.nodes;

import dev.oreslang.runtime.OresFuture;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.OresScheduler;
import org.graalvm.polyglot.Value;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * Host-only public async invocation handoff.
 *
 * A Truffle Value.asHostObject() call re-enters the guest context and can race
 * the just-started CONTROL task. This bridge captures the runtime Future while
 * the public guest call is already entered, and returns the actual Java Future
 * to the host after Value.execute() exits. No raw guest mutable state, guest
 * capability, or arbitrary host argument is transferred to an async task.
 *
 * The scope is strictly thread-local and nested calls cannot steal a parent
 * result. Only the dedicated internal control command can publish a result.
 */
public final class OresHostFutureBridge {
    private static final ThreadLocal<ArrayDeque<Slot>> CAPTURES =
            new ThreadLocal<>();

    /**
     * A one-use ticket. Claim happens before any guest dispatch; publication
     * is permitted only by the same physical context and source root that
     * claimed it. In particular, a reentrant command cannot claim this ticket
     * again while the first call is still starting its task.
     */
    static final class Slot {
        private enum State { OPEN, CLAIMED, PUBLISHED, DELIVERED, CLOSED }
        // Per host invocation, not per scheduler or process: rejected before
        // another TaskRunner enters the live registry.
        static final int MAX_DEFERRED_SOURCE_STARTS = 256;

        private final String function;
        // A capture is usable only on the physical host thread that opened it.
        // ThreadLocal is the first boundary; this explicit check also protects
        // package-level ticket operations from accidental cross-thread sharing.
        private final Thread ownerThread = Thread.currentThread();
        // Never supplied to the guest callable or exposed through a Value.
        private final String nonce = UUID.randomUUID().toString();
        private State state = State.OPEN;
        private Object ownerContext;
        private Object ownerRoot;
        // Only production scoped invocations set the expected context.
        // A Value from another context cannot borrow the host admission
        // obtained before its Truffle Value.execute boundary.
        private final Object expectedContext;
        private OresFuture<?> future;
        private final ArrayList<OresScheduler.DeferredStart<?>> deferredStarts =
                new ArrayList<>();

        Slot(String function) {
            this(function, null);
        }

        Slot(String function, Object expectedContext) {
            this.function = Objects.requireNonNull(function, "function");
            this.expectedContext = expectedContext;
        }

        private void requireOwnerThread() {
            if (Thread.currentThread() != ownerThread) {
                throw new SecurityException("host Future ticket belongs to another thread");
            }
        }

        String nonce() {
            requireOwnerThread();
            return nonce;
        }

        void claim(String function, String nonce, Object context, Object root) {
            requireOwnerThread();
            if (!this.function.equals(function) || !this.nonce.equals(nonce)) {
                throw new SecurityException(
                        "host Future handoff requires a matching scoped invocation");
            }
            if (state != State.OPEN) {
                throw new SecurityException("host Future handoff ticket already consumed");
            }
            // Validate both owners before publishing either strong reference:
            // a rejected claim must not retain a partially staged context.
            Object validatedContext = Objects.requireNonNull(context, "context");
            Object validatedRoot = Objects.requireNonNull(root, "root");
            if (expectedContext != null && expectedContext != validatedContext) {
                throw new SecurityException(
                        "claimed host Future context differs from exclusive host entry");
            }
            ownerContext = validatedContext;
            ownerRoot = validatedRoot;
            state = State.CLAIMED;
        }

        void publish(String function, String nonce, Object context, Object root,
                     OresFuture<?> result) {
            requireOwnerThread();
            if (!this.function.equals(function) || !this.nonce.equals(nonce)
                    || state != State.CLAIMED
                    || ownerContext != context || ownerRoot != root) {
                throw new SecurityException(
                        "host Future result does not belong to claimed invocation");
            }
            future = Objects.requireNonNull(result, "result");
            state = State.PUBLISHED;
        }

        void requireCapacityForStaging() {
            requireOwnerThread();
            if (state != State.CLAIMED) {
                throw new SecurityException(
                        "source start requires a claimed host Future invocation");
            }
            if (deferredStarts.size() >= MAX_DEFERRED_SOURCE_STARTS) {
                throw new RejectedExecutionException(
                        "host Future invocation exceeded deferred source task capacity");
            }
        }

        void stageSourceStart(OresScheduler.DeferredStart<?> deferred) {
            requireCapacityForStaging();
            deferredStarts.add(Objects.requireNonNull(deferred, "deferred"));
        }

        OresFuture<?> result() {
            requireOwnerThread();
            if (state != State.PUBLISHED || future == null) {
                throw new IllegalStateException(
                        "Oreslang public async call did not publish its Future");
            }
            // The Polyglot execute entry has already returned at this point.
            // Starting CONTROL work earlier would race the original host exit.
            OresFuture<?> delivered = future;
            future = null;
            state = State.DELIVERED;
            try {
                for (OresScheduler.DeferredStart<?> deferred : deferredStarts) {
                    deferred.dispatchAfterHostReturn();
                }
                deferredStarts.clear();
            } catch (RuntimeException | Error failure) {
                for (OresScheduler.DeferredStart<?> deferred : deferredStarts) {
                    try { deferred.abandonBeforeDispatch(); }
                    catch (RuntimeException | Error cleanup) {
                        if (failure != cleanup) failure.addSuppressed(cleanup);
                    }
                }
                deferredStarts.clear();
                try { delivered.cancel(false); }
                catch (RuntimeException | Error cleanup) {
                    if (failure != cleanup) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
            return delivered;
        }

        /**
         * The guest may have already published a producer when the outer
         * Polyglot Value.execute() subsequently fails. If the host did not
         * receive that Future, request source-task cancellation instead of
         * silently discarding its only receiver. Cancellation is a request,
         * not proof of physical cleanup completion.
         */
        void cancelUnreturnedOnHostFailure(Throwable hostFailure) {
            requireOwnerThread();
            Objects.requireNonNull(hostFailure, "hostFailure");
            // A guest dispatch registered during the bridged call may exist
            // even when linking or publication later fails. Such a producer
            // must never be left logically live without a physical carrier.
            for (OresScheduler.DeferredStart<?> deferred : deferredStarts) {
                try { deferred.abandonBeforeDispatch(); }
                catch (RuntimeException | Error cancellationFailure) {
                    if (cancellationFailure != hostFailure) {
                        hostFailure.addSuppressed(cancellationFailure);
                    }
                }
            }
            deferredStarts.clear();
            if (state != State.PUBLISHED || future == null) return;
            try {
                future.cancel(false);
            } catch (RuntimeException | Error cancellationFailure) {
                if (cancellationFailure != hostFailure) {
                    hostFailure.addSuppressed(cancellationFailure);
                }
            }
        }

        void close() {
            requireOwnerThread();
            // Revoke capabilities and release strong context references BEFORE
            // calling cancellation hooks. A throwing hook cannot leave a
            // claimed ticket open or retain the rest of the staged tasks.
            ArrayList<OresScheduler.DeferredStart<?>> abandoned =
                    new ArrayList<>(deferredStarts);
            deferredStarts.clear();
            state = State.CLOSED;
            ownerContext = null;
            ownerRoot = null;
            future = null;

            Throwable failure = null;
            for (OresScheduler.DeferredStart<?> deferred : abandoned) {
                try {
                    deferred.abandonBeforeDispatch();
                } catch (RuntimeException | Error cleanup) {
                    if (failure == null) failure = cleanup;
                    else if (failure != cleanup) failure.addSuppressed(cleanup);
                }
            }
            if (failure instanceof Error fatal) throw fatal;
            if (failure instanceof RuntimeException runtime) throw runtime;
        }
    }

    private OresHostFutureBridge() { }

    public static <T> OresFuture<T> invokePublicFuture(
            Value parsedSource, String function, Object... arguments) {
        return invokePublicFutureAfterHostExit(
                parsedSource, function, () -> { }, arguments);
    }

    /**
     * Package-private deterministic rendezvous hook for real Polyglot tests.
     * Runs AFTER parsedSource.execute returns but BEFORE pending CONTROL work
     * is dispatched. Production callers use the public zero-hook overload.
     */
    @SuppressWarnings("unchecked")
    static <T> OresFuture<T> invokePublicFutureAfterHostExit(
            Value parsedSource, String function, Runnable afterHostExit,
            Object... arguments) {
        Objects.requireNonNull(parsedSource, "parsedSource");
        Objects.requireNonNull(afterHostExit, "afterHostExit");
        if (function == null || function.isBlank()) {
            throw new IllegalArgumentException("public async function name is required");
        }
        Objects.requireNonNull(arguments, "arguments");

        ArrayDeque<Slot> slots = CAPTURES.get();
        if (slots == null) {
            slots = new ArrayDeque<>();
            CAPTURES.set(slots);
        }
        if (slots.size() >= 32) {
            throw new IllegalStateException("nested host Future handoff limit reached");
        }
        // Resolve only an unambiguous context created by this physical host
        // thread. Neither guessing from a Value nor acquiring inside the guest
        // root would protect the Truffle enter that Value.execute performs.
        // Parsing a Polyglot Value need not initialize its language yet.
        // Ask the Value's exact owning context to initialize Oreslang BEFORE
        // resolving the pre-entry host lease; neither thread-local guessing
        // nor an internal guest root can protect the original Value.execute.
        org.graalvm.polyglot.Context parsedContext = parsedSource.getContext();
        if (parsedContext == null) {
            throw new SecurityException(
                    "scoped host Future requires a context-bound Polyglot Value");
        }
        parsedContext.initialize(dev.oreslang.OresLanguage.ID);
        OresContext expected = OresContext.forExactHostValueContext(parsedContext);
        Slot slot = new Slot(function, expected);
        slots.push(slot);
        try {
            Object[] command = new Object[arguments.length + 3];
            command[0] = OresEvalRootNode.INVOKE_PUBLIC_FUTURE_BRIDGE_COMMAND;
            command[1] = function;
            command[2] = slot.nonce();
            System.arraycopy(arguments, 0, command, 3, arguments.length);
            // Do not inspect the returned Value. Its primitive acknowledgment
            // is intentionally unused; doing asHostObject() here would enter
            // the guest context again after the scheduler has started.
            try (OresContext.HostEntryLease ignored =
                    expected.acquireScopedHostEntry()) {
                parsedSource.execute(command);
            }
            // Exclusive ownership must end before staged CONTROL dispatch.
            afterHostExit.run();
            return (OresFuture<T>) slot.result();
        } catch (RuntimeException | Error hostFailure) {
            // Even after successful ticket publication, Polyglot may reject
            // the outer host return. Never abandon that published producer.
            slot.cancelUnreturnedOnHostFailure(hostFailure);
            throw hostFailure;
        } finally {
            Slot popped = slots.pop();
            slot.close();
            if (popped != slot) {
                popped.close();
                for (Slot remaining : slots) remaining.close();
                slots.clear();
                CAPTURES.remove();
                throw new IllegalStateException("host Future handoff scope was corrupted");
            }
            if (slots.isEmpty()) CAPTURES.remove();
        }
    }

    /**
     * Reject forged bridged control commands before any asynchronous work is
     * started. A missing host receiver must not create an orphaned producer.
     */
    static void requireActiveScope(String function, String nonce,
                                   Object context, Object root) {
        ArrayDeque<Slot> slots = CAPTURES.get();
        if (slots == null || slots.isEmpty()) {
            throw new SecurityException(
                    "host Future handoff requires a matching scoped invocation");
        }
        slots.peek().claim(function, nonce, context, root);
    }

    /**
     * The only source scheduler dispatch gate used by a scoped host Future
     * invocation. Work is registered inside the guest call but its physical
     * CONTROL carrier cannot start until Value.execute has returned.
     * Ordinary guest/actor calls do not inherit this host-specific deferral.
     */
    public static <T> OresFuture<T> startSourceTask(
            Object context, OresScheduler scheduler, OresScheduler.Task<T> task) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(task, "task");
        ArrayDeque<Slot> slots = CAPTURES.get();
        if (slots == null || slots.isEmpty()) return scheduler.start(task);
        Slot slot = slots.peek();
        slot.requireOwnerThread();
        if (slot.state != Slot.State.CLAIMED || slot.ownerContext != context) {
            throw new SecurityException(
                    "host Future source start requires the owning context and claimed scope");
        }
        // Check BEFORE touching the scheduler registry. Rejecting overflow
        // after prepareDeferred would leave an unowned NEW task behind.
        slot.requireCapacityForStaging();
        OresScheduler.DeferredStart<T> deferred = scheduler.prepareDeferred(task);
        try {
            slot.stageSourceStart(deferred);
        } catch (RuntimeException | Error failure) {
            try { deferred.abandonBeforeDispatch(); }
            catch (RuntimeException | Error cleanup) {
                if (failure != cleanup) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return deferred.future();
    }

    /** Called only by the same root and context that claimed the ticket. */
    static boolean publish(String function, String nonce, Object context, Object root,
                           OresFuture<?> future) {
        ArrayDeque<Slot> slots = CAPTURES.get();
        if (slots == null || slots.isEmpty()) return false;
        slots.peek().publish(function, nonce, context, root, future);
        return true;
    }
}
