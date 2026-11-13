package dev.oreslang.runtime;

import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * Host-only, listener-scoped slot ownership for reusable HTTP actor workers.
 *
 * A Handler may be shared by multiple listeners, but an actor incarnation
 * may be selected only from the slots belonging to its exact Server identity.
 * Identity semantics are intentional: an owner-selected equals()/hashCode()
 * method must not merge two unrelated listeners.
 *
 * The Handler still serializes next() and replace() under its monitor.
 * A separate generation registry retains displaced physical actor lifetimes.
 */
final class HttpPooledWorkerListenerSlots<L, W> {
    record Selection<W>(int index, W previous) {}

    private final int size;
    private final IdentityHashMap<L, SlotSet<W>> listeners = new IdentityHashMap<>();

    private static final class SlotSet<W> {
        final Object[] workers;
        int cursor;

        SlotSet(int size) {
            workers = new Object[size];
        }

        @SuppressWarnings("unchecked")
        W at(int index) {
            return (W) workers[index];
        }
    }

    HttpPooledWorkerListenerSlots(int size) {
        if (size < 1 || size > 4096) {
            throw new IllegalArgumentException("HTTP pool size must be 1..4096");
        }
        this.size = size;
    }

    synchronized Selection<W> next(L listener) {
        Objects.requireNonNull(listener, "listener");
        SlotSet<W> pool = listeners.computeIfAbsent(listener, ignored -> new SlotSet<>(size));
        int at = pool.cursor;
        pool.cursor = (at + 1) % size;
        return new Selection<>(at, pool.at(at));
    }

    synchronized void replace(L listener, Selection<W> selected, W worker) {
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(worker, "worker");
        SlotSet<W> pool = listeners.get(listener);
        if (pool == null || selected.index() < 0 || selected.index() >= size
                || pool.at(selected.index()) != selected.previous()) {
            throw new IllegalStateException("stale HTTP listener worker slot replacement");
        }
        pool.workers[selected.index()] = worker;
    }

    synchronized void forget(L listener) {
        Objects.requireNonNull(listener, "listener");
        // This drops only slot references, NEVER physical worker generations.
        // The independent registry keeps all generations until true exit.
        listeners.remove(listener);
    }

    synchronized void clear() {
        listeners.clear();
    }

    synchronized int listenerCount() {
        return listeners.size();
    }
}
