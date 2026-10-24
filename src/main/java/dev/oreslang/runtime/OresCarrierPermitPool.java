package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * Nonblocking physical execution-carrier permit coordinator for one OresVM.
 *
 * <p>Every execution domain keeps one protected reserve. Capacity above those
 * reserves is borrowable, but an above-reserve acquisition is denied whenever
 * granting it would leave too few free permits for currently unmet reserves.
 * Acquisition never waits.
 *
 * <p>JDK 25 ThreadPoolExecutor starts workers through its internal thread
 * container rather than virtual-dispatching a Thread.start() override. Permit
 * acquisition therefore happens in ThreadFactory.newThread(), immediately
 * before executor startup. The executor must reconcile factory-created threads
 * after each synchronous worker-creation attempt so abandoned NEW threads
 * return their permits. Started workers release on worker-loop exit.
 */
final class OresCarrierPermitPool {
    enum Domain { CONTROL, PRIVATE, SHARED, UNTRUSTED, LIFECYCLE }

    record Snapshot(
            int maximum,
            int totalHeld,
            int free,
            int controlHeld,
            int privateHeld,
            int sharedHeld,
            int untrustedHeld,
            int lifecycleHeld,
            long deniedCapacity,
            long deniedReserveProtection) {
        Snapshot {
            if (maximum < Domain.values().length || maximum > 256
                    || totalHeld < 0 || totalHeld > maximum
                    || free != maximum - totalHeld
                    || controlHeld < 0 || privateHeld < 0 || sharedHeld < 0
                    || untrustedHeld < 0 || lifecycleHeld < 0
                    || (long) controlHeld + privateHeld + sharedHeld
                            + untrustedHeld + lifecycleHeld != totalHeld
                    || deniedCapacity < 0 || deniedReserveProtection < 0) {
                throw new IllegalArgumentException("invalid physical carrier permit snapshot");
            }
        }
    }

    interface ReconciliableThreadFactory extends ThreadFactory {
        /**
         * Release permits for Thread objects created by the current caller
         * that are still NEW after its synchronous executor operation returns.
         */
        void reconcileCreatedThreads();

        /**
         * ThreadPoolExecutor termination can publish before the outer
         * ThreadFactory wrapper has returned its physical permit. Fence that
         * final wrapper exit separately.
         */
        boolean awaitPhysicalTermination(long timeout, TimeUnit unit)
                throws InterruptedException;
    }

    private final int maximum;
    private final EnumMap<Domain, Integer> held = new EnumMap<>(Domain.class);
    private int total;
    private long deniedCapacity;
    private long deniedReserveProtection;

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
        if (total >= maximum) {
            deniedCapacity = saturatingIncrement(deniedCapacity);
            return null;
        }

        int current = held.get(domain);
        if (current >= 1) {
            int freeAfter = maximum - (total + 1);
            int unmetAfter = 0;
            for (Domain other : Domain.values()) {
                int count = held.get(other);
                if (other == domain) count++;
                if (count < 1) unmetAfter++;
            }
            if (freeAfter < unmetAfter) {
                deniedReserveProtection = saturatingIncrement(deniedReserveProtection);
                return null;
            }
        }

