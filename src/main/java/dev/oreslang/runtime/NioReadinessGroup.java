package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * VM-owned, one-shot NIO readiness domain.
 *
 * <p>The group is deliberately lazy: construction opens no selector and starts
 * no reactor thread. The first accepted watch opens the selector. It is
 * driven by a dedicated worker or polled between VM carrier turns.
 * Reactor code only settles OresFuture values; it never runs guest code,
 * actor turns, or CONTROL callbacks inline.</p>
 *
 * <p>Channels ordinarily own one watch. The duplex TCP adapter can own one
 * read and one write watch with distinct interest masks. Admission and the
 * cross-thread command queue are bounded. Cancellation revokes the
 * watch before ordinary Future cancellation callbacks are delivered, and close
 * fences new work before waking and joining the selector loop.</p>
 */
final class NioReadinessGroup implements AutoCloseable {
    private static final int DEFAULT_MAX_PENDING = 4_096;
    private static final int DEFAULT_COMMAND_CAPACITY = 4_096;
    private static final int SHARED_COMMAND_BATCH = 64;
    private static final int SHARED_READY_BATCH = 64;
    private static final long SHARED_POLL_MILLIS = 5;

    private final Object lifecycle = new Object();
    private final int maxPending;
    private final int commandCapacity;
    private final IdentityHashMap<SelectableChannel, List<Watch>> watches =
            new IdentityHashMap<>();
    private final ArrayDeque<Runnable> commands = new ArrayDeque<>();
    private final PriorityQueue<Watch> deadlines =
            new PriorityQueue<>(Comparator.comparingLong(watch -> watch.deadlineNanos));
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicInteger liveReactors = new AtomicInteger();
    private final Runnable wakeCarrier;
    private final ReentrantLock sharedPollOwner = new ReentrantLock();

    private Selector selector;
    private Thread reactor;
    private int pending;
    private boolean started;
    private boolean closed;

    NioReadinessGroup() {
        this(DEFAULT_MAX_PENDING, DEFAULT_COMMAND_CAPACITY);
    }

    NioReadinessGroup(int maxPending, int commandCapacity) {
        this(maxPending, commandCapacity, null);
    }

    NioReadinessGroup(Runnable wakeCarrier) {
        this(DEFAULT_MAX_PENDING, DEFAULT_COMMAND_CAPACITY, wakeCarrier);
    }

    private NioReadinessGroup(int maxPending, int commandCapacity, Runnable wakeCarrier) {
        if (maxPending < 1 || commandCapacity < 1) {
            throw new IllegalArgumentException("NIO readiness bounds must be positive");
        }
        this.maxPending = maxPending;
        this.commandCapacity = commandCapacity;
        this.wakeCarrier = wakeCarrier;
    }

    int reactorCount() {
        return liveReactors.get();
    }

    boolean shared() {
        return wakeCarrier != null;
    }

    int pendingCount() {
        synchronized (lifecycle) {
            return pending;
        }
    }

