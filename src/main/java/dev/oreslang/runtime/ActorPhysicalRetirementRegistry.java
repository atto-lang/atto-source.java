package dev.oreslang.runtime;

import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        MUTEX_HANDOFF, TCP_HTTP_HANDLE, NATIVE_IO_OPERATION, FFI_LOAN,
        ASYNC_ITERATOR, SHARED_BLOB_LEASE, OTHER
    }

    record Snapshot(
            int pending, int inFlightNotifications, boolean finalizationHandoffInFlight,
            Map<Kind, Integer> byKind,
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
                // A removed ticket is not permission to retire while its
                // trusted host notification is executing.
                inFlightNotifications++;
            }
            Throwable failure = null;
            try {
                notifyOwner();
            } catch (RuntimeException | Error callbackFailure) {
                failure = callbackFailure;
            }
            boolean readyForFinalization;
            synchronized (lock) {
                inFlightNotifications--;
                readyForFinalization = sealed && firstFailure == null
                        && pending.isEmpty() && inFlightNotifications == 0;
                if (readyForFinalization) {
                    // Reserve finalization ownership before releasing the
                    // registry monitor. Otherwise another actor STOP caller
                    // could commit DONE before this handoff reports a failure.
                    if (finalizationHandoffThread != null) {
                        throw new IllegalStateException("duplicate finalization handoff");
                    }
                    finalizationHandoffThread = Thread.currentThread();
                }
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error fatal) throw fatal;
            if (readyForFinalization) {
                // Distinct trusted finalization handoff, NOT a second physical
                // notifier. Unrelated threads cannot retire until it finishes.
                // retireVerified() called by this handoff only PREPARES the
                // terminal state. The registry publishes retired after the
                // entire callback returns successfully.
                try {
                    notificationDrained.run();
                    synchronized (lock) {
                        if (retirementPrepared) {
                            if (firstFailure != null) {
                                retirementPrepared = false;
                                throw new IllegalStateException(
                                        "finalization handoff failed before retirement commit",
                                        firstFailure);
                            }
                            retired = true;
                            retirementPrepared = false;
                        }
                    }
                } catch (RuntimeException | Error callbackFailure) {
                    synchronized (lock) {
                        retirementPrepared = false;
                    }
                    recordFailure(callbackFailure);
                    throw callbackFailure;
                } finally {
                    synchronized (lock) {
                        finalizationHandoffThread = null;
                    }
                }
            }
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
    private final Runnable notificationDrained;
    private final Object lock = new Object();
    private final Set<Receipt> pending =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private Throwable firstFailure;
    private int inFlightNotifications;
    private Thread finalizationHandoffThread;
    /*
     * retireVerified() called from the reserved finalization handoff is only a
     * prepare marker. The irreversible retired bit is published by the
     * registry after the handoff callback returns normally, so no later
     * callback failure can create retired && failure.
     */
    private boolean retirementPrepared;
    private boolean sealed;
    private boolean retired;

    ActorPhysicalRetirementRegistry(
            ActorRuntime.ActorId ownerActorId, long ownerGeneration,
            Runnable stateChanged, Runnable notificationDrained) {
        this.ownerActorId = Objects.requireNonNull(ownerActorId, "ownerActorId");
        if (ownerGeneration <= 0) {
            throw new IllegalArgumentException("actor generation must be positive");
        }
        this.ownerGeneration = ownerGeneration;
        this.stateChanged = Objects.requireNonNull(stateChanged, "stateChanged");
        this.notificationDrained = Objects.requireNonNull(
                notificationDrained, "notificationDrained");
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

    private void recordFailure(Throwable failure) {
        synchronized (lock) {
            if (firstFailure == null) firstFailure = failure;
            else if (firstFailure != failure) firstFailure.addSuppressed(failure);
        }
    }

    /**
     * Trusted physical-event observer: it must never invoke actor finalization.
     * The separate notificationDrained callback runs after this returns.
     */
    private void notifyOwner() {
        try {
            stateChanged.run();
        } catch (RuntimeException | Error failure) {
            recordFailure(failure);
            throw failure;
        }
    }

    /** No callbacks or guest work. Called atomically with actor logical STOP. */
    void seal() {
        synchronized (lock) {
            sealed = true;
        }
    }

    /** True only on the trusted finalization handoff's synchronous carrier. */
    boolean isCurrentFinalizationHandoffThread() {
        synchronized (lock) {
            return finalizationHandoffThread != null
                    && finalizationHandoffThread == Thread.currentThread();
        }
    }

    Snapshot snapshot() {
        synchronized (lock) {
            Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
            for (Receipt receipt : pending) {
                counts.merge(receipt.kind(), 1, Integer::sum);
            }
            return new Snapshot(
                    pending.size(), inFlightNotifications,
                    finalizationHandoffThread != null, counts,
                    sealed, retired, firstFailure);
        }
    }

    /**
     * The finalization winner seals the registry forever. It must hold the
     * actor lifecycle monitor and independently verify dispatcher/scheduler
     * quiescence before calling this. During the reserved notification-drained
     * handoff this only stages retirement; the registry publishes the retired
     * bit after that callback returns normally. Outside a handoff the verified
     * retirement is immediate.
     */
    void retireVerified() {
        synchronized (lock) {
            if (retired || retirementPrepared || firstFailure != null || !sealed
                    || !pending.isEmpty() || inFlightNotifications != 0
                    || (finalizationHandoffThread != null
                            && finalizationHandoffThread != Thread.currentThread())) {
                throw new IllegalStateException(
                        "cannot retire actor with outstanding/failed physical receipts");
            }
            if (finalizationHandoffThread == Thread.currentThread()) {
                // This callback can still throw after returning from here.
                // Stage only; completeAfterPhysicalRelease() performs the
                // nonthrowing terminal publication after the callback exits.
                retirementPrepared = true;
            } else {
                retired = true;
            }
        }
    }
}
