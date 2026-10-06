package dev.oreslang.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Runtime-owned structured deadline, propagated across scheduler/actor turns. */
public final class OresDeadlineScope implements AutoCloseable {
    private static final ThreadLocal<OresDeadlineScope> CURRENT = new ThreadLocal<>();
    // Independent of CONTROL, actor and user pools. No guest code runs here.
    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();
    private final Object turnLock = new Object();
    private int activeTurns;
    private final Set<Runnable> quiescenceWaiters = ConcurrentHashMap.newKeySet();
    private final OresDeadlineScope parent;
    private final long deadlineNanos;
    private final String label;
    private final Set<Consumer<Throwable>> members = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Throwable> terminal = new AtomicReference<>();
    private final ScheduledFuture<?> timer;
    private final Runnable detachParent;

    private static ScheduledThreadPoolExecutor watchdog() {
        var executor = new ScheduledThreadPoolExecutor(1, runnable ->
                Thread.ofPlatform().daemon(true).name("ores-deadline-watchdog").unstarted(runnable));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    public OresDeadlineScope(Duration timeout, String label) {
        Objects.requireNonNull(timeout, "timeout");
        this.label = Objects.requireNonNull(label, "label");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must be non-negative");
        long nanos;
        try { nanos = timeout.toNanos(); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("timeout is too large", overflow); }
        long now = System.nanoTime();
        parent = current();
        long remaining = parent == null ? nanos : Math.min(nanos, Math.max(0L, parent.deadlineNanos - now));
        deadlineNanos = now + remaining;
        detachParent = parent == null ? () -> { } : parent.register(this::terminate);
        timer = WATCHDOG.schedule(() -> terminate(timeoutError()), remaining, TimeUnit.NANOSECONDS);
        if (terminal.get() != null) timer.cancel(false);
    }

    public static OresDeadlineScope current() { return CURRENT.get(); }

    static OresDeadlineScope enter(OresDeadlineScope scope) {
        OresDeadlineScope prior = current();
        if (scope == null) CURRENT.remove(); else CURRENT.set(scope);
        return prior;
    }

    Runnable register(Consumer<Throwable> member) {
        members.add(member);
        Throwable failure = terminal.get();
        if (failure != null && members.remove(member)) member.accept(failure);
        return () -> members.remove(member);
    }

    static <T> OresFuture<T> own(OresFuture<T> future) {
        OresDeadlineScope scope = current();
        if (scope == null || future.isDone()) return future;
        Runnable detach = scope.register(future::abortFromRuntime);
        future.whenCompleteRuntime((value, failure) -> detach.run());
        return future;
    }

    // Scope owners publish only after every descendant has left its guest
    // boundary. This is asynchronous; the watchdog never joins a carrier.
    void acquireTurn() {
        if (parent != null) parent.acquireTurn();
        synchronized (turnLock) { activeTurns++; }
    }

    void releaseTurn() {
        Set<Runnable> ready = Set.of();
        synchronized (turnLock) {
            if (--activeTurns < 0) throw new IllegalStateException("scope turn accounting underflow");
            if (activeTurns == 0) {
                ready = Set.copyOf(quiescenceWaiters);
                quiescenceWaiters.clear();
            }
        }
        if (parent != null) parent.releaseTurn();
        for (Runnable waiter : ready) waiter.run();
    }

    boolean whenQuiescent(Runnable waiter) {
        synchronized (turnLock) {
            if (activeTurns == 0) return true;
            quiescenceWaiters.add(waiter);
            return false;
        }
    }

    public void checkpoint() {
        Throwable failure = terminal.get();
        if (failure == null && System.nanoTime() - deadlineNanos >= 0) {
            terminate(timeoutError());
            failure = terminal.get();
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
    }

    private OresTimeoutError timeoutError() {
        return new OresTimeoutError("deadline expired in " + label);
    }

    private void terminate(Throwable failure) {
        if (!terminal.compareAndSet(null, failure)) return;
        // Cancellation and completion may detach concurrently. Taking a member
        // out before invoking it also guarantees exactly-once delivery.
        for (Consumer<Throwable> member : Set.copyOf(members)) {
            if (!members.remove(member)) continue;
            try { member.accept(failure); } catch (RuntimeException ignored) { }
        }
        ScheduledFuture<?> armed = timer;
        if (armed != null) armed.cancel(false);
        if (detachParent != null) detachParent.run();
    }

    @Override public void close() {
        terminate(new CancellationException("concurrency scope completed: " + label));
    }

    int memberCount() { return members.size(); }
    static int pendingTimerCount() { return WATCHDOG.getQueue().size(); }
}
