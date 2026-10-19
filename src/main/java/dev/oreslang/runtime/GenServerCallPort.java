package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Host/runtime-only bridge from opaque bounded GenServer reply correlation
 * into OresFuture. This is NOT a guest-call method or an actor mailbox.
 *
 * Calls must first be admitted to this port, and a trusted actor dispatcher
 * must then separately admit/send a typed message. If send fails it MUST call
 * fail(ticket, target, failure) to settle and return ownership to the caller.
 * The port never executes source handlers or CONTROL callbacks.
 */
final class GenServerCallPort<T> {
    record PendingCall<T>(GenServerReplyLedger.Ticket ticket, OresFuture<T> future) { }

    private final Object gate = new Object();
    private final GenServerReplyLedger ledger;

    // Single ownership index: no global map duplicate and no full-map scan
    // when one actor incarnation fails. Every access is guarded by gate.
    private Map<GenServerReplyLedger.Target,
            Map<GenServerReplyLedger.Ticket, OresFuture<T>>> pendingByTarget =
            new HashMap<>();
    private int live;

    GenServerCallPort(int capacity) {
        ledger = new GenServerReplyLedger(capacity);
    }

    PendingCall<T> register(GenServerReplyLedger.Target target) {
        Objects.requireNonNull(target, "target");
        synchronized (gate) {
            var ticket = ledger.register(target);
            try {
                // Allocate the externally returned handle before publishing
                // into either index. On allocation failure retire the ticket.
                OresFuture<T> future = new OresFuture<>(() -> cancel(ticket), () -> { });
                PendingCall<T> result = new PendingCall<>(ticket, future);
                Map<GenServerReplyLedger.Ticket, OresFuture<T>> bucket =
                        pendingByTarget.get(target);
                if (bucket == null) {
                    bucket = new HashMap<>();
                    pendingByTarget.put(target, bucket);
                }
                if (bucket.putIfAbsent(ticket, future) != null)
                    throw new IllegalStateException("duplicate GenServer call ticket");
                live++;
                return result;
            } catch (RuntimeException | Error failure) {
                // This covers constructor/map-growth errors without stranding
                // scarce ledger quota. No guest callbacks run under gate.
                Map<GenServerReplyLedger.Ticket, OresFuture<T>> bucket =
                        pendingByTarget.get(target);
                if (bucket != null) {
                    bucket.remove(ticket);
                    if (bucket.isEmpty()) pendingByTarget.remove(target);
                }
                ledger.retire(ticket);
                throw failure;
            }
        }
    }

    /** Must be called with gate held, after ledger retirement succeeded. */
    private OresFuture<T> unlink(GenServerReplyLedger.Ticket ticket) {
        var target = ticket.target();
        var bucket = pendingByTarget.get(target);
        if (bucket == null)
            throw new IllegalStateException("missing call target index");
        OresFuture<T> future = bucket.remove(ticket);
        if (future == null)
            throw new IllegalStateException("missing reply future");
        if (bucket.isEmpty()) pendingByTarget.remove(target);
        live--;
        return future;
    }

    private boolean cancel(GenServerReplyLedger.Ticket ticket) {
        synchronized (gate) {
            if (!ledger.retire(ticket)) return false;
            unlink(ticket);
            return true;
        }
    }

    /** True only when the authentic responding actor wins settlement. */
    boolean reply(GenServerReplyLedger.Ticket ticket,
                  GenServerReplyLedger.Target respondingActor, T value) {
        OresFuture<T> future;
        synchronized (gate) {
            if (!ledger.tryReply(ticket, respondingActor)) return false;
            future = unlink(ticket);
        }
        // Completing an OresFuture may notify host waiters; never under gate.
        if (!future.completeFromRuntime(value)) {
            throw new IllegalStateException("reply ledger/future settlement mismatch");
        }
        return true;
    }

    /** Typed transport/admission failure, only from trusted runtime dispatch. */
    boolean fail(GenServerReplyLedger.Ticket ticket,
                 GenServerReplyLedger.Target respondingActor, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        OresFuture<T> future;
        synchronized (gate) {
            if (!ledger.tryReply(ticket, respondingActor)) return false;
            future = unlink(ticket);
        }
        if (!future.failFromRuntime(failure)) {
            throw new IllegalStateException("failure ledger/future settlement mismatch");
        }
        return true;
    }

    /** Timeout settles only the awaiting call; accepted work may continue. */
    boolean timeout(GenServerReplyLedger.Ticket ticket) {
        // Preallocate terminal failure before revoking the only live ticket.
        TimeoutException failure = new TimeoutException("GenServer deadline expired");
        OresFuture<T> future;
        synchronized (gate) {
            if (!ledger.retire(ticket)) return false;
            future = unlink(ticket);
        }
        if (!future.failFromRuntime(failure)) {
            throw new IllegalStateException("timeout ledger/future settlement mismatch");
        }
        return true;
    }

    /** Retire one generation in O(its pending calls), never O(all calls). */
    int failActor(GenServerReplyLedger.Target target, Throwable failure) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(failure, "failure");
        Map<GenServerReplyLedger.Ticket, OresFuture<T>> selected;
        synchronized (gate) {
            selected = pendingByTarget.get(target);
            if (selected == null) return 0;
            for (var ticket : selected.keySet()) {
                if (!ledger.retire(ticket))
                    throw new IllegalStateException("call port/ledger retirement mismatch");
            }
            pendingByTarget.remove(target);
            live -= selected.size();
        }
        // No guest or completion callbacks execute under the port lock.
        for (var future : selected.values()) {
            if (!future.failFromRuntime(failure))
                throw new IllegalStateException("actor exit/future settlement mismatch");
        }
        return selected.size();
    }

    /** Close claims all remaining calls before invoking any callbacks. */
    int close() {
        // Allocate a single shared terminal failure before taking authority.
        IllegalStateException failure = new IllegalStateException("GenServer call port closed");
        Map<GenServerReplyLedger.Target,
                Map<GenServerReplyLedger.Ticket, OresFuture<T>>> discarded;
        int count;
        synchronized (gate) {
            count = ledger.close();
            if (count != live)
                throw new IllegalStateException("call port/ledger size mismatch");
            if (count == 0) return 0;
            discarded = pendingByTarget;
            pendingByTarget = Map.of();
            live = 0;
        }
        for (var bucket : discarded.values()) {
            for (var future : bucket.values()) {
                if (!future.failFromRuntime(failure))
                    throw new IllegalStateException("close/future settlement mismatch");
            }
        }
        return count;
    }

    int pendingCount() {
        synchronized (gate) {
            if (ledger.pendingCount() != live)
                throw new IllegalStateException("call port/ledger size mismatch");
            return live;
        }
    }

    /** Runtime diagnostics for empty-bucket retention and test assertions. */
    int pendingTargetCount() {
        synchronized (gate) {
            return pendingByTarget.size();
        }
    }
}
