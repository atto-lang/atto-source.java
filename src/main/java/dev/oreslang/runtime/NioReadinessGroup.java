package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VM-owned, one-shot NIO readiness domain.
 *
 * <p>The group is deliberately lazy: construction opens no selector and starts
 * no reactor thread. The first accepted watch creates one daemon platform
 * thread. Reactor code only settles OresFuture values; it never runs guest code,
 * actor turns, or CONTROL callbacks inline.</p>
 *
 * <p>Each channel may own at most one outstanding readiness watch. Admission
 * and the cross-thread command queue are bounded. Cancellation revokes the
 * watch before ordinary Future cancellation callbacks are delivered, and close
 * fences new work before waking and joining the selector loop.</p>
 */
final class NioReadinessGroup implements AutoCloseable {
    private static final int DEFAULT_MAX_PENDING = 4_096;
    private static final int DEFAULT_COMMAND_CAPACITY = 4_096;

    private final Object lifecycle = new Object();
    private final int maxPending;
    private final int commandCapacity;
    private final IdentityHashMap<SelectableChannel, Watch> watches =
            new IdentityHashMap<>();
    private final ArrayDeque<Runnable> commands = new ArrayDeque<>();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicInteger liveReactors = new AtomicInteger();

    private Selector selector;
    private Thread reactor;
    private int pending;
    private boolean started;
    private boolean closed;

    NioReadinessGroup() {
        this(DEFAULT_MAX_PENDING, DEFAULT_COMMAND_CAPACITY);
    }

    NioReadinessGroup(int maxPending, int commandCapacity) {
        if (maxPending < 1 || commandCapacity < 1) {
            throw new IllegalArgumentException("NIO readiness bounds must be positive");
        }
        this.maxPending = maxPending;
        this.commandCapacity = commandCapacity;
    }

    int reactorCount() {
        return liveReactors.get();
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
            if (watches.containsKey(channel)) {
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

            watch = new Watch(channel, interestOps);
            if (!enqueueLocked(() -> registerOnReactor(watch))) {
                return OresFuture.failed(new RejectedExecutionException(
                        "NIO readiness command capacity exhausted"));
            }
            watches.put(channel, watch);
            pending++;
            wake = selector;
        }
        wakeup(wake);
        return watch.future;
    }

    private void ensureStartedLocked() throws IOException {
        if (started) return;
        Selector opened = Selector.open();
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
        if (watch.retired.get()) return;
        try {
            SelectionKey key = watch.channel.register(selector, watch.interestOps, watch);
            watch.key = key;
            if (watch.retired.get()) key.cancel();
        } catch (ClosedChannelException | CancelledKeyException failure) {
            finish(watch, null, failure);
        } catch (RuntimeException failure) {
            finish(watch, null, failure);
        }
    }

    private void requestCancel(Watch watch) {
        Selector wake = null;
        boolean retireDirectly = false;
        synchronized (lifecycle) {
            if (watch.retired.get()) return;
            if (closed) {
                retireDirectly = true;
            } else if (enqueueLocked(() -> cancelOnReactor(watch))) {
                wake = selector;
            } else {
                // Cancellation must not be blocked by a saturated command
                // queue. The stale registration command observes retired and
                // becomes a no-op; an already-created key is safe to cancel
                // from the caller thread.
                retireDirectly = true;
            }
        }
        if (retireDirectly) {
            SelectionKey key = watch.key;
            if (key != null) key.cancel();
            retire(watch);
        }
        wakeup(wake);
    }

    private void cancelOnReactor(Watch watch) {
        SelectionKey key = watch.key;
        if (key != null) key.cancel();
        retire(watch);
    }

    private void runLoop() {
        liveReactors.incrementAndGet();
        Throwable terminal = null;
        try {
            while (true) {
                drainCommands();
                synchronized (lifecycle) {
                    if (closed) break;
                }

                selector.select();
                processSelected();
            }
            drainCommands();
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

    private void drainCommands() {
        while (true) {
            Runnable command;
            synchronized (lifecycle) {
                command = commands.pollFirst();
            }
            if (command == null) return;
            command.run();
        }
    }

    private void processSelected() {
        Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
        while (keys.hasNext()) {
            SelectionKey key = keys.next();
            keys.remove();
            Object attachment = key.attachment();
            if (!(attachment instanceof Watch watch)) {
                key.cancel();
                continue;
            }
            if (watch.retired.get()) {
                key.cancel();
                continue;
            }
            try {
                int ready = key.readyOps() & watch.interestOps;
                if (ready != 0) {
                    key.cancel();
                    finish(watch, ready, null);
                }
            } catch (CancelledKeyException failure) {
                finish(watch, null, failure);
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
        if (!watch.retired.compareAndSet(false, true)) return false;
        synchronized (lifecycle) {
            if (watches.get(watch.channel) == watch) {
                watches.remove(watch.channel);
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
            List<Watch> snapshot = new ArrayList<>(watches.values());
            watches.clear();
            pending = 0;
            commands.clear();
            lifecycle.notifyAll();
            return snapshot;
        }
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
            snapshot = new ArrayList<>(watches.values());
            watches.clear();
            pending = 0;
            commands.clear();
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
    }

    boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        return stopped.await(timeout, unit);
    }

    private final class Watch {
        final SelectableChannel channel;
        final int interestOps;
        final AtomicBoolean retired = new AtomicBoolean();
        final OresFuture<Integer> future;
        volatile SelectionKey key;

        Watch(SelectableChannel channel, int interestOps) {
            this.channel = channel;
            this.interestOps = interestOps;
            this.future = new OresFuture<>(() -> requestCancel(this));
        }
    }
}
