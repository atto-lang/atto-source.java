package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Internal sequentially consistent i64 atomic primitive for AttoLang lowering.
 *
 * <p>This class is an implementation building block, not a guest-visible
 * {@code Atomic<T>} type. All arithmetic is checked to match AttoLang's normal
 * integer semantics, so overflow fails without publishing a wrapped value.
 * Instances may be shared only through a compiler-verified, authorized shared
 * allocation domain; Java references must not become guest actor-heap pointers.
 * All accesses use AtomicLong's volatile/CAS synchronization semantics.</p>
 */
public final class AtomicI64Cell {
    private final AtomicLong value;

    public AtomicI64Cell(long initialValue) {
        value = new AtomicLong(initialValue);
    }

    public long load() {
        return value.get();
    }

    public void store(long next) {
        value.set(next);
    }

    public long swap(long next) {
        return value.getAndSet(next);
    }

    public long fetch_add(long delta) {
        for (;;) {
            long old = value.get();
            long next = Math.addExact(old, delta);
            if (value.compareAndSet(old, next)) return old;
        }
    }

    public long fetch_sub(long delta) {
        for (;;) {
            long old = value.get();
            long next = Math.subtractExact(old, delta);
            if (value.compareAndSet(old, next)) return old;
        }
    }

    public long fetch_and(long mask) {
        return value.getAndUpdate(old -> old & mask);
    }

    public long fetch_or(long mask) {
        return value.getAndUpdate(old -> old | mask);
    }

    public long fetch_xor(long mask) {
        return value.getAndUpdate(old -> old ^ mask);
    }

    /**
     * Strong compare-and-exchange: the returned observed value is the value
     * seen at the linearization point; success means it equaled expected.
     */
    public CompareExchange compare_exchange(long expected, long next) {
        long observed = value.compareAndExchange(expected, next);
        return new CompareExchange(observed == expected, observed);
    }

    public record CompareExchange(boolean exchanged, long observed) {}
}