    /** Host/test observation only; actor code must never block on this barrier. */
    boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (lifecycle) {
            while (pending != 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(lifecycle, left);
            }
            return true;
        }
    }

    boolean isClosed() {
        synchronized (lifecycle) {
            return closed;
        }
    }

    /**
     * Register a one-shot readiness watch.
     *
     * <p>The returned Future is completed with the ready operation mask. A
     * second watch for the same channel is rejected until the first retires.
     * This keeps channel ownership explicit and prevents two runtime domains
     * from racing SelectionKey interest changes.</p>
     */
    OresFuture<Integer> watch(SelectableChannel channel, int interestOps) {
        return watch(channel, interestOps, false, 0);
    }

    OresFuture<Integer> watchDirection(SelectableChannel channel, int interestOps, long deadlineNanos) {
        if (interestOps != SelectionKey.OP_READ && interestOps != SelectionKey.OP_WRITE) {
            throw new IllegalArgumentException("a duplex watch must select one socket direction");
        }
        return watch(channel, interestOps, true, deadlineNanos);
    }

    private OresFuture<Integer> watch(SelectableChannel channel, int interestOps, boolean duplex,
                                      long deadlineNanos) {
        Objects.requireNonNull(channel, "channel");
        if (interestOps == 0
                || (interestOps & channel.validOps()) != interestOps) {
            return OresFuture.failed(new IllegalArgumentException(
                    "invalid NIO readiness interest mask"));
        }

        final Watch watch;
        final Selector wake;
        synchronized (lifecycle) {
            if (closed) {
                return OresFuture.failed(new RejectedExecutionException(
                        "NIO readiness group is closed"));
            }
            // Ownership is checked before capacity: a duplicate watch is a
            // channel-ownership violation even when the group is saturated
            // (often by that very channel's outstanding watch).
            List<Watch> existing = watches.get(channel);
            if (existing != null && (!duplex || existing.stream().anyMatch(
                    prior -> !prior.duplex || (prior.interestOps & interestOps) != 0))) {
                return OresFuture.failed(new IllegalStateException(
                        "channel already owns an outstanding readiness watch"));
            }
            if (pending >= maxPending) {
                return OresFuture.failed(new RejectedExecutionException(
                        "NIO readiness watch capacity exhausted"));
            }

            try {
                channel.configureBlocking(false);
                ensureStartedLocked();
            } catch (IOException failure) {
                return OresFuture.failed(failure);
            } catch (RuntimeException | Error failure) {
                return OresFuture.failed(failure);
            }

            watch = new Watch(channel, interestOps, duplex, deadlineNanos);
            if (!enqueueLocked(() -> registerOnReactor(watch))) {
                return OresFuture.failed(new RejectedExecutionException(
                        "NIO readiness command capacity exhausted"));
            }
            watches.computeIfAbsent(channel, ignored -> new ArrayList<>(2)).add(watch);
            if (deadlineNanos != 0) deadlines.add(watch);
            pending++;
            wake = selector;
        }
        wakeup(wake);
        if (wakeCarrier != null) wakeCarrier.run();
        return watch.future;
    }

    private void ensureStartedLocked() throws IOException {
        if (started) return;
        Selector opened = Selector.open();
        if (wakeCarrier != null) {
            selector = opened;
            started = true;
            return;
        }
        Thread thread = Thread.ofPlatform()
                .daemon(true)
                .name("ores-nio-reactor-0")
                .unstarted(() -> OresVM.runAsNioWorker(this::runLoop));
        selector = opened;
        reactor = thread;
        started = true;
        try {
            thread.start();
        } catch (RuntimeException | Error failure) {
            started = false;
            reactor = null;
            selector = null;
            closed = true;
            stopped.countDown();
            try {
                opened.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private boolean enqueueLocked(Runnable command) {
        if (commands.size() >= commandCapacity) return false;
        commands.addLast(command);
        return true;
    }

    private void registerOnReactor(Watch watch) {
        try {
            synchronized (lifecycle) {
                if (closed || watch.retired.get()) return;
                SelectionKey key = watch.channel.keyFor(selector);
                if (key != null && !key.isValid()) {
                    // A continuation can rearm as soon as the old Future settles.
                    // Process cancelled keys before asking Java to register again.
                    selector.selectNow();
                    key = watch.channel.keyFor(selector);
                }
                if (key == null) {
                    key = watch.channel.register(selector, watch.interestOps);
                } else {
                    key.interestOpsOr(watch.interestOps);
                }
                watch.key = key;
            }
        } catch (IOException | CancelledKeyException failure) {
            finish(watch, null, failure);
        } catch (RuntimeException failure) {
            finish(watch, null, failure);
        }
    }

    private void requestCancel(Watch watch) {
        if (retire(watch)) wakeup(selector);
    }

    private void runLoop() {
        liveReactors.incrementAndGet();
        Throwable terminal = null;
        try {
            while (true) {
                drainCommands(Integer.MAX_VALUE);
                synchronized (lifecycle) {
                    if (closed) break;
                }

                if (selector.selectedKeys().isEmpty()) {
                    long wait = millisUntilDeadline();
                    if (wait == 0) selector.selectNow();
                    else if (wait < 0) selector.select();
                    else selector.select(wait);
                }
                processSelected(Integer.MAX_VALUE);
                expireWatches();
            }
            drainCommands(Integer.MAX_VALUE);
        } catch (ClosedSelectorException ignored) {
            synchronized (lifecycle) {
                if (!closed) terminal = ignored;
            }
        } catch (IOException | RuntimeException failure) {
            terminal = failure;
        } finally {
            if (terminal != null) {
                failAllFromReactor(terminal);
            } else {
                retireAllFromReactor();
            }
            Selector owned = selector;
            if (owned != null) {
                try {
                    owned.close();
                } catch (IOException closeFailure) {
                    if (terminal == null) terminal = closeFailure;
                    else terminal.addSuppressed(closeFailure);
                }
            }
            liveReactors.decrementAndGet();
            stopped.countDown();
        }

        if (terminal instanceof VirtualMachineError fatal) throw fatal;
        if (terminal instanceof ThreadDeath fatal) throw fatal;
    }

    private void drainCommands(int limit) {
        for (int count = 0; count < limit; count++) {
            Runnable command;
            synchronized (lifecycle) {
                command = commands.pollFirst();
            }
            if (command == null) return;
            command.run();
        }
    }

    /** True if the carrier had a shared selector turn and should check its work queues again. */
    boolean pollShared(boolean mayWait) {
        synchronized (lifecycle) {
            if (closed || (pending == 0 && commands.isEmpty())) return false;
        }
        if (!sharedPollOwner.tryLock()) return false;
        try {
            synchronized (lifecycle) {
                if (closed) return false;
            }
            OresVM.runAsNioWorker(() -> {
                try {
                    drainCommands(SHARED_COMMAND_BATCH);
                    boolean wait;
                    synchronized (lifecycle) {
                        wait = mayWait && !closed && pending > 0 && commands.isEmpty();
                    }
                    if (selector.selectedKeys().isEmpty()) {
                        long toDeadline = millisUntilDeadline();
                        if (wait && toDeadline != 0) {
                            selector.select(toDeadline < 0 ? SHARED_POLL_MILLIS
                                    : Math.min(SHARED_POLL_MILLIS, toDeadline));
                        } else {
                            selector.selectNow();
                        }
                    }
                    // A full ready set can contain thousands of sockets.
                    // Leave its remaining keys selected for the next poll
                    // so application turns get their carrier back promptly.
                    processSelected(SHARED_READY_BATCH);
                    expireWatches();
                } catch (IOException | RuntimeException failure) {
                    failAllFromReactor(failure);
                }
            });
            return true;
        } finally {
            sharedPollOwner.unlock();
            stopSharedIfClosed();
        }
    }

    private long millisUntilDeadline() {
        synchronized (lifecycle) {
            Watch next = deadlines.peek();
            if (next == null) return -1;
            long left = next.deadlineNanos - System.nanoTime();
            return left <= 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toMillis(left));
        }
    }

    private void expireWatches() {
        for (int count = 0; count < SHARED_COMMAND_BATCH; count++) {
            Watch next;
            synchronized (lifecycle) {
                next = deadlines.peek();
                if (next == null || next.deadlineNanos - System.nanoTime() > 0) return;
            }
            finish(next, null, new InterruptedByTimeoutException());
        }
    }

    void wakeShared() {
        if (!sharedPollOwner.isLocked()) return;
        Selector current;
        synchronized (lifecycle) {
            current = selector;
        }
        wakeup(current);
    }

    private void stopSharedIfClosed() {
        synchronized (lifecycle) {
            if (!closed || stopped.getCount() == 0) return;
        }
        if (!sharedPollOwner.tryLock()) return;
        try {
            if (selector != null) selector.close();
        } catch (IOException ignored) {
            // The group has already fenced and settled all outstanding watches.
        } finally {
            stopped.countDown();
            sharedPollOwner.unlock();
        }
    }

    private void processSelected(int limit) {
        Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
        for (int count = 0; count < limit && keys.hasNext(); count++) {
            SelectionKey key = keys.next();
            keys.remove();
            List<Watch> selected;
            synchronized (lifecycle) {
                List<Watch> current = watches.get(key.channel());
                selected = current == null ? List.of() : List.copyOf(current);
            }
            if (selected.isEmpty()) {
                key.cancel();
                continue;
            }
            try {
                int ready = key.readyOps();
                for (Watch watch : selected) {
                    int mask = ready & watch.interestOps;
                    if (watch.key == key && mask != 0) finish(watch, mask, null);
                }
            } catch (CancelledKeyException failure) {
                for (Watch watch : selected) {
                    if (watch.key == key) finish(watch, null, failure);
                }
            }
        }
    }

    private void finish(Watch watch, Integer ready, Throwable failure) {
        if (!retire(watch)) return;
        if (failure == null) {
            watch.future.completeFromRuntime(ready);
        } else {
            watch.future.failFromRuntime(failure);
        }
    }

    private boolean retire(Watch watch) {
        synchronized (lifecycle) {
            if (!watch.retired.compareAndSet(false, true)) return false;
            if (watch.deadlineNanos != 0) deadlines.remove(watch);
            List<Watch> related = watches.get(watch.channel);
            if (related != null && related.remove(watch)) {
                if (related.isEmpty()) watches.remove(watch.channel);
                SelectionKey key = watch.key;
                if (key != null && key.isValid()) {
                    try {
                        int before = key.interestOpsAnd(~watch.interestOps);
                        if ((before & ~watch.interestOps) == 0) key.cancel();
                    } catch (CancelledKeyException ignored) {
                        // The descriptor closed concurrently.
                    }
                }
                pending--;
                if (pending < 0) {
                    pending = 0;
                    throw new IllegalStateException(
                            "NIO readiness pending accounting underflow");
                }
                lifecycle.notifyAll();
            }
        }
        return true;
    }

    private void failAllFromReactor(Throwable failure) {
        List<Watch> snapshot = snapshotAndFence();
        for (Watch watch : snapshot) {
            if (watch.retired.compareAndSet(false, true) && !watch.future.isDone()) {
                watch.future.failFromRuntime(failure);
            }
        }
    }

    private void retireAllFromReactor() {
        List<Watch> snapshot = snapshotAndFence();
        RejectedExecutionException closedFailure =
                new RejectedExecutionException("NIO readiness group closed");
        for (Watch watch : snapshot) {
            if (watch.retired.compareAndSet(false, true) && !watch.future.isDone()) {
                watch.future.failFromRuntime(closedFailure);
            }
        }
    }

    private List<Watch> snapshotAndFence() {
        synchronized (lifecycle) {
            closed = true;
            List<Watch> snapshot = allWatchesLocked();
            watches.clear();
            pending = 0;
            commands.clear();
            deadlines.clear();
            lifecycle.notifyAll();
            return snapshot;
        }
    }

    private List<Watch> allWatchesLocked() {
        List<Watch> all = new ArrayList<>(pending);
        for (List<Watch> channel : watches.values()) all.addAll(channel);
        return all;
    }

    private static void wakeup(Selector selector) {
        if (selector == null) return;
        try {
            selector.wakeup();
        } catch (ClosedSelectorException ignored) {
            // Close/fatal teardown owns the terminal Future settlement.
        }
    }

    @Override
    public void close() {
        final List<Watch> snapshot;
        final Selector wake;
        final boolean neverStarted;
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            snapshot = allWatchesLocked();
            watches.clear();
            pending = 0;
            commands.clear();
            deadlines.clear();
            wake = selector;
            neverStarted = !started;
            lifecycle.notifyAll();
        }

        RejectedExecutionException failure =
                new RejectedExecutionException("NIO readiness group closed");
        for (Watch watch : snapshot) {
            SelectionKey key = watch.key;
            if (key != null) key.cancel();
            if (watch.retired.compareAndSet(false, true) && !watch.future.isDone()) {
                watch.future.failFromRuntime(failure);
            }
        }

        if (neverStarted) stopped.countDown();
        wakeup(wake);
        if (wakeCarrier != null) stopSharedIfClosed();
    }

    boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        return stopped.await(timeout, unit);
    }

    private final class Watch {
        final SelectableChannel channel;
        final int interestOps;
        final boolean duplex;
        final long deadlineNanos;
        final AtomicBoolean retired = new AtomicBoolean();
        final OresFuture<Integer> future;
        volatile SelectionKey key;

        Watch(SelectableChannel channel, int interestOps, boolean duplex, long deadlineNanos) {
            this.channel = channel;
            this.interestOps = interestOps;
            this.duplex = duplex;
            this.deadlineNanos = deadlineNanos;
            this.future = new OresFuture<>(() -> requestCancel(this));
        }
    }
}