        held.put(domain, current + 1);
        total++;
        verifyLocked();
        return new Permit(this, domain);
    }

    /**
     * Atomic reservation primitive for a future native implementation.
     * It is intentionally not wired to nativeStart yet: Java lacks proof that
     * a partially attached pthread has physically exited after startup failure.
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

    synchronized Snapshot snapshot() {
        return new Snapshot(
                maximum,
                total,
                maximum - total,
                held.get(Domain.CONTROL),
                held.get(Domain.PRIVATE),
                held.get(Domain.SHARED),
                held.get(Domain.UNTRUSTED),
                held.get(Domain.LIFECYCLE),
                deniedCapacity,
                deniedReserveProtection);
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
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

    ReconciliableThreadFactory threadFactory(
            Domain domain,
            String prefix,
            UnaryOperator<Runnable> decorateWorkerLoop) {
        return new PermitThreadFactory(
                this,
                Objects.requireNonNull(domain, "domain"),
                Objects.requireNonNull(prefix, "prefix"),
                Objects.requireNonNull(decorateWorkerLoop, "decorateWorkerLoop"));
    }

    ReconciliableThreadFactory threadFactory(Domain domain, String prefix) {
        return threadFactory(domain, prefix, UnaryOperator.identity());
    }

    static void reconcile(ThreadFactory factory) {
        if (factory instanceof ReconciliableThreadFactory reconciliable)
            reconciliable.reconcileCreatedThreads();
    }

    static boolean awaitPhysicalTermination(
            ThreadFactory factory, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (!(factory instanceof ReconciliableThreadFactory reconciliable))
            return true;
        return reconciliable.awaitPhysicalTermination(timeout, unit);
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

    private static final class PermitThreadFactory implements ReconciliableThreadFactory {
        private final OresCarrierPermitPool permits;
        private final Domain domain;
        private final String prefix;
        private final UnaryOperator<Runnable> decorateWorkerLoop;
        private final java.util.concurrent.atomic.AtomicInteger next =
                new java.util.concurrent.atomic.AtomicInteger();
        private final ConcurrentHashMap<Thread, WorkerLease> live = new ConcurrentHashMap<>();
        private final Object physicalDrain = new Object();
        private final ThreadLocal<ArrayList<Thread>> createdByCaller =
                ThreadLocal.withInitial(ArrayList::new);

        PermitThreadFactory(
                OresCarrierPermitPool permits,
                Domain domain,
                String prefix,
                UnaryOperator<Runnable> decorateWorkerLoop) {
            this.permits = permits;
            this.domain = domain;
            this.prefix = prefix;
            this.decorateWorkerLoop = decorateWorkerLoop;
        }

        private static final class WorkerLease {
            private static final int RESERVED = 0;
            private static final int ENTERED = 1;
            private static final int RELEASED = 2;

            private final Permit permit;
            private final AtomicInteger state = new AtomicInteger(RESERVED);

            private WorkerLease(Permit permit) { this.permit = permit; }

            boolean enter() {
                return state.compareAndSet(RESERVED, ENTERED);
            }

            boolean abandon() {
                if (!state.compareAndSet(RESERVED, RELEASED)) return false;
                permit.close();
                return true;
            }

            void retire() {
                if (!state.compareAndSet(ENTERED, RELEASED)) {
                    throw new IllegalStateException(
                            "JVM carrier permit retired from invalid lease state "
                                    + state.get());
                }
                permit.close();
            }
        }

        @Override
        public Thread newThread(Runnable workerLoop) {
            Objects.requireNonNull(workerLoop, "workerLoop");
            Permit permit = permits.tryAcquire(domain);
            if (permit == null) {
                // ThreadFactory explicitly permits null when creation policy
                // refuses another worker. ThreadPoolExecutor can then queue or
                // reject according to its normal bounded semantics.
                return null;
            }

            final Runnable decorated;
            try {
                decorated = Objects.requireNonNull(
                        decorateWorkerLoop.apply(workerLoop),
                        "decorated workerLoop");
            } catch (RuntimeException | Error failure) {
                permit.close();
                throw failure;
            }

            WorkerLease lease = new WorkerLease(permit);
            final Thread thread;
            try {
                thread = new Thread(() -> {
                    Thread current = Thread.currentThread();
                    WorkerLease owned = live.get(current);
                    if (owned == null || owned != lease || !lease.enter()) {
                        // If reconciliation won a startup race, never execute
                        // runtime work without a physical execution permit.
                        live.remove(current, lease);
                        signalPhysicalDrain();
                        return;
                    }
                    try {
                        decorated.run();
                    } finally {
                        // processWorkerExit() may synchronously attempt a
                        // replacement on this same worker thread. Reconcile any
                        // replacement Thread object that was created but never
                        // entered before retiring this carrier.
                        reconcileCreatedThreads();
                        lease.retire();
                        if (!live.remove(current, lease)) {
                            throw new IllegalStateException(
                                    "JVM carrier lease disappeared before retirement");
                        }
                        signalPhysicalDrain();
                    }
                }, prefix + next.incrementAndGet());
                thread.setDaemon(true);
                live.put(thread, lease);
                try {
                    createdByCaller.get().add(thread);
                } catch (RuntimeException | Error bookkeepingFailure) {
                    if (lease.abandon()) {
                        live.remove(thread, lease);
                        signalPhysicalDrain();
                    }
                    throw bookkeepingFailure;
                }
                return thread;
            } catch (RuntimeException | Error failure) {
                if (lease.abandon()) signalPhysicalDrain();
                throw failure;
            }
        }

        @Override
        public void reconcileCreatedThreads() {
            ArrayList<Thread> created = createdByCaller.get();
            if (created.isEmpty()) {
                createdByCaller.remove();
                return;
            }
            try {
                for (Thread thread : created) {
                    WorkerLease lease = live.get(thread);
                    if (lease == null || thread.getState() != Thread.State.NEW) continue;
                    if (lease.abandon()) {
                        live.remove(thread, lease);
                        signalPhysicalDrain();
                    }
                }
            } finally {
                created.clear();
                createdByCaller.remove();
            }
        }

        private void signalPhysicalDrain() {
            synchronized (physicalDrain) {
                physicalDrain.notifyAll();
            }
        }

        @Override
        public boolean awaitPhysicalTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            Objects.requireNonNull(unit, "unit");
            if (timeout < 0L)
                throw new IllegalArgumentException("timeout must be non-negative");
            long nanos = unit.toNanos(timeout);
            long started = System.nanoTime();
            synchronized (physicalDrain) {
                while (!live.isEmpty()) {
                    if (nanos <= 0L) return false;
                    TimeUnit.NANOSECONDS.timedWait(physicalDrain, nanos);
                    long elapsed = System.nanoTime() - started;
                    nanos = Math.max(0L, unit.toNanos(timeout) - elapsed);
                }
            }
            return true;
        }

    }
}
