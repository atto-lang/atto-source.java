package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-capable, bounded NIO readiness manager. One dedicated reactor starts
 * initially; up to four can be created on sustained registration-command
 * pressure, never just because sockets are waiting for events.
 *
 * This is a low-level host substrate. It does not currently own sockets or
 * implement source-level std/net. Only one pending readiness watch per channel
 * is permitted; the host retains the channel until the watch settles.
 * Completion only settles OresFuture: guest continuations are enqueued by
 * the Future's owning OresScheduler, not executed by a reactor.
 */
public final class NioReadinessGroup implements AutoCloseable {
    private static final int DEFAULT_MAX_REACTORS = 4;
    private static final int DEFAULT_MAX_PENDING = 8192;
    private static final int DEFAULT_COMMAND_CAPACITY = 1024;

    private final Object lifecycle = new Object();
    private final int maxReactors;
    private final int maxPending;
    private final int commandCapacity;
    private final ArrayList<Reactor> reactors = new ArrayList<>();
    private final IdentityHashMap<SelectableChannel, Watch> ownership = new IdentityHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    // Includes selector watches that have retired but whose terminal Future
    // callbacks are still queued or running. Protects against unbounded
    // notification buildup when host subscribers run slowly.
    private final AtomicInteger notificationReservations = new AtomicInteger();
    private final AtomicInteger liveReactors = new AtomicInteger();
    // Standalone groups own dedicated completion workers. VM-owned groups
    // instead reuse its bounded CONTROL workers (no extra resident threads).
    private final Executor completionExecutor;
    private final ThreadPoolExecutor ownedCompletionExecutor;
    private final ConcurrentLinkedDeque<Runnable> stagedNotifications =
            new ConcurrentLinkedDeque<>();
    private final Object notificationDispatch = new Object();
    private final Object notificationDrain = new Object();
    // A transient registration burst must not spin up extra selector threads.
    // Counter is protected by the lifecycle monitor.
    private int consecutiveCommandPressure;
    private volatile boolean closed;

    public NioReadinessGroup() throws IOException {
        this(DEFAULT_MAX_REACTORS, DEFAULT_MAX_PENDING, DEFAULT_COMMAND_CAPACITY);
    }

    public NioReadinessGroup(int maxReactors, int maxPending, int commandCapacity) throws IOException {
        this(maxReactors, maxPending, commandCapacity, null);
    }

