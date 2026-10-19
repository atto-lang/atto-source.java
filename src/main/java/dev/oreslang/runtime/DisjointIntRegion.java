package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Host-only reference for move-only disjoint range capabilities.
 *
 * <p>This illustrates zero-mutex element access on statically disjoint slices,
 * NOT an Oreslang guest capability. Source lowering must prove linear ownership,
 * region/task lifetimes and trust-domain access before exposing permits. In a
 * running callback each loan is restricted to its Java carrier; a future
 * Oreslang implementation must instead bind it to a logical continuation.</p>
 */
public final class DisjointIntRegion implements AutoCloseable {
    private static final long CLOSED = Long.MIN_VALUE;
    private final int[] values;
    private final AtomicBoolean rootIssued = new AtomicBoolean();
    // High bit means no new admission. Low bits count in-flight callbacks.
    private final AtomicLong lifecycle = new AtomicLong();

    public DisjointIntRegion(int length) {
        if (length < 1) throw new IllegalArgumentException("region length must be positive");
        values = new int[length];
    }

    public int length() { return values.length; }

    private void enter() {
        for (;;) {
            long state = lifecycle.get();
            if ((state & CLOSED) != 0) throw new IllegalStateException("region retired");
            if (state == Long.MAX_VALUE) throw new IllegalStateException("region admission overflow");
            if (lifecycle.compareAndSet(state, state + 1)) return;
        }
    }

    private void exit() {
        long state = lifecycle.getAndDecrement();
        if ((state & Long.MAX_VALUE) == 0) throw new AssertionError("unbalanced region admission");
    }

    public Permit takeRoot() {
        enter();
        try {
            if (!rootIssued.compareAndSet(false, true)) {
                throw new IllegalStateException("exclusive root permit already issued");
            }
            return new Permit(0, values.length);
        } finally {
            exit();
        }
    }

    /**
     * Only allowed after retirement and *physical* quiescence of all callbacks.
     * This returns an independent copy, never a reference to protected storage.
     */
    public int[] snapshotAfterClose() {
        long state = lifecycle.get();
        if (state != CLOSED) {
            throw new IllegalStateException("region is not retired or callbacks remain active");
        }
        return values.clone();
    }

    @Override
    public void close() {
        lifecycle.getAndUpdate(state -> state | CLOSED);
        // Do not falsely claim that cancellation stopped a running callback.
        // Its admission remains pinned until it physically returns.
    }

    public final class Permit implements AutoCloseable {
        private static final int AVAILABLE = 0;
        private static final int BORROWED = 1;
        private static final int RETIRED = 2;

        private final int from;
        private final int to;
        private final AtomicInteger state = new AtomicInteger(AVAILABLE);

        private Permit(int from, int to) {
            this.from = from;
            this.to = to;
        }

        public int length() { return to - from; }

        /**
         * Consume this permit and mint N non-overlapping child permits.
         * The original permit cannot be reused or resurrected.
         */
        public List<Permit> split(int count) {
            if (count < 2 || count > length()) {
                throw new IllegalArgumentException("partition count out of range");
            }
            enter();
            try {
                if (!state.compareAndSet(AVAILABLE, RETIRED)) {
                    throw new IllegalStateException("permit is already moved, borrowed or retired");
                }
                var children = new ArrayList<Permit>(count);
                long length = length();
                for (int i = 0; i < count; i++) {
                    int begin = from + (int) (length * i / count);
                    int end = from + (int) (length * (i + 1) / count);
                    children.add(new Permit(begin, end));
                }
                return List.copyOf(children);
            } finally {
                exit();
            }
        }

        /**
         * A lexical exclusive callback. No locks are acquired per element.
         * Loans are invalid after the callback returns, even if captured.
         */
        public void withWrite(Consumer<Loan> body) {
            Objects.requireNonNull(body, "body");
            if (!state.compareAndSet(AVAILABLE, BORROWED)) {
                throw new IllegalStateException("permit is already moved, borrowed or retired");
            }
            try {
                enter();
                try {
                    Loan loan = new Loan(this, Thread.currentThread());
                    try {
                        body.accept(loan);
                    } finally {
                        loan.active = false;
                    }
                } finally {
                    exit();
                }
            } finally {
                state.set(AVAILABLE);
            }
        }

        @Override
        public void close() {
            if (!state.compareAndSet(AVAILABLE, RETIRED)) {
                throw new IllegalStateException("cannot retire a borrowed or moved permit");
            }
        }
    }

    public final class Loan {
        private final Permit owner;
        private final Thread carrier;
        private volatile boolean active = true;

        private Loan(Permit owner, Thread carrier) {
            this.owner = owner;
            this.carrier = carrier;
        }

        /**
         * A running callback is physically pinned by enter()/exit(). close()
         * stops new admissions; it MUST NOT revoke a callback halfway through
         * a multi-element update and publish a partial state as a snapshot.
         * A loan expires only when its lexical callback has unwound.
         */
        private void requireLive() {
            if (!active || Thread.currentThread() != carrier) {
                throw new IllegalStateException("stale or foreign-carrier loan");
            }
        }

        private int checkedIndex(int index) {
            requireLive();
            if (index < 0 || index >= owner.length()) {
                throw new IndexOutOfBoundsException(index);
            }
            return owner.from + index;
        }

        public int length() {
            requireLive();
            return owner.length();
        }
        public int get(int index) { return values[checkedIndex(index)]; }
        public void set(int index, int value) { values[checkedIndex(index)] = value; }
    }
}
