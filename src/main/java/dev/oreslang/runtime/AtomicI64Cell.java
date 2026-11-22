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
 * Default accessors use AtomicLong's sequentially consistent
 * volatile/CAS synchronization. Explicitly ordered load/store overloads
 * use Java opaque/acquire/release access where appropriate; RMWs and strong
 * compare-exchange conservatively strengthen requests to seq_cst.</p>
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


    // Memory-order codes are the compiler-owned core/atomic contract:
    // 0 relaxed, 1 acquire, 2 release, 3 acq_rel, 4 seq_cst.
    // Invalid combinations must fail *before* observing or mutating a cell.
    private static void validate_load_order(int order) {
        if (order != 0 && order != 1 && order != 4) {
            throw new IllegalArgumentException("invalid Atomic.load memory order");
        }
    }

    private static void validate_store_order(int order) {
        if (order != 0 && order != 2 && order != 4) {
            throw new IllegalArgumentException("invalid Atomic.store memory order");
        }
    }

    private static void validate_rmw_order(int order) {
        if (order < 0 || order > 4) {
            throw new IllegalArgumentException("invalid atomic RMW memory order");
        }
    }

    private static void validate_cas_orders(int success, int failure) {
        validate_rmw_order(success);
        if (failure != 0 && failure != 1 && failure != 4) {
            throw new IllegalArgumentException("invalid compare_exchange failure order");
        }
        boolean permitted = switch (success) {
            case 0, 2 -> failure == 0;
            case 1, 3 -> failure == 0 || failure == 1;
            case 4 -> failure == 0 || failure == 1 || failure == 4;
            default -> false;
        };
        if (!permitted) {
            throw new IllegalArgumentException("failure memory order exceeds compare_exchange success order");
        }
    }

    /**
     * Ordered guest lowering target. Java opaque access is stronger than a
     * C/C++ relaxed atomic, but it remains atomic and cannot be weaker than
     * the requested minimum. Acquire/SC use the corresponding AtomicLong API.
     */
    public long load(int order) {
        validate_load_order(order);
        return switch (order) {
            case 0 -> value.getOpaque();
            case 1 -> value.getAcquire();
            case 4 -> value.get();
            default -> throw new AssertionError("validated load order");
        };
    }

    public void store(long next, int order) {
        validate_store_order(order);
        switch (order) {
            case 0 -> value.setOpaque(next);
            case 2 -> value.setRelease(next);
            case 4 -> value.set(next);
            default -> throw new AssertionError("validated store order");
        }
    }

    /**
     * RMWs conservatively use AtomicLong's sequentially-consistent
     * operations for all admitted orderings. No weaker Java memory operation
     * or split read-then-write is substituted for an atomic transaction.
     */
    public long swap(long next, int order) {
        validate_rmw_order(order);
        return swap(next);
    }

    public long fetch_add(long delta, int order) {
        validate_rmw_order(order);
        return fetch_add(delta);
    }

    public long fetch_sub(long delta, int order) {
        validate_rmw_order(order);
        return fetch_sub(delta);
    }

    public long fetch_and(long mask, int order) {
        validate_rmw_order(order);
        return fetch_and(mask);
    }

    public long fetch_or(long mask, int order) {
        validate_rmw_order(order);
        return fetch_or(mask);
    }

    public long fetch_xor(long mask, int order) {
        validate_rmw_order(order);
        return fetch_xor(mask);
    }

    public CompareExchange compare_exchange(
            long expected, long next, int success_order, int failure_order) {
        validate_cas_orders(success_order, failure_order);
        // Strong SC CAS guarantees a single linearization point and
        // conservatively strengthens both the success and failure order.
        return compare_exchange(expected, next);
    }

    public record CompareExchange(boolean exchanged, long observed) {}
}
