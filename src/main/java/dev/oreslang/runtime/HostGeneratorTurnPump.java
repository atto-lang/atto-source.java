package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

/**
 * Context-owned admission stage for externally requested generator turns.
 *
 * A caller may enqueue work from any host thread, but only a verified owner
 * executing a real context-owned scheduler turn may call dispatchOne().
 * There is deliberately NO executor here: enqueuing may never manufacture
 * Truffle admission or start a virtual-thread generator producer.
 *
 * Each admitted operation has TWO completions: its public pull result and its
 * physical guest-turn exit. An early Future cancellation/settlement does not
 * free ownership or admit the next turn until the exit barrier also settles.
 *
 * This is a staging primitive, not a host async-generator implementation:
 * the existing interpreter generator producer still uses virtual carriers.
 * Do not wire that producer to this pump until it has continuation lowering.
 */
final class HostGeneratorTurnPump implements AutoCloseable {
    record OwnerToken(UUID contextId, UUID incarnation) {
        OwnerToken {
            Objects.requireNonNull(contextId, "contextId");
            Objects.requireNonNull(incarnation, "incarnation");
        }
    }

    record Turn<T>(OresFuture<T> result, OresFuture<?> physicalExit) {
        Turn {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(physicalExit, "physicalExit");
            if (result == physicalExit) {
                throw new IllegalArgumentException(
                        "physical exit must be a distinct source-owned barrier, "
                        + "not the public Future's early completion/cancellation");
            }
        }
    }

    @FunctionalInterface
    interface Start<T> {
        /** Nonblocking: enqueue/start the guest continuation on the owner. */
        Turn<T> start();
    }

    private static final int DEFAULT_CAPACITY = 256;

    private static final class Request<T> {
        final Start<T> start;
        final OresFuture<T> future;
        Turn<T> turn;
        boolean valueSettled;
        boolean exited;
        boolean finished;
        T value;
        Throwable failure;
        Throwable exitFailure;

        Request(HostGeneratorTurnPump owner, Start<T> start) {
            this.start = start;
            this.future = new OresFuture<>(() -> owner.cancel(this));
        }
    }

    private final Object gate = new Object();
    private final OwnerToken owner;
    private final BooleanSupplier ownerTurnAuthorized;
    private final Runnable signalOwner;
    private final int capacity;
    private final ArrayDeque<Request<?>> pending = new ArrayDeque<>();
    private Request<?> active;
    private boolean sealed;
    // A starter may throw *after* launching a producer but before returning
    // its physical-exit receipt. Without that receipt there is no proof of
    // quiescence. Permanently quarantine this incarnation; retries may not
    // claim retirement from the absence of an active queue entry.
    private boolean unprovenPhysicalExit;

    HostGeneratorTurnPump(UUID contextId, BooleanSupplier ownerTurnAuthorized) {
        this(contextId, ownerTurnAuthorized, () -> { }, DEFAULT_CAPACITY);
    }

