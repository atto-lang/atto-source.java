package dev.oreslang.runtime;

import java.util.ArrayList;
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
    private final Map<GenServerReplyLedger.Ticket, OresFuture<T>> futures = new HashMap<>();

    GenServerCallPort(int capacity) {
        ledger = new GenServerReplyLedger(capacity);
    }

    PendingCall<T> register(GenServerReplyLedger.Target target) {
        Objects.requireNonNull(target, "target");
        synchronized (gate) {
            var ticket = ledger.register(target); // authority not published to guest DATA
            OresFuture<T> future = new OresFuture<>(() -> cancel(ticket), () -> { });
            futures.put(ticket, future);
            return new PendingCall<>(ticket, future);
        }
    }

    private boolean cancel(GenServerReplyLedger.Ticket ticket) {
        synchronized (gate) {
            if (!ledger.retire(ticket)) return false;
            futures.remove(ticket);
            return true;
        }
    }

    /** True only when the authentic responding actor wins settlement. */
    boolean reply(GenServerReplyLedger.Ticket ticket,
                  GenServerReplyLedger.Target respondingActor, T value) {
        OresFuture<T> future;
        synchronized (gate) {
            if (!ledger.tryReply(ticket, respondingActor)) return false;
            future = futures.remove(ticket);
            if (future == null) throw new IllegalStateException("missing reply future");
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
            future = futures.remove(ticket);
            if (future == null) throw new IllegalStateException("missing failure future");
        }
        if (!future.failFromRuntime(failure)) {
            throw new IllegalStateException("failure ledger/future settlement mismatch");
        }
        return true;
    }

    /** Timeout only settles the awaiting call; accepted server work may continue. */
    boolean timeout(GenServerReplyLedger.Ticket ticket) {
        OresFuture<T> future;
        synchronized (gate) {
            if (!ledger.retire(ticket)) return false;
            future = futures.remove(ticket);
            if (future == null) throw new IllegalStateException("missing timeout future");
        }
        if (!future.failFromRuntime(new TimeoutException("GenServer deadline expired"))) {
            throw new IllegalStateException("timeout ledger/future settlement mismatch");
        }
        return true;
    }

    /** Actor crash/restart fences only the specified generation's pending calls. */
    int failActor(GenServerReplyLedger.Target target, Throwable failure) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(failure, "failure");
        ArrayList<OresFuture<T>> affected = new ArrayList<>();
        synchronized (gate) {
            for (var entry : new ArrayList<>(futures.entrySet())) {
                if (entry.getKey().matchesTarget(target) && ledger.retire(entry.getKey())) {
                    futures.remove(entry.getKey());
                    affected.add(entry.getValue());
                }
            }
        }
        for (var future : affected) {
            if (!future.failFromRuntime(failure)) {
                throw new IllegalStateException("actor exit/future settlement mismatch");
            }
        }
        return affected.size();
    }

    /** Close claims all remaining calls before invoking any callbacks. */
    int close() {
        ArrayList<OresFuture<T>> discarded;
        synchronized (gate) {
            int count = ledger.close();
            if (count == 0) return 0;
            discarded = new ArrayList<>(futures.values());
            if (discarded.size() != count) {
                throw new IllegalStateException("call port/ledger size mismatch");
            }
            futures.clear();
        }
        for (var future : discarded) {
            if (!future.failFromRuntime(new IllegalStateException("GenServer call port closed"))) {
                throw new IllegalStateException("close/future settlement mismatch");
            }
        }
        return discarded.size();
    }

    int pendingCount() {
        synchronized (gate) {
            if (ledger.pendingCount() != futures.size()) {
                throw new IllegalStateException("call port/ledger size mismatch");
            }
            return futures.size();
        }
    }
}
