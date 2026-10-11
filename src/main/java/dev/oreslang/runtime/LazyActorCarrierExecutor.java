package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Configured actor carrier pool with zero physical workers until its first
 * accepted executable command. Each pool owns its delegate exactly once;
 * closed-before-activation pools never load liboresthread or start threads.
 *
 * <p>Pool creation, execution admission, and shutdown share one monitor.
 * Failed native activation cannot publish a command to a missing carrier.
 * The delegate remains the sole owner of queued work and existing shutdown
 * semantics. No actor, ActorGroup, or scheduler is assigned a physical thread.</p>
 */
final class LazyActorCarrierExecutor extends AbstractExecutorService {
    private final Supplier<? extends ExecutorService> factory;
    private final boolean nativePreferred;
    private final Object lifecycle = new Object();
    private ExecutorService delegate;
    private boolean shutdown;

    LazyActorCarrierExecutor(
            Supplier<? extends ExecutorService> factory,
            boolean nativePreferred) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.nativePreferred = nativePreferred;
    }

    boolean activated() {
        synchronized (lifecycle) {
            return delegate != null;
        }
    }

    boolean nativeCarriers() {
        synchronized (lifecycle) {
            return delegate == null ? nativePreferred
                    : delegate instanceof NativeCarrierExecutor;
        }
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        synchronized (lifecycle) {
            if (shutdown) throw new RejectedExecutionException(
                    "actor carrier pool closed before work admission");
            if (delegate == null) {
                // A throwing factory leaves CONFIGURED intact. Retry remains
                // possible only if the owning actor runtime is still open.
                ExecutorService next = Objects.requireNonNull(
                        factory.get(), "actor carrier factory returned null");
                delegate = next;
            }
            delegate.execute(command);
        }
    }

    @Override
    public void shutdown() {
        synchronized (lifecycle) {
            if (shutdown) return;
            shutdown = true;
            if (delegate != null) delegate.shutdown();
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        synchronized (lifecycle) {
            shutdown = true;
            return delegate == null ? List.of() : delegate.shutdownNow();
        }
    }

    @Override
    public boolean isShutdown() {
        synchronized (lifecycle) {
            return shutdown;
        }
    }

    @Override
    public boolean isTerminated() {
        synchronized (lifecycle) {
            return shutdown && (delegate == null || delegate.isTerminated());
        }
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        final ExecutorService active;
        synchronized (lifecycle) {
            if (!shutdown) return false;
            active = delegate;
        }
        return active == null || active.awaitTermination(timeout, unit);
    }
}