    HostGeneratorTurnPump(UUID contextId, BooleanSupplier ownerTurnAuthorized,
                          Runnable signalOwner, int capacity) {
        this.owner = new OwnerToken(
                Objects.requireNonNull(contextId, "contextId"), UUID.randomUUID());
        this.ownerTurnAuthorized = Objects.requireNonNull(
                ownerTurnAuthorized, "ownerTurnAuthorized");
        this.signalOwner = Objects.requireNonNull(signalOwner, "signalOwner");
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    OwnerToken token() {
        return owner;
    }

    <T> OresFuture<T> request(OwnerToken token, Start<T> start) {
        Objects.requireNonNull(start, "start");
        Request<T> request = new Request<>(this, start);
        boolean wake;
        synchronized (gate) {
            if (!owner.equals(token)) {
                throw new SecurityException("generator pull belongs to another context incarnation");
            }
            if (sealed) {
                throw new RejectedExecutionException("generator context is closing");
            }
            if (pending.size() >= capacity) {
                throw new RejectedExecutionException("generator host pull queue is full");
            }
            pending.addLast(request);
            wake = active == null;
        }
        if (wake) {
            try {
                signalOwner.run();
            } catch (RuntimeException | Error wakeFailure) {
                // The caller cannot receive a handle when submission throws.
                // Fail closed and cancel every staged request, rather than
                // leaving an unowned producer eligible for later dispatch.
                seal();
                throw wakeFailure;
            }
        }
        return request.future;
    }

    /**
     * Called only from a scheduler-owned, physically entered guest turn.
     * The start callback must enqueue a continuation, not block for yield,
     * hold a guest entry lock, or create an independent virtual guest carrier.
     */
    boolean dispatchOne() {
        if (!ownerTurnAuthorized.getAsBoolean()) {
            throw new SecurityException(
                    "generator dispatch requires an authorized context-owned guest turn");
        }
        Request<?> selected = null;
        synchronized (gate) {
            if (sealed || active != null) return false;
            while (!pending.isEmpty()) {
                Request<?> candidate = pending.removeFirst();
                if (!candidate.future.isDone()) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) return false;
            // Reserve the full logical and physical turn before starting.
            // A simultaneous seal sees this reservation even when the
            // starter has not returned its result/exit barriers yet.
            active = selected;
        }

        if (selected.future.isDone() || isSealed()) {
            // The cancellation/shutdown won after dequeuing but before
            // producer startup. It is safe to release this unused reservation.
            synchronized (gate) {
                selected.finished = true;
                if (active != selected) {
                    throw new IllegalStateException("generator reservation changed owner");
                }
                active = null;
            }
            signalIfReady();
            return true;
        }

        Turn<?> started;
        try {
            // NEVER execute user-supplied starter code while holding gate.
            // A stalled or reentrant callback must not prevent close() from
            // sealing admission or cancelling pending/active host Futures.
            started = selected.start.start();
            if (started == null) {
                throw new IllegalStateException(
                        "generator turn starter returned no physical-exit barrier");
            }
        } catch (RuntimeException | Error failure) {
            synchronized (gate) {
                selected.finished = true;
                if (active != selected) {
                    throw new IllegalStateException("generator reservation changed owner", failure);
                }
                unprovenPhysicalExit = true;
                active = null;
            }
            // A starter that throws could have partially launched guest work
            // but supplied no physical-exit barrier. Poison the queue AND
            // retain a permanent quarantine, not merely a canceled Future.
            seal();
            selected.future.failFromRuntime(failure);
            return true;
        }

        boolean cancelled;
        synchronized (gate) {
            if (active != selected) {
                throw new IllegalStateException("generator reservation changed owner");
            }
            selected.turn = castTurn(started);
            cancelled = selected.future.isCancelled() || sealed;
        }
        attachCallbacks(selected);
        // The host can cancel while the starter is running. Seal may also
        // precede publication of turn: either way, carry cancellation into
        // the newly returned source task without releasing physical ownership.
        if (cancelled || selected.future.isCancelled()) started.result().cancel(false);
        return true;
    }

    private boolean isSealed() {
        synchronized (gate) {
            return sealed;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Turn<T> castTurn(Turn<?> turn) {
        return (Turn<T>) turn;
    }

    @SuppressWarnings("unchecked")
    private <T> void attachCallbacks(Request<?> raw) {
        Request<T> request = (Request<T>) raw;
        Turn<T> turn = request.turn;
        turn.result().whenCompleteRuntime((value, failure) ->
                settleValue(request, value, failure));
        turn.physicalExit().whenCompleteRuntime((ignored, failure) ->
                settleExit(request, failure));
    }

    private <T> void settleValue(Request<T> request, T value, Throwable failure) {
        synchronized (gate) {
            if (request.finished || request.valueSettled) return;
            request.valueSettled = true;
            request.value = value;
            request.failure = failure;
        }
        finishIfExited(request);
    }

    private void settleExit(Request<?> request, Throwable failure) {
        boolean unproven = failure != null;
        synchronized (gate) {
            if (request.finished || request.exited) return;
            request.exited = true;
            request.exitFailure = failure;
            if (unproven) unprovenPhysicalExit = true;
        }
        if (unproven) {
            // A cancelled/exceptional barrier reports no physical DONE.
            // Request producer cancellation but NEVER treat the settlement
            // as evidence that TruffleContext.leave has completed.
            seal();
        }
        finishIfExited(request);
    }

    @SuppressWarnings("unchecked")
    private <T> void finishIfExited(Request<T> request) {
        T value;
        Throwable failure;
        boolean wake;
        synchronized (gate) {
            if (request.finished || !request.valueSettled || !request.exited) return;
            request.finished = true;
            value = request.value;
            failure = request.exitFailure != null ? request.exitFailure : request.failure;
            if (active != request) {
                throw new IllegalStateException("generator turn lost physical owner");
            }
            // Retain physical/logical ownership until the previous public
            // result is published. A second scheduler carrier must not race
            // ahead of that result even after the physical exit is observed.
            wake = !sealed && !pending.isEmpty();
        }
        try {
            if (failure == null) request.future.completeFromRuntime(value);
            else request.future.failFromRuntime(OresFuture.unwrap(failure));
        } finally {
            synchronized (gate) {
                if (active != request) {
                    throw new IllegalStateException("generator turn changed owner before publication");
                }
                active = null;
                wake = !sealed && !pending.isEmpty();
            }
            if (wake) notifyOwnerOrSeal();
        }
    }

    private static void failSelected(Request<?> selected, Throwable failure) {
        selected.future.failFromRuntime(failure);
    }

    private void signalIfReady() {
        boolean wake;
        synchronized (gate) {
            wake = !sealed && active == null && !pending.isEmpty();
        }
        if (wake) notifyOwnerOrSeal();
    }

    /** A failed notifier may not strand already-admitted work. */
    private void notifyOwnerOrSeal() {
        try {
            signalOwner.run();
        } catch (RuntimeException | Error rejected) {
            seal();
            // Notification is not guest execution. Avoid throwing an owner
            // dispatch failure into an unrelated Future-completion callback.
        }
    }

    private void cancel(Request<?> request) {
        Turn<?> running = null;
        synchronized (gate) {
            if (active == request) {
                running = request.turn;
            } else {
                pending.remove(request);
            }
        }
        if (running != null) {
            // This settles the public result, not the physical-exit barrier.
            running.result().cancel(false);
        } else {
            signalIfReady();
        }
    }

    /** Fence new admissions before teardown; never wait under this monitor. */
    void seal() {
        List<Request<?>> rejected;
        Turn<?> running;
        Request<?> starting;
        synchronized (gate) {
            if (sealed) return;
            sealed = true;
            rejected = new ArrayList<>(pending);
            pending.clear();
            running = active == null ? null : active.turn;
            // Cancellation must remain observable while start.start() has
            // dropped the gate but has not handed its physical exit back.
            starting = active != null && running == null ? active : null;
        }
        for (Request<?> request : rejected) request.future.cancel(false);
        if (starting != null) starting.future.cancel(false);
        if (running != null) running.result().cancel(false);
    }

    boolean isQuiescent() {
        synchronized (gate) {
            return active == null && pending.isEmpty() && !unprovenPhysicalExit;
        }
    }

    /**
     * Close retries must not retire physical guest execution after cancellation
     * merely because the public Future already reports isDone/isCancelled.
     */
    void requireQuiescent() {
        if (!isQuiescent()) {
            throw new IllegalStateException(
                    "generator host turn lacks a complete physical guest exit receipt "
                    + "(active, pending or indeterminate starter failure)");
        }
    }

    @Override
    public void close() {
        seal();
        requireQuiescent();
    }
}
