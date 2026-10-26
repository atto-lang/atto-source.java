package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

final class AdaptiveCarrierExecutor extends ThreadPoolExecutor {
    private static final ThreadLocal<Boolean> FORBID_WORKER_START =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final int minimum;
    private final AtomicInteger startedWorkers;
    private final ReentrantLock admission = new ReentrantLock();
    private int admittedTurns;
    private volatile Runnable afterTaskHook;

    void onTaskCompleted(Runnable hook) { afterTaskHook = hook; }

    AdaptiveCarrierExecutor(int minimum, int maximum, int queueCapacity, ThreadFactory factory) {
        this(minimum, maximum, queueCapacity,
                Objects.requireNonNull(factory, "factory"), new AtomicInteger());
    }

    private AdaptiveCarrierExecutor(int minimum, int maximum, int queueCapacity,
            ThreadFactory factory, AtomicInteger startedWorkers) {
        super(validateMinimum(minimum), validateMaximum(minimum, maximum), 30L,
                TimeUnit.SECONDS, new ArrayBlockingQueue<>(validateCapacity(queueCapacity)),
                new TrackingThreadFactory(factory, startedWorkers), new AbortPolicy());
        this.minimum = minimum;
        this.startedWorkers = startedWorkers;
        allowCoreThreadTimeOut(false);
    }

    private static int validateMinimum(int value) {
        if (value < 0) throw new IllegalArgumentException("minimum carriers must be >= 0");
        return value;
    }

    private static int validateMaximum(int minimum, int maximum) {
        if (maximum < 1 || maximum < minimum || maximum > 256)
            throw new IllegalArgumentException(
                    "maximum carriers must be between max(1,min) and 256");
        return maximum;
    }

    private static int validateCapacity(int value) {
        if (value < 1) throw new IllegalArgumentException("ready queue capacity must be >= 1");
        return value;
    }

    private static final class TrackingThreadFactory
            implements OresCarrierPermitPool.ReconciliableThreadFactory {
        private final ThreadFactory delegate;
        private final AtomicInteger startedWorkers;

        TrackingThreadFactory(ThreadFactory delegate, AtomicInteger startedWorkers) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.startedWorkers = startedWorkers;
        }

        @Override public Thread newThread(Runnable workerLoop) {
            if (Boolean.TRUE.equals(FORBID_WORKER_START.get()))
                throw new RejectedExecutionException(
                        "carrier creation forbidden for no-growth admission");
            Thread thread = delegate.newThread(() -> {
                startedWorkers.incrementAndGet();
                try {
                    workerLoop.run();
                } finally {
                    if (startedWorkers.decrementAndGet() < 0) {
                        startedWorkers.incrementAndGet();
                        throw new IllegalStateException(
                                "JVM carrier started-worker accounting underflow");
                    }
                }
            });
            if (thread == null)
                throw new RejectedExecutionException("thread factory returned null");
            return thread;
        }

        @Override public void reconcileCreatedThreads() {
            OresCarrierPermitPool.reconcile(delegate);
        }

