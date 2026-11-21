package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Host-only, generation-fenced actor state region. Its instances and scopes
 * are intentionally package-private: this is NOT a guest-reachable API, a
 * compiler-issued authority token, or a substitute for actor HIR/MIR checks.
 *
 * A trusted actor scheduler supplies logical handler identities. FIFO grants
 * admit concurrent readers or one writer, never both. No user callback is
 * executed under the region monitor. Scopes remain active until close(), so
 * retirement cannot reclaim backing storage while a handler is in-flight.
 * Scalar reads and checked writes are atomic with the STOP generation fence.
 */
final class ActorCriticalStateRegionV1 {
    enum Mode { READ, WRITE }

    private record Pending(Object handler, Mode mode) { }

    private final Object monitor = new Object();
    private final long generation;
    private final long[] state;
    private final int maxPending;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final Set<Object> owners = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean retired;
    private boolean writerActive;
    private int activeReaders;

    ActorCriticalStateRegionV1(long generation, long[] initialState, int maxPending) {
        if (generation <= 0 || generation == Long.MAX_VALUE
                || maxPending <= 0 || maxPending > 65_536
                || initialState == null || initialState.length == 0
                || initialState.length > 32) {
            throw new IllegalArgumentException("invalid actor state region parameters");
        }
        this.generation = generation;
        this.state = initialState.clone();
        this.maxPending = maxPending;
    }

    long generation() { return generation; }

    /** Nonblocking logical admission; the scheduler must call admitReady(). */
    void request(Object handler, long expectedGeneration, Mode mode) {
        Objects.requireNonNull(handler, "handler identity");
        Objects.requireNonNull(mode, "critical mode");
        synchronized (monitor) {
            requireCurrent(expectedGeneration);
            if (!owners.add(handler)) {
                throw new IllegalStateException("REENTRANT_ACTOR_STATE_LOAN");
            }
            if (pending.size() == maxPending) {
                owners.remove(handler);
                throw new IllegalStateException("ACTOR_STATE_QUEUE_FULL");
            }
            pending.addLast(new Pending(handler, mode));
        }
    }

    /** Cancel only a still-queued request; never force-release an active scope. */
    boolean cancelPending(Object handler) {
        Objects.requireNonNull(handler, "handler identity");
        synchronized (monitor) {
            for (var it = pending.iterator(); it.hasNext();) {
                Pending waiter = it.next();
                if (waiter.handler() == handler) {
                    it.remove();
                    owners.remove(handler);
                    return true;
                }
            }
            return false;
        }
    }

    /** FIFO: head writer blocks newer readers, but read batches can coexist. */
    List<Scope> admitReady() {
        synchronized (monitor) {
            if (retired || writerActive || pending.isEmpty()) return List.of();
            if (activeReaders > 0 && pending.peekFirst().mode() == Mode.WRITE) {
                return List.of();
            }
            List<Scope> admitted = new ArrayList<>();
            if (pending.peekFirst().mode() == Mode.WRITE) {
                Pending writer = pending.removeFirst();
                writerActive = true;
                admitted.add(new Scope(writer.handler(), Mode.WRITE));
            } else {
                while (!pending.isEmpty() && pending.peekFirst().mode() == Mode.READ) {
                    Pending reader = pending.removeFirst();
                    activeReaders++;
                    admitted.add(new Scope(reader.handler(), Mode.READ));
                }
            }
            return List.copyOf(admitted);
        }
    }

    int pendingCount() {
        synchronized (monitor) { return pending.size(); }
    }

    int activeCount() {
        synchronized (monitor) { return activeReaders + (writerActive ? 1 : 0); }
    }

    /** STOP is nonblocking and never revokes physical ownership of a scope. */
    void retire(long nextGeneration) {
        synchronized (monitor) {
            if (nextGeneration <= generation) {
                throw new IllegalArgumentException("next actor incarnation must advance generation");
            }
            if (retired) return;
            retired = true;
            for (Pending waiter : pending) owners.remove(waiter.handler());
            pending.clear();
        }
    }

    boolean mayReclaim() {
        synchronized (monitor) {
            return retired && !writerActive && activeReaders == 0;
        }
    }

    private void requireCurrent(long expectedGeneration) {
        if (expectedGeneration != generation) {
            throw new IllegalStateException("STALE_ACTOR_GENERATION");
        }
        if (retired) throw new IllegalStateException("ACTOR_STATE_RETIRED");
    }

    final class Scope implements AutoCloseable {
        private final Object handler;
        private final Mode mode;
        private boolean closed;

        private Scope(Object handler, Mode mode) {
            this.handler = handler;
            this.mode = mode;
        }

        Mode mode() { return mode; }

        long readInt(int slot) {
            synchronized (monitor) {
                requireLive();
                requireSlot(slot);
                return state[slot];
            }
        }

        /** Compute first, commit only on checked success while STOP is fenced. */
        long checkedAddInt(int slot, long delta) {
            synchronized (monitor) {
                requireLive();
                if (mode != Mode.WRITE) {
                    throw new IllegalStateException("WRITE_REQUIRES_CRITICAL_WRITE");
                }
                requireSlot(slot);
                long next = Math.addExact(state[slot], delta);
                state[slot] = next;
                return next;
            }
        }

        private void requireSlot(int slot) {
            if (slot < 0 || slot >= state.length) {
                throw new IndexOutOfBoundsException("unknown sealed state slot");
            }
        }

        private void requireLive() {
            requireCurrent(generation);
            if (closed || !owners.contains(handler)
                    || (mode == Mode.WRITE ? !writerActive : activeReaders <= 0)) {
                throw new IllegalStateException("ACTOR_STATE_SCOPE_CLOSED");
            }
        }

        @Override public void close() {
            synchronized (monitor) {
                if (closed) return;
                closed = true;
                if (!owners.remove(handler)) {
                    throw new IllegalStateException("ACTOR_STATE_OWNER_CORRUPTED");
                }
                if (mode == Mode.WRITE) {
                    if (!writerActive) throw new IllegalStateException("ACTOR_STATE_WRITER_CORRUPTED");
                    writerActive = false;
                } else {
                    if (activeReaders <= 0) throw new IllegalStateException("ACTOR_STATE_READER_CORRUPTED");
                    activeReaders--;
                }
            }
        }
    }
}
