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
        MUTEX_HANDOFF, TCP_HTTP_HANDLE, FFI_LOAN, ASYNC_ITERATOR,
        SHARED_BLOB_LEASE, OTHER
    }

    record Snapshot(
            int pending, Map<Kind, Integer> byKind,
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
            boolean changed;
            synchronized (lock) {
                if (state != 0) return false;
                if (!pending.remove(this)) {
                    throw new IllegalStateException("foreign physical receipt");
                }
                state = 1;
                changed = true;
            }
            if (changed) stateChanged.run(); // never inside registry monitor
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
            stateChanged.run(); // supervisor may observe quarantine
            return true;
        }
    }

    private final ActorRuntime.ActorId ownerActorId;
    private final long ownerGeneration;
    private final Runnable stateChanged;
    private final Object lock = new Object();
    private final Set<Receipt> pending =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private Throwable firstFailure;
    private boolean sealed;
    private boolean retired;

    ActorPhysicalRetirementRegistry(
            ActorRuntime.ActorId ownerActorId, long ownerGeneration,
            Runnable stateChanged) {
        this.ownerActorId = Objects.requireNonNull(ownerActorId, "ownerActorId");
        if (ownerGeneration <= 0) {
            throw new IllegalArgumentException("actor generation must be positive");
        }
        this.ownerGeneration = ownerGeneration;
        this.stateChanged = Objects.requireNonNull(stateChanged, "stateChanged");
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
            stateChanged.run();
            throw quotaFailure;
        }
        return result;
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
                    pending.size(), counts, sealed, retired, firstFailure);
        }
    }

    /**
     * The finalization winner seals the registry forever. It must hold the
     * actor lifecycle monitor and independently verify dispatcher/scheduler
     * quiescence before calling this; no callback is run here.
     */
    void retireVerified() {
        synchronized (lock) {
            if (retired || firstFailure != null || !sealed || !pending.isEmpty()) {
                throw new IllegalStateException(
                        "cannot retire actor with outstanding/failed physical receipts");
            }
            retired = true;
        }
    }
}
