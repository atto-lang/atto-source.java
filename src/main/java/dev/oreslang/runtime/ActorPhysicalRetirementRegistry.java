package dev.oreslang.runtime;

import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Trusted host runtime physical-resource retirement authority for one actor
 * incarnation. A Future cancellation, a guest defer, or a channel unsubscribe
 * is NOT a physical handle/arena release receipt.
 *
 * The registry is package-private: source actors never receive registration
 * or settlement authority. Native/IO adapters must register BEFORE acquiring
 * a resource/loan, then settle only after the actual native close/handoff
 * barrier completes. A failure is sticky and quarantines the actor.
 */
final class ActorPhysicalRetirementRegistry {
    static final int MAX_OUTSTANDING = 4_096;

    enum Kind {
        MUTEX_HANDOFF, TCP_HTTP_HANDLE, NATIVE_IO_OPERATION, FFI_LOAN, ASYNC_ITERATOR,
        SHARED_BLOB_LEASE, OTHER
    }

    record Snapshot(
            int pending, int inFlightNotifications, Map<Kind, Integer> byKind,
            boolean sealed, boolean retired, Throwable failure) {
        Snapshot {
            byKind = Map.copyOf(byKind);
        }
    }

    final class Receipt {
        private final ActorRuntime.ActorId actorId;
        private final long generation;
        private final Kind kind;
        private int state; // 0 = pending, 1 = physically released, 2 = failed

        private Receipt(Kind kind) {
            this.actorId = ownerActorId;
            this.generation = ownerGeneration;
            this.kind = kind;
        }

        ActorRuntime.ActorId actorId() { return actorId; }
        long generation() { return generation; }
        Kind kind() { return kind; }

        /**
         * The owner must call this only AFTER actual physical reclamation.
         * It is intentionally not AutoCloseable: lexical scope exit alone
         * cannot prove native callback or queued mutex handoff retirement.
         */
        boolean completeAfterPhysicalRelease() {
            synchronized (lock) {
                if (state != 0) return false;
                if (!pending.remove(this)) {
                    throw new IllegalStateException("foreign physical receipt");
                }
                state = 1;
                // A concurrently observed empty pending set is NOT a safe
                // physical retirement receipt while this terminal callback
                // can still be executing on an external driver thread.
                inFlightNotifications++;
            }
            Throwable failure = null;
            try {
                notifyOwner();
            } catch (RuntimeException | Error callbackFailure) {
                failure = callbackFailure;
            } finally {
                synchronized (lock) {
                    inFlightNotifications--;
                }
                // Reevaluate STOP only AFTER the original callback has exited.
                // In real actor runtimes this dispatch is host-only and deferred,
                // never an inline finalizer on the native completion thread.
                if (failure == null) {
                    try {
                        requestPostDrainWake();
                    } catch (RuntimeException | Error callbackFailure) {
                        if (failure == null) failure = callbackFailure;
                        else if (failure != callbackFailure) {
                            failure.addSuppressed(callbackFailure);
                        }
                    }
                }
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error fatal) throw fatal;
            return true;
        }

        /** A failed/uncertain physical release is not a successful receipt. */
        boolean failPhysicalRelease(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            synchronized (lock) {
                if (state != 0) return false;
                if (!pending.contains(this)) {
                    throw new IllegalStateException("foreign physical receipt");
                }
                state = 2;
                if (firstFailure == null) firstFailure = failure;
                else if (firstFailure != failure) firstFailure.addSuppressed(failure);
                // Retain the ticket and the actor: there is no proof the
                // underlying native/IO resource was actually reclaimed.
            }
            notifyOwner(); // supervisor may observe quarantine
            return true;
        }
    }

    private final ActorRuntime.ActorId ownerActorId;
    private final long ownerGeneration;
    private final Runnable stateChanged;
    /** Optional dedicated bounded host-only dispatch, never a guest carrier. */
    private final Executor postDrainExecutor;
    private final Object lock = new Object();
    private final Set<Receipt> pending =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private Throwable firstFailure;
    private int inFlightNotifications;
    private boolean sealed;
    private boolean retired;

    ActorPhysicalRetirementRegistry(
            ActorRuntime.ActorId ownerActorId, long ownerGeneration,
            Runnable stateChanged) {
        this(ownerActorId, ownerGeneration, stateChanged, null);
    }

    ActorPhysicalRetirementRegistry(
            ActorRuntime.ActorId ownerActorId, long ownerGeneration,
            Runnable stateChanged, Executor postDrainExecutor) {
        this.ownerActorId = Objects.requireNonNull(ownerActorId, "ownerActorId");
        if (ownerGeneration <= 0) {
            throw new IllegalArgumentException("actor generation must be positive");
        }
        this.ownerGeneration = ownerGeneration;
        this.stateChanged = Objects.requireNonNull(stateChanged, "stateChanged");
        this.postDrainExecutor = postDrainExecutor;
    }

