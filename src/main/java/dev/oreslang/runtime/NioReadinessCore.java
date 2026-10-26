package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class NioReadinessCore implements AutoCloseable {
    private final Object lifecycle = new Object();
    private final Object notificationDrain = new Object();
    private final int maxReactors;
    private final int maxPending;
    private final int commandCapacity;
    private final ArrayList<NioSelectorReactor> reactors = new ArrayList<>();
    private final IdentityHashMap<SelectableChannel, NioSelectorReactor.Watch> ownership =
            new IdentityHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger notifications = new AtomicInteger();
    private final AtomicInteger liveReactors = new AtomicInteger();
    private final NioCompletionDispatcher completion;
    private final NioLeaseBook leases = new NioLeaseBook();
    private volatile boolean closed;
    private int pressure;

    NioReadinessCore(NioReadinessGroup facade, int maxReactors, int maxPending,
            int commandCapacity, Executor vmCompletionExecutor,
            ThreadFactory ownedCompletionFactory) {
        Objects.requireNonNull(facade, "facade");
        if (maxReactors < 1 || maxReactors > 4 || maxPending < 1 || commandCapacity < 8)
            throw new IllegalArgumentException("invalid NIO reactor bounds");
        this.maxReactors = maxReactors;
        this.maxPending = maxPending;
        this.commandCapacity = commandCapacity;
        completion = new NioCompletionDispatcher(
                maxPending, vmCompletionExecutor, ownedCompletionFactory, liveReactors::get);
    }

    int reactorCount() { return liveReactors.get(); }
    int pendingCount() { return pending.get(); }
    int pendingNotifications() { return notifications.get(); }
    int stagedNotificationCount() { return completion.stagedCount(); }
    int unexpectedSubmissionFailures() { return completion.unexpectedFailures(); }
    int completionCarrierCount() { return completion.carrierCount(); }
    boolean usesVmCompletionCarriers() { return completion.usesVmCarriers(); }
    boolean closed() { return closed; }
    int activeAcceptLeases() { return leases.accepts(); }
    int activeReadLeases() { return leases.reads(); }
    int activeWriteLeases() { return leases.writes(); }

    void bindVmCompletionExecutor(Executor executor, boolean coldStartOffReactor) {
        synchronized (lifecycle) {
            if (closed || !reactors.isEmpty() || pending.get() != 0
                    || notifications.get() != 0 || !leases.empty())
                throw new IllegalStateException(
                        "NIO completion scheduler must be bound before first I/O admission");
            completion.bind(executor, coldStartOffReactor);
        }
    }

    void retryStagedNotifications() { completion.retry(); }

    OresFuture<Integer> watch(SelectableChannel channel, int interestOps) {
        Objects.requireNonNull(channel, "channel");
        if (channel.isBlocking())
            throw new IllegalArgumentException("NIO channels must be nonblocking");
        if (interestOps == 0 || (interestOps & ~channel.validOps()) != 0)
            throw new IllegalArgumentException("unsupported readiness operations");
        synchronized (lifecycle) {
            if (closed || !channel.isOpen())
                return OresFuture.failed(
                        new IllegalStateException("NIO manager or channel closed"));
            if (ownership.containsKey(channel))
                return OresFuture.failed(
                        new IllegalStateException("channel already has a pending readiness watch"));
            if (pending.get() >= maxPending || notifications.get() >= maxPending)
                return OresFuture.failed(new RejectedExecutionException(
                        "NIO readiness or completion notification capacity exhausted"));
            NioSelectorReactor target;
            try {
                target = reactors.isEmpty() ? startReactor() : leastBacklogged();
                if (target.queueSize() >= Math.max(8, commandCapacity / 2)) pressure++;
                else pressure = 0;
                if (pressure >= 8 && reactors.size() < maxReactors) {
                    target = startReactor();
                    pressure = 0;
                }
            } catch (IOException | RuntimeException failure) {
                return OresFuture.failed(failure);
            }
            NioSelectorReactor.Watch watch =
                    new NioSelectorReactor.Watch(this, channel, interestOps, target);
            ownership.put(channel, watch);
            pending.incrementAndGet();
            notifications.incrementAndGet();
            if (!target.admit(watch)) {
                ownership.remove(channel);
                pending.decrementAndGet();
                notifications.decrementAndGet();
                return OresFuture.failed(
                        new RejectedExecutionException("NIO reactor command queue full"));
            }
            return watch.future();
        }
    }

    private NioSelectorReactor startReactor() throws IOException {
        if (closed) throw new RejectedExecutionException("NIO reactor stopped");
        if (reactors.size() >= maxReactors)
            throw new RejectedExecutionException("NIO reactor limit reached");
        NioSelectorReactor reactor =
                new NioSelectorReactor(this, reactors.size(), commandCapacity);
        liveReactors.incrementAndGet();
        try { reactor.start(); }
        catch (RuntimeException | Error failure) {
            liveReactors.decrementAndGet();
            reactor.abortUnstarted();
            throw failure;
        }
        reactors.add(reactor);
        return reactor;
    }

    private NioSelectorReactor leastBacklogged() {
        NioSelectorReactor best = reactors.getFirst();
        for (NioSelectorReactor reactor : reactors)
            if (reactor.queueSize() < best.queueSize()) best = reactor;
        return best;
    }

    void retire(NioSelectorReactor.Watch watch, Integer events, Throwable error) {
        synchronized (lifecycle) {
            if (ownership.get(watch.channel()) != watch) return;
            ownership.remove(watch.channel());
            pending.decrementAndGet();
        }
        try {
            completion.dispatch(() -> {
                try {
                    if (!watch.future().isCancelled()) {
                        if (error != null) watch.future().failFromRuntime(error);
                        else watch.future().completeFromRuntime(events);
                    }
                } finally {
                    releaseNotification();
                }
            });
        } catch (RuntimeException | Error failure) {
            releaseNotification();
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            throw new IllegalStateException(
                    "NIO completion publication failed before reactor quiescence", failure);
        }
    }

    private void releaseNotification() {
        int remaining = notifications.decrementAndGet();
        if (remaining < 0) {
            notifications.incrementAndGet();
            throw new IllegalStateException(
                    "NIO notification reservation released more than once");
        }
        if (remaining == 0)
            synchronized (notificationDrain) { notificationDrain.notifyAll(); }
    }

    void reactorStopped() {
        if (liveReactors.decrementAndGet() == 0) {
            synchronized (lifecycle) { closed = true; }
            completion.shutdownOwned();
        }
    }

    boolean reserveAccept(ServerSocketChannel c, OresFuture<SocketChannel> f) {
        return leases.reserveAccept(c, f, closed);
    }
    void releaseAccept(ServerSocketChannel c, OresFuture<SocketChannel> f) {
        leases.releaseAccept(c, f);
    }
    boolean reserveRead(SocketChannel c, OresFuture<byte[]> f) {
        return leases.reserveRead(c, f, closed);
    }
    void releaseRead(SocketChannel c, OresFuture<byte[]> f) { leases.releaseRead(c, f); }
    boolean reserveWrite(SocketChannel c, OresFuture<Integer> f) {
        return leases.reserveWrite(c, f, closed);
    }
    void releaseWrite(SocketChannel c, OresFuture<Integer> f) { leases.releaseWrite(c, f); }
    boolean channelUnavailable(SocketChannel c) { return closed || !c.isOpen(); }

    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            if (reactors.isEmpty()) {
                completion.shutdownOwned();
                return;
            }
            for (NioSelectorReactor reactor : reactors) reactor.requestStop();
        }
    }

    boolean awaitStopped(long time, TimeUnit unit) throws InterruptedException {
        ArrayList<NioSelectorReactor> snapshot;
        synchronized (lifecycle) { snapshot = new ArrayList<>(reactors); }
        long deadline = System.nanoTime() + unit.toNanos(time);
        for (NioSelectorReactor reactor : snapshot) {
            long left = deadline - System.nanoTime();
            if (left <= 0 || !reactor.awaitStopped(left, TimeUnit.NANOSECONDS))
                return false;
        }
        completion.retry();
        synchronized (notificationDrain) {
            while (notifications.get() != 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(notificationDrain, left);
            }
        }
        return completion.awaitOwned(deadline - System.nanoTime());
    }
}