    // VM-only: no extra prestarted NIO completion threads. The caller must
    // register retryStagedNotifications with its carrier pool's afterExecute
    // hook. A standalone manager retains its dedicated completion workers.
    NioReadinessGroup(int maxReactors, int maxPending, int commandCapacity,
                      Executor vmCompletionExecutor) throws IOException {
        if (maxReactors < 1 || maxReactors > 4 || maxPending < 1 || commandCapacity < 8)
            throw new IllegalArgumentException("invalid NIO reactor bounds");
        this.maxReactors = maxReactors;
        this.maxPending = maxPending;
        this.commandCapacity = commandCapacity;
        // Standalone host tests use two prestarted bounded completion workers.
        // The production VM supplies its already-prestarted CONTROL pool.
        if (vmCompletionExecutor == null) {
            ownedCompletionExecutor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(maxPending), new java.util.concurrent.ThreadFactory() {
                        private final AtomicInteger ids = new AtomicInteger();
                        @Override public Thread newThread(Runnable work) {
                            return Thread.ofPlatform().daemon(true)
                                    .name("ores-nio-completion-" + ids.getAndIncrement())
                                    .unstarted(() -> OresVM.runAsNioWorker(work));
                        }
                    });
            completionExecutor = ownedCompletionExecutor;
        } else {
            ownedCompletionExecutor = null;
            completionExecutor = vmCompletionExecutor;
        }
        try {
            if (ownedCompletionExecutor != null
                    && ownedCompletionExecutor.prestartAllCoreThreads() != 2)
                throw new RejectedExecutionException("NIO completion worker bootstrap failed");
            Reactor first = new Reactor(0);
            synchronized (lifecycle) {
                reactors.add(first);
                liveReactors.incrementAndGet();
                try { first.start(); }
                catch (RuntimeException | Error problem) {
                    liveReactors.decrementAndGet();
                    reactors.clear();
                    first.abortUnstarted();
                    throw problem;
                }
            }
        } catch (IOException | RuntimeException | Error failure) {
            if (ownedCompletionExecutor != null) ownedCompletionExecutor.shutdownNow();
            throw failure;
        }
    }

    public int reactorCount() {
        synchronized (lifecycle) { return reactors.size(); }
    }
    public int pendingCount() { return pending.get(); }
    int pendingNotifications() { return notificationReservations.get(); }
    boolean usesVmCompletionCarriers() { return ownedCompletionExecutor == null; }

    private void releaseNotificationReservation() {
        if (notificationReservations.decrementAndGet() == 0) {
            synchronized (notificationDrain) { notificationDrain.notifyAll(); }
        }
    }

    /** Only host CPU callbacks are dispatched; selector threads never execute
     * the Future subscriber. Admission already reserves at most maxPending
     * tasks, including slow or queued completions.
     */
    private void dispatchNotification(Runnable notification) {
        if (ownedCompletionExecutor != null) {
            completionExecutor.execute(notification);
        } else {
            stagedNotifications.addLast(notification);
            retryStagedNotifications();
        }
    }

    /** Called when a CONTROL task retires, so a temporary full queue cannot
     * permanently strand NIO completions. The bounded retry never blocks,
     * executes a Future subscriber inline, or spins on a full executor.
     */
    void retryStagedNotifications() {
        if (ownedCompletionExecutor != null) return;
        synchronized (notificationDispatch) {
            for (int i = 0; i < 128; ++i) {
                Runnable next = stagedNotifications.peekFirst();
                if (next == null) return;
                try {
                    completionExecutor.execute(() -> OresVM.runAsNioWorker(next));
                } catch (RejectedExecutionException full) {
                    // Keep the accepted reservation until a CONTROL task
                    // completes and its afterExecute hook retries delivery.
                    return;
                }
                stagedNotifications.removeFirst();
            }
        }
    }

    /**
     * Accept one TCP connection using the VM-owned selector rather than a
     * listener-owned selector thread. This is a host transport building block;
     * source-level std/net still needs connection read/write integration.
     *
     * The listener stays caller-owned and MUST remain nonblocking and open
     * until completion. SocketChannel.accept() runs on dedicated NIO completion
     * workers, never on a selector, CONTROL carrier or actor turn. Only one
     * outstanding accept/watch per listener is admitted by watch().
     *
     * Cancellation revokes the active readiness watch. If cancellation wins
     * after accept(), the newly created socket is closed, never leaked.
     */
    public OresFuture<SocketChannel> acceptAsync(ServerSocketChannel listener) {
        Objects.requireNonNull(listener, "listener");
        if (listener.isBlocking())
            throw new IllegalArgumentException("TCP listener must be nonblocking");
        if (ownedCompletionExecutor == null)
            return OresFuture.failed(new IllegalStateException(
                    "TCP accept requires dedicated NIO completion workers"));
        AtomicReference<OresFuture<Integer>> pendingWatch = new AtomicReference<>();
        OresFuture<SocketChannel> accepted = new OresFuture<>(() -> {
            OresFuture<Integer> pending = pendingWatch.getAndSet(null);
            if (pending != null) pending.cancel(false);
        });

        final class AcceptPump {
            void arm() {
                if (accepted.isDone()) return;
                OresFuture<Integer> readiness = NioReadinessGroup.this.watch(
                        listener, SelectionKey.OP_ACCEPT);
                pendingWatch.set(readiness);
                if (accepted.isDone()) {
                    pendingWatch.compareAndSet(readiness, null);
                    readiness.cancel(false);
                    return;
                }
                readiness.whenCompleteRuntime((events, failure) -> {
                    pendingWatch.compareAndSet(readiness, null);
                    if (accepted.isDone()) return;
                    if (failure != null) {
                        accepted.failFromRuntime(failure);
                        return;
                    }
                    SocketChannel socket = null;
                    try {
                        // Even if another host accidentally consumes a ready
                        // accept, null simply means re-arm rather than spin.
                        if (listener.isBlocking())
                            throw new IllegalStateException("listener became blocking");
                        socket = listener.accept();
                        if (socket == null) {
                            arm();
                            return;
                        }
                        socket.configureBlocking(false);
                        if (accepted.completeFromRuntime(socket)) {
                            socket = null; // Future consumer now owns accepted FD
                        } else {
                            socket.close(); // cancelled just after accept()
                            socket = null;
                        }
                    } catch (IOException | RuntimeException | Error problem) {
                        if (socket != null) {
                            try { socket.close(); }
                            catch (IOException closeFailure) { problem.addSuppressed(closeFailure); }
                        }
                        accepted.failFromRuntime(problem);
                        if (problem instanceof VirtualMachineError fatal) throw fatal;
                        if (problem instanceof ThreadDeath fatal) throw fatal;
                    }
                });
            }
        }
        new AcceptPump().arm();
        return accepted;
    }

    /** Register one kernel-readiness notification without blocking a carrier. */
    public OresFuture<Integer> watch(SelectableChannel channel, int interestOps) {
        Objects.requireNonNull(channel, "channel");
        if (channel.isBlocking()) throw new IllegalArgumentException("NIO channels must be nonblocking");
        if (interestOps == 0 || (interestOps & ~channel.validOps()) != 0)
            throw new IllegalArgumentException("unsupported readiness operations");
        synchronized (lifecycle) {
            if (closed || !channel.isOpen()) return OresFuture.failed(
                    new IllegalStateException("NIO manager or channel closed"));
            if (ownership.containsKey(channel)) return OresFuture.failed(
                    new IllegalStateException("channel already has a pending readiness watch"));
            if (pending.get() >= maxPending || notificationReservations.get() >= maxPending)
                return OresFuture.failed(new RejectedExecutionException(
                        "NIO readiness or completion notification capacity exhausted"));
            Reactor target = leastBacklogged();
            // Reactor scaling is about *runnable registration work*, not the
            // number of pending network reads. The active kernel wait is idle.
            if (target.commands.size() >= Math.max(8, commandCapacity / 2)) {
                if (consecutiveCommandPressure < 8) consecutiveCommandPressure++;
            } else {
                consecutiveCommandPressure = 0;
            }
            if (consecutiveCommandPressure >= 8 && reactors.size() < maxReactors) {
                Reactor extra = null;
                try {
                    extra = new Reactor(reactors.size());
                    liveReactors.incrementAndGet();
                    try { extra.start(); }
                    catch (RuntimeException | Error startFailure) {
                        liveReactors.decrementAndGet();
                        throw startFailure;
                    }
                    reactors.add(extra);
                    target = extra;
                    consecutiveCommandPressure = 0;
                } catch (IOException | RuntimeException | Error failure) {
                    // Thread startup can fail after a selector was opened.
                    // Never leak that kernel descriptor when the optional
                    // expansion fails. Existing reactors can still serve or
                    // explicitly reject the bounded request.
                    if (extra != null) extra.abortUnstarted();
                    // Admission may still use existing reactors; throttle
                    // retries rather than allocating a selector on every
                    // subsequent request during FD/thread exhaustion.
                    consecutiveCommandPressure = 0;
                    if (failure instanceof VirtualMachineError fatal) throw fatal;
                    if (failure instanceof ThreadDeath fatal) throw fatal;
                }
            }
            if (target.stopping.get())
                return OresFuture.failed(new RejectedExecutionException("NIO reactor stopped"));
            Watch watch = new Watch(channel, interestOps, target);
            ownership.put(channel, watch);
            pending.incrementAndGet();
            notificationReservations.incrementAndGet();
            target.admitted.put(watch, Boolean.TRUE);
            if (!target.commands.offer(watch)) {
                target.admitted.remove(watch);
                pending.decrementAndGet();
                notificationReservations.decrementAndGet();
                ownership.remove(channel);
                return OresFuture.failed(new RejectedExecutionException("NIO reactor command queue full"));
            }
            target.selector.wakeup();
            return watch.future;
        }
    }

    private Reactor leastBacklogged() {
        Reactor best = reactors.get(0);
        for (Reactor reactor : reactors) {
            if (reactor.commands.size() < best.commands.size()) best = reactor;
        }
        return best;
    }

    private final class Watch {
        final SelectableChannel channel;
        final int interests;
        final Reactor reactor;
        final AtomicBoolean retired = new AtomicBoolean();
        final OresFuture<Integer> future;
        SelectionKey key; // reactor-thread only
        Watch(SelectableChannel channel, int interests, Reactor reactor) {
            this.channel = channel;
            this.interests = interests;
            this.reactor = reactor;
            // Only cancelled watches need rechecking. Outstanding Future
            // admission bounds this queue: each Future cancels at most once.
            this.future = new OresFuture<>(() -> true, () -> {
                // Shutdown retires all admitted watches. A concurrent late
                // cancel must not publish a stale watch after the reactor's
                // final cancellation-queue clear.
                if (retired.get() || reactor.stopping.get()) return;
                reactor.cancellations.add(this);
                if (retired.get() || reactor.stopping.get()) {
                    reactor.cancellations.remove(this);
                    return;
                }
                reactor.selector.wakeup();
            });
        }
    }

    private final class Reactor implements Runnable {
        final Selector selector;
        final ArrayBlockingQueue<Watch> commands;
        final ConcurrentHashMap<Watch, Boolean> admitted = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Watch> cancellations = new ConcurrentLinkedQueue<>();
        final CountDownLatch stopped = new CountDownLatch(1);
        final AtomicBoolean stopping = new AtomicBoolean();
        final Thread thread;
        Reactor(int index) throws IOException {
            selector = Selector.open();
            commands = new ArrayBlockingQueue<>(commandCapacity);
            thread = Thread.ofPlatform().daemon(true)
                    .name("ores-nio-reactor-" + index)
                    .unstarted(() -> OresVM.runAsNioWorker(this));
        }
        void start() { thread.start(); }
        // Only for a reactor whose Thread.start() failed. Never called after
        // a successful start, because the live loop owns selector closure.
        void abortUnstarted() {
            if (thread.getState() != Thread.State.NEW)
                throw new IllegalStateException("reactor was already started");
            stopping.set(true);
            try { selector.close(); } catch (IOException ignored) { }
            stopped.countDown();
        }
        void requestStop() { stopping.set(true); selector.wakeup(); }

        void retire(Watch watch, Integer events, Throwable error) {
            if (!watch.retired.compareAndSet(false, true)) return;
            if (watch.key != null) watch.key.cancel();
            admitted.remove(watch);
            synchronized (lifecycle) {
                if (ownership.get(watch.channel) == watch) ownership.remove(watch.channel);
                pending.decrementAndGet();
            }
            // Runtime subscribers run synchronously in OresFuture.settle().
            // Dispatch even host-only subscribers away from the selector:
            // a slow or adversarial subscriber must not park NIO readiness.
            // Every admitted watch owns one notification reservation until
            // terminal callbacks finish, so this bounded queue cannot fill
            // while normal lifecycle invariants are respected.
            try {
                dispatchNotification(() -> {
                    try {
                        if (!watch.future.isCancelled()) {
                            if (error != null) watch.future.failFromRuntime(error);
                            else watch.future.completeFromRuntime(events);
                        }
                    } finally {
                        releaseNotificationReservation();
                    }
                });
            } catch (RejectedExecutionException impossible) {
                // A selector must NEVER call synchronous Future subscribers
                // as a saturation fallback. Unexpected executor shutdown is
                // an invariant failure, not permission to run guest callbacks.
                releaseNotificationReservation();
                throw new IllegalStateException(
                        "NIO completion executor terminated before reactor quiescence", impossible);
            }
        }

        @Override public void run() {
            Throwable terminal = null;
            try {
                while (!stopping.get()) {
                    Watch watch;
                    int work = 0;
                    while (work++ < 128 && (watch = commands.poll()) != null) {
                        if (watch.future.isCancelled() || closed || stopping.get()) {
                            retire(watch, null, new IOException("NIO watch terminated"));
                            continue;
                        }
                        try {
                            watch.key = watch.channel.register(selector, watch.interests, watch);
                            if (watch.future.isCancelled())
                                retire(watch, null, new IOException("NIO watch cancelled"));
                        } catch (IOException | RuntimeException failure) {
                            retire(watch, null, failure);
                        }
                    }
                    if (stopping.get()) break;
                    int cancellationQuantum = 0;
                    while (cancellationQuantum++ < 128
                            && (watch = cancellations.poll()) != null) {
                        if (watch.future.isCancelled())
                            retire(watch, null, new IOException("NIO watch cancelled"));
                    }
                    // A hot socket population must not monopolize the
                    // reactor: bound readiness dispatch just like commands.
                    // Selected keys left over from the previous quantum must
                    // be served without parking for another select timeout.
                    if (commands.isEmpty() && cancellations.isEmpty()
                            && selector.selectedKeys().isEmpty())
                        selector.select(1000); // dedicated reactor only
                    else selector.selectNow();
                    Iterator<SelectionKey> selected = selector.selectedKeys().iterator();
                    int readyQuantum = 0;
                    while (readyQuantum++ < 128 && selected.hasNext()) {
                        SelectionKey key = selected.next();
                        selected.remove();
                        Watch watchReady = (Watch) key.attachment();
                        if (watchReady == null) continue;
                        if (!key.isValid()) retire(watchReady, null, new CancelledKeyException());
                        else retire(watchReady, key.readyOps(), null);
                    }
                    // Remaining cancellation notifications are processed
                    // next iteration without a full scan of all idle watches.
                }
            } catch (Throwable failure) {
                terminal = failure;
            } finally {
                stopping.set(true);
                Throwable reason = terminal == null ? new IOException("NIO reactor stopped") : terminal;
                for (Watch registered : admitted.keySet()) retire(registered, null, reason);
                commands.clear();
                cancellations.clear();
                try { selector.close(); } catch (IOException ignored) { }
                // Publish the completion-drain transition before advertising
                // reactor quiescence. An external awaitStopped() may wake at
                // stopped.countDown(), so shutdown must already be visible to
                // its subsequent completionExecutor.awaitTermination().
                if (liveReactors.decrementAndGet() == 0) {
                    synchronized (lifecycle) { closed = true; }
                    if (ownedCompletionExecutor != null) ownedCompletionExecutor.shutdown();
                }
                stopped.countDown();
            }
        }
    }

    /** Nonblocking stop request. A host may separately await termination. */
    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            for (Reactor reactor : reactors) reactor.requestStop();
        }
    }

    boolean awaitStopped(long time, TimeUnit unit) throws InterruptedException {
        ArrayList<Reactor> snapshot;
        synchronized (lifecycle) { snapshot = new ArrayList<>(reactors); }
        long deadline = System.nanoTime() + unit.toNanos(time);
        for (Reactor reactor : snapshot) {
            long left = deadline - System.nanoTime();
            if (left <= 0 || !reactor.stopped.await(left, TimeUnit.NANOSECONDS)) return false;
        }
        long left = deadline - System.nanoTime();
        if (left <= 0) return false;
        if (ownedCompletionExecutor != null)
            return ownedCompletionExecutor.awaitTermination(left, TimeUnit.NANOSECONDS);
        // The VM owns CONTROL shutdown, which must happen AFTER all of our
        // notifications finish. Never shut down its shared executor here.
        retryStagedNotifications();
        synchronized (notificationDrain) {
            while (notificationReservations.get() != 0) {
                left = deadline - System.nanoTime();
                if (left <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(notificationDrain, left);
            }
        }
        return true;
    }
}