    /**
     * Physical retirement notifications must never execute the final actor
     * reclamation on the native/FFI callback stack. The wake's admission is
     * decided before a deferred worker may invoke the owner. A rejected
     * dispatch permanently quarantines this incarnation.
     *
     * The legacy direct form is retained for isolated registry-only tests.
     * Actual ActorCells MUST supply an asynchronous host-only dispatcher.
     */
    private void requestPostDrainWake() {
        if (postDrainExecutor == null) {
            notifyOwner();
            return;
        }
        CompletableFuture<Boolean> allowed = new CompletableFuture<>();
        Thread submitter = Thread.currentThread();
        java.util.concurrent.atomic.AtomicBoolean executingDispatch =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicBoolean ranInline =
                new java.util.concurrent.atomic.AtomicBoolean();
        try {
            postDrainExecutor.execute(() -> {
                // Direct/caller-runs execution would deadlock on its own
                // approval gate. Remember this protocol violation even if a
                // broken executor swallows the Runnable's exception.
                if (Thread.currentThread() == submitter && executingDispatch.get()) {
                    ranInline.set(true);
                    throw new IllegalStateException(
                            "physical retirement wake requires asynchronous host dispatch");
                }
                // A fast worker must not observe approval until its
                // submission has returned without throwing. This also
                // prevents an executor which enqueues then throws from
                // authorizing Actor.done() before quarantine is latched.
                if (allowed.join()) {
                    notifyOwner();
                }
            });
            executingDispatch.set(false);
            if (ranInline.get()) {
                throw new IllegalStateException(
                        "physical retirement wake requires asynchronous host dispatch");
            }
            allowed.complete(true);
        } catch (RuntimeException | Error failure) {
            executingDispatch.set(false);
            synchronized (lock) {
                if (firstFailure == null) firstFailure = failure;
                else if (firstFailure != failure) firstFailure.addSuppressed(failure);
            }
            allowed.complete(false);
            throw failure;
        }
    }

    /**
     * Admission happens under the owning ActorCell.lifecycleLock.
     * STOP seals ordinary admission; only an already executing authenticated
     * cleanup continuation may register further close/iterator loan receipts.
     */
    Receipt register(Kind kind, boolean authenticatedCleanupTurn) {
        Objects.requireNonNull(kind, "kind");
        IllegalStateException quotaFailure = null;
        Receipt result = null;
        synchronized (lock) {
            if (retired || (sealed && !authenticatedCleanupTurn)) {
                throw new IllegalStateException(
                        "physical receipt admission sealed for actor " + ownerActorId);
            }
            if (pending.size() >= MAX_OUTSTANDING) {
                quotaFailure = new IllegalStateException(
                        "actor physical retirement receipt capacity exhausted");
                if (firstFailure == null) firstFailure = quotaFailure;
            } else {
                result = new Receipt(kind);
                pending.add(result);
            }
        }
        if (quotaFailure != null) {
            notifyOwner();
            throw quotaFailure;
        }
        return result;
    }

    /**
     * Retirement notifications are trusted host callbacks. A broken callback
     * is not an arena-release receipt: fail closed and retain the actor.
     */
    private void notifyOwner() {
        try {
            stateChanged.run();
        } catch (RuntimeException | Error failure) {
            synchronized (lock) {
                if (firstFailure == null) firstFailure = failure;
                else if (firstFailure != failure) firstFailure.addSuppressed(failure);
            }
            throw failure;
        }
    }

    /** No callbacks or guest work. Called atomically with actor logical STOP. */
    void seal() {
        synchronized (lock) {
            sealed = true;
        }
    }

    Snapshot snapshot() {
        synchronized (lock) {
            Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
            for (Receipt receipt : pending) {
                counts.merge(receipt.kind(), 1, Integer::sum);
            }
            return new Snapshot(
                    pending.size(), inFlightNotifications, counts,
                    sealed, retired, firstFailure);
        }
    }

    /**
     * The finalization winner seals the registry forever. It must hold the
     * actor lifecycle monitor and independently verify dispatcher/scheduler
     * quiescence before calling this; no callback is run here.
     */
    void retireVerified() {
        synchronized (lock) {
            if (retired || firstFailure != null || !sealed
                    || !pending.isEmpty() || inFlightNotifications != 0) {
                throw new IllegalStateException(
                        "cannot retire actor with outstanding/failed physical receipts");
            }
            retired = true;
        }
    }
}
