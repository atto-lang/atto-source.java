package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

final class NioCompletionDispatcher {
    private final AdaptiveCarrierExecutor owned;
    private final Executor fallback;
    private final IntSupplier reactorCount;
    private final ConcurrentLinkedDeque<Delivery> staged = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean dispatching = new AtomicBoolean();
    private final AtomicBoolean retryRequested = new AtomicBoolean();
    private final AtomicBoolean bootstrapInFlight = new AtomicBoolean();
    private final AtomicInteger unexpectedFailures = new AtomicInteger();
    private volatile Executor bound;
    private volatile boolean coldBootstrap;
    private volatile boolean bootstrapped;

    NioCompletionDispatcher(
            int maxPending,
            Executor vmCompletionExecutor,
            ThreadFactory ownedFactory,
            IntSupplier reactorCount) {
        this.reactorCount = Objects.requireNonNull(reactorCount, "reactorCount");
        if (vmCompletionExecutor == null) {
            ThreadFactory factory = ownedFactory != null
                    ? ownedFactory
                    : Thread.ofPlatform().daemon(true).name("ores-nio-completion-", 0).factory();
            owned = new AdaptiveCarrierExecutor(0, 2, maxPending, factory);
            fallback = owned;
        } else {
            owned = null;
            fallback = vmCompletionExecutor;
        }
    }

    boolean usesVmCarriers() { return bound != null || owned == null; }
    int carrierCount() { return owned == null ? 0 : owned.getPoolSize(); }
    int stagedCount() { return staged.size(); }
    int unexpectedFailures() { return unexpectedFailures.get(); }

    void bind(Executor executor, boolean coldStartOffReactor) {
        if (bound != null) throw new IllegalStateException("NIO completion scheduler already bound");
        bound = Objects.requireNonNull(executor, "executor");
        coldBootstrap = coldStartOffReactor;
    }

    private final class Delivery implements Runnable {
        private final Runnable body;
        private final AtomicBoolean claimed = new AtomicBoolean();
        Delivery(Runnable body) { this.body = body; }
        @Override public void run() {
            if (NioReadinessGroup.onReactorThread())
                throw new RejectedExecutionException(
                        "NIO completion callback cannot execute on reactor thread");
            if (!claimed.compareAndSet(false, true)) return;
            staged.remove(this);
            OresVM.runAsNioWorker(body);
        }
    }

    void dispatch(Runnable body) {
        Executor target = bound;
        if (target == null) {
            fallback.execute(() -> OresVM.runAsNioWorker(body));
            return;
        }
        staged.addLast(new Delivery(body));
        retry();
    }

    private void bootstrap(Executor target) {
        if (!coldBootstrap || owned == null || bootstrapped || reactorCount.getAsInt() == 0
                || !bootstrapInFlight.compareAndSet(false, true)) return;
        try {
            owned.execute(() -> {
                boolean admitted = false;
                try {
                    Delivery next = staged.pollFirst();
                    if (next == null) return;
                    if (next.claimed.get()) { admitted = true; return; }
                    try {
                        target.execute(next);
                        admitted = true;
                    } catch (RuntimeException | Error failure) {
                        if (!(failure instanceof RejectedExecutionException))
                            unexpectedFailures.incrementAndGet();
                        if (!next.claimed.get()) staged.addFirst(next);
                        else admitted = true;
                        if (failure instanceof VirtualMachineError fatal) throw fatal;
                    }
                } finally {
                    if (admitted) bootstrapped = true;
                    bootstrapInFlight.set(false);
                    if (admitted) {
                        owned.shutdown();
                        retry();
                    }
                }
            });
        } catch (RuntimeException | Error failure) {
            bootstrapInFlight.set(false);
            if (!(failure instanceof RejectedExecutionException))
                unexpectedFailures.incrementAndGet();
            if (failure instanceof VirtualMachineError fatal) throw fatal;
        }
    }

    void retry() {
        Executor target = bound;
        if (target == null && owned != null) return;
        if (target == null) target = fallback;
        if (bound != null && coldBootstrap && !bootstrapped
                && reactorCount.getAsInt() > 0 && owned != null) {
            bootstrap(target);
            return;
        }
        retryRequested.set(true);
        if (!dispatching.compareAndSet(false, true)) return;
        try {
            do {
                retryRequested.set(false);
                for (int i = 0; i < 128; i++) {
                    Delivery next = staged.pollFirst();
                    if (next == null) break;
                    if (next.claimed.get()) continue;
                    try {
                        target.execute(next);
                    } catch (RuntimeException | Error failure) {
                        if (!(failure instanceof RejectedExecutionException))
                            unexpectedFailures.incrementAndGet();
                        if (!next.claimed.get()) staged.addFirst(next);
                        if (failure instanceof VirtualMachineError fatal) throw fatal;
                        return;
                    }
                }
            } while (retryRequested.get() && !staged.isEmpty());
        } finally {
            dispatching.set(false);
        }
        if (retryRequested.get() && !staged.isEmpty()) retry();
    }

    void shutdownOwned() {
        if (owned != null) owned.shutdown();
    }

    boolean awaitOwned(long timeoutNanos) throws InterruptedException {
        return owned == null
                || owned.awaitTermination(Math.max(0L, timeoutNanos), TimeUnit.NANOSECONDS);
    }

    boolean ownsCompletionExecutor() { return owned != null; }
    boolean isBound() { return bound != null; }
}