        @Override public boolean awaitPhysicalTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return OresCarrierPermitPool.awaitPhysicalTermination(delegate, timeout, unit);
        }
    }

    private void reconcileCreatedThreads() {
        OresCarrierPermitPool.reconcile(getThreadFactory());
    }

    void executeWithoutCarrierGrowth(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (!admission.tryLock())
            throw new RejectedExecutionException("JVM carrier admission is busy");
        try {
            if (isShutdown()) throw new RejectedExecutionException("carrier pool stopped");
            if (startedWorkers.get() == 0)
                throw new RejectedExecutionException("no fully started JVM carrier available");
            if (admittedTurns == Integer.MAX_VALUE)
                throw new RejectedExecutionException("runnable admission counter exhausted");
            NoGrowthTurn queued = new NoGrowthTurn(task);
            boolean previous = Boolean.TRUE.equals(FORBID_WORKER_START.get());
            FORBID_WORKER_START.set(Boolean.TRUE);
            try {
                super.execute(queued);
                admittedTurns++;
            } catch (RuntimeException | Error failure) {
                getQueue().remove(queued);
                throw failure;
            } finally {
                reconcileCreatedThreads();
                if (previous) FORBID_WORKER_START.set(Boolean.TRUE);
                else FORBID_WORKER_START.remove();
            }
        } finally {
            admission.unlock();
        }
    }

    boolean readyForNoGrowthAdmission() {
        return startedWorkers.get() > 0 && !isShutdown();
    }

    int startedWorkerCount() { return startedWorkers.get(); }
    int minimumCarriers() { return minimum; }

    private record NoGrowthTurn(Runnable command) implements Runnable {
        @Override public void run() { runContained(command); }
    }

    private record ContainedTurn(Runnable command) implements Runnable {
        @Override public void run() { runContained(command); }
    }

    private static void runContained(Runnable command) {
        try {
            command.run();
        } catch (RuntimeException | AssertionError failure) {
            dispatchUncaught(failure);
        } catch (Error fatal) {
            throw fatal;
        }
    }

    private static void dispatchUncaught(Throwable failure) {
        Thread current = Thread.currentThread();
        Thread.UncaughtExceptionHandler handler = current.getUncaughtExceptionHandler();
        if (handler == null) handler = Thread.getDefaultUncaughtExceptionHandler();
        if (handler == null) return;
        try { handler.uncaughtException(current, failure); }
        catch (RuntimeException | Error ignored) { }
    }

    @Override public List<Runnable> shutdownNow() {
        admission.lock();
        try {
            List<Runnable> drained;
            try { drained = super.shutdownNow(); }
            finally { reconcileCreatedThreads(); }
            if (drained.size() > admittedTurns)
                throw new IllegalStateException("carrier runnable credit underflow during shutdown");
            admittedTurns -= drained.size();
            ArrayList<Runnable> abandoned = new ArrayList<>(drained.size());
            for (Runnable task : drained) {
                if (task instanceof NoGrowthTurn wrapper) abandoned.add(wrapper.command());
                else if (task instanceof ContainedTurn wrapper) abandoned.add(wrapper.command());
                else abandoned.add(task);
            }
            return List.copyOf(abandoned);
        } finally {
            admission.unlock();
        }
    }

    @Override public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        admission.lock();
        try {
            if (isShutdown()) throw new RejectedExecutionException("carrier pool stopped");
            if (admittedTurns == Integer.MAX_VALUE)
                throw new RejectedExecutionException("runnable admission counter exhausted");
            int demand = admittedTurns + 1;
            int target = Math.min(getMaximumPoolSize(), Math.max(minimum, demand));
            if (getCorePoolSize() < target) setCorePoolSize(target);
            try {
                super.execute(new ContainedTurn(task));
                admittedTurns = demand;
            } catch (RuntimeException | Error failure) {
                int prior = Math.min(getMaximumPoolSize(), Math.max(minimum, admittedTurns));
                if (!isShutdown() && getCorePoolSize() > prior) setCorePoolSize(prior);
                throw failure;
            } finally {
                reconcileCreatedThreads();
            }
        } finally {
            admission.unlock();
        }
    }

    @Override public void setCorePoolSize(int value) {
        try { super.setCorePoolSize(value); }
        finally { reconcileCreatedThreads(); }
    }

    @Override public boolean prestartCoreThread() {
        try { return super.prestartCoreThread(); }
        finally { reconcileCreatedThreads(); }
    }

    @Override public int prestartAllCoreThreads() {
        try { return super.prestartAllCoreThreads(); }
        finally { reconcileCreatedThreads(); }
    }

    @Override public void shutdown() {
        admission.lock();
        try {
            super.shutdown();
            reconcileCreatedThreads();
        } finally {
            admission.unlock();
        }
    }

    @Override public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0L) throw new IllegalArgumentException("timeout must be non-negative");
        long budget = unit.toNanos(timeout);
        long start = System.nanoTime();
        if (!super.awaitTermination(timeout, unit)) return false;
        long remaining = Math.max(0L, budget - (System.nanoTime() - start));
        return OresCarrierPermitPool.awaitPhysicalTermination(
                getThreadFactory(), remaining, TimeUnit.NANOSECONDS);
    }

    @Override protected void afterExecute(Runnable task, Throwable failure) {
        try {
            super.afterExecute(task, failure);
        } finally {
            admission.lock();
            try {
                if (admittedTurns <= 0)
                    throw new IllegalStateException("carrier runnable credit underflow");
                int remaining = --admittedTurns;
                if (!isShutdown()) {
                    int target = Math.min(getMaximumPoolSize(), Math.max(minimum, remaining));
                    if (getCorePoolSize() > target) setCorePoolSize(target);
                }
            } finally {
                admission.unlock();
            }
        }
        Runnable callback = afterTaskHook;
        if (callback != null) {
            try { callback.run(); }
            catch (RuntimeException | AssertionError observerFailure) {
                dispatchUncaught(observerFailure);
            } catch (Error fatal) {
                throw fatal;
            }
        }
    }
}
