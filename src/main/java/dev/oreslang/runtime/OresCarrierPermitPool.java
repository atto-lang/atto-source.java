package dev.oreslang.runtime;

import java.util.EnumMap;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Nonblocking physical execution-carrier permit coordinator for one OresVM.
 *
 * <p>Every execution domain keeps one protected reserve. Capacity above those
 * reserves is borrowable, but an above-reserve acquisition is denied whenever
 * granting it would leave too few free permits for currently unmet reserves.
 * Acquisition never waits; callers either create a carrier immediately or
 * retain/stage their runnable work.
 */
final class OresCarrierPermitPool {
    enum Domain { CONTROL, PRIVATE, SHARED, UNTRUSTED, LIFECYCLE }

    private final int maximum;
    private final EnumMap<Domain, Integer> held = new EnumMap<>(Domain.class);
    private int total;

    OresCarrierPermitPool(int maximum) {
        if (maximum < Domain.values().length || maximum > 256)
            throw new IllegalArgumentException("physical carrier permit maximum must be 5..256");
        this.maximum = maximum;
        for (Domain domain : Domain.values()) held.put(domain, 0);
    }

    int maximum() { return maximum; }

    synchronized int totalHeld() { return total; }

    synchronized int held(Domain domain) {
        return held.get(Objects.requireNonNull(domain, "domain"));
    }

    synchronized Permit tryAcquire(Domain domain) {
        Objects.requireNonNull(domain, "domain");
        if (total >= maximum) return null;

        int current = held.get(domain);
        if (current >= 1) {
            int freeAfter = maximum - (total + 1);
            int unmetAfter = 0;
            for (Domain other : Domain.values()) {
                int count = held.get(other);
                if (other == domain) count++;
                if (count < 1) unmetAfter++;
            }
            if (freeAfter < unmetAfter) return null;
        }

        held.put(domain, current + 1);
        total++;
        verifyLocked();
        return new Permit(this, domain);
    }

    /**
     * Atomically reserve a fixed native carrier set before nativeStart().
     * Failure leaves the coordinator unchanged.
     */
    synchronized Permit[] tryAcquireMany(Domain domain, int count) {
        Objects.requireNonNull(domain, "domain");
        if (count < 1) throw new IllegalArgumentException("permit count must be positive");

        int originalDomain = held.get(domain);
        int originalTotal = total;
        Permit[] permits = new Permit[count];
        for (int i = 0; i < count; i++) {
            Permit permit = tryAcquire(domain);
            if (permit == null) {
                held.put(domain, originalDomain);
                total = originalTotal;
                verifyLocked();
                return null;
            }
            permits[i] = permit;
        }
        return permits;
    }

    private synchronized void release(Domain domain) {
        int current = held.get(domain);
        if (current <= 0 || total <= 0)
            throw new IllegalStateException("physical carrier permit underflow for " + domain);
        held.put(domain, current - 1);
        total--;
        verifyLocked();
    }

    private void verifyLocked() {
        int sum = 0;
        for (int count : held.values()) {
            if (count < 0) throw new IllegalStateException("negative physical carrier permit count");
            sum += count;
        }
        if (sum != total || total < 0 || total > maximum)
            throw new IllegalStateException("physical carrier permit accounting invariant failed");
    }

    /**
     * JVM factory whose permit is acquired immediately before Thread.start().
     * Creating a Java Thread object therefore consumes no physical permit.
     * A failed start rolls the permit back unless the worker loop already
     * entered concurrently; normal worker exit releases exactly once.
     */
    ThreadFactory threadFactory(
            Domain domain,
            String prefix,
            UnaryOperator<Runnable> decorateWorkerLoop) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(decorateWorkerLoop, "decorateWorkerLoop");
        AtomicInteger next = new AtomicInteger();
        return workerLoop -> {
            Runnable decorated = Objects.requireNonNull(
                    decorateWorkerLoop.apply(Objects.requireNonNull(workerLoop, "workerLoop")),
                    "decorated workerLoop");
            return new PermitThread(
                    decorated,
                    prefix + next.incrementAndGet(),
                    this,
                    domain);
        };
    }

    ThreadFactory threadFactory(Domain domain, String prefix) {
        return threadFactory(domain, prefix, UnaryOperator.identity());
    }

    static final class Permit implements AutoCloseable {
        private final OresCarrierPermitPool owner;
        private final Domain domain;
        private boolean released;

        private Permit(OresCarrierPermitPool owner, Domain domain) {
            this.owner = owner;
            this.domain = domain;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (released) return;
                released = true;
            }
            owner.release(domain);
        }
    }

    private static final class PermitThread extends Thread {
        private static final int NEW = 0;
        private static final int STARTING = 1;
        private static final int RUNNING = 2;
        private static final int RELEASED = 3;

        private final OresCarrierPermitPool permits;
        private final Domain domain;
        private final AtomicInteger state = new AtomicInteger(NEW);
        private final AtomicReference<Permit> permit = new AtomicReference<>();

        PermitThread(
                Runnable target,
                String name,
                OresCarrierPermitPool permits,
                Domain domain) {
            super(target, name);
            setDaemon(true);
            this.permits = permits;
            this.domain = domain;
        }

        @Override
        public synchronized void start() {
            if (!state.compareAndSet(NEW, STARTING))
                throw new IllegalThreadStateException("carrier thread already started");

            Permit acquired = permits.tryAcquire(domain);
            if (acquired == null) {
                state.set(RELEASED);
                throw new RejectedExecutionException(
                        "global physical carrier budget has no safe permit for " + domain);
            }
            permit.set(acquired);
            try {
                super.start();
            } catch (RuntimeException | Error startFailure) {
                if (state.compareAndSet(STARTING, RELEASED)) releasePermit();
                throw startFailure;
            }
        }

        @Override
        public void run() {
            if (!state.compareAndSet(STARTING, RUNNING)) {
                // A successful JVM start must publish STARTING before this
                // worker can enter. Anything else is a runtime invariant fault.
                releasePermit();
                throw new IllegalStateException("physical carrier entered without a start permit");
            }
            try {
                super.run();
            } finally {
                if (state.getAndSet(RELEASED) != RELEASED) releasePermit();
            }
        }

        private void releasePermit() {
            Permit owned = permit.getAndSet(null);
            if (owned != null) owned.close();
        }
    }
}
