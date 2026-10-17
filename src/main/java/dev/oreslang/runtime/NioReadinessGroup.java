package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
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
 * Process-capable, bounded NIO readiness manager. The normal throughput
 * profile constructs with zero reactor threads. The first accepted readiness
 * watch lazily starts reactor #0; up to four can be created on sustained
 * registration-command pressure, never just because sockets are waiting.
 *
 * This is a low-level host substrate. It provides one-shot nonblocking
 * TCP accept on VM-owned selectors, but does not yet replace source-level
 * std/net read/write or own the caller's listening socket. Only one pending
 * readiness watch per channel is permitted; the host retains the channel
 * until the watch settles.
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
    // A readiness watch retires BEFORE its Future subscribers drain. Keep a
    // separate per-listener lease across callback settlement and re-arm,
    // otherwise a second acceptAsync can race the first one's accept().
    private final IdentityHashMap<ServerSocketChannel, OresFuture<SocketChannel>> acceptLeases =
            new IdentityHashMap<>();
    // Keep exclusive read ownership until all completion callbacks drain.
    // A retired OP_READ key alone is not proof that its channel is free.
    private final IdentityHashMap<SocketChannel, OresFuture<byte[]>> readLeases =
            new IdentityHashMap<>();
    private final IdentityHashMap<SocketChannel, OresFuture<Integer>> writeLeases =
            new IdentityHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    // Includes selector watches that have retired but whose terminal Future
    // callbacks are still queued or running. Protects against unbounded
    // notification buildup when host subscribers run slowly.
    private final AtomicInteger notificationReservations = new AtomicInteger();
    private final AtomicInteger liveReactors = new AtomicInteger();
    // Standalone/VM groups own an isolated completion domain, but its carriers
    // are lazy: selector bootstrap itself creates no completion worker. An
    // optional VM executor can still be supplied by narrow embedding tests.
    private final Executor completionExecutor;
    private final ThreadPoolExecutor ownedCompletionExecutor;
    // Context-owned completion handoff: scheduler/actor carriers already in
    // the VM budget, never an extra NIO completion worker. Installed once
    // before first watch; standalone host groups retain the legacy pool.
    private volatile Executor boundCompletionExecutor;
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
        // Keep slow/adversarial runtime subscribers off selectors and CONTROL,
        // but do not pay resident completion threads at cold start. Independent
        // terminal notifications grow this bounded pool 0 -> 2 on demand.
        if (vmCompletionExecutor == null) {
            ownedCompletionExecutor = new AdaptiveCarrierExecutor(
                    0, 2, maxPending, new java.util.concurrent.ThreadFactory() {
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
        // Reactor objects, selectors and threads are demand-driven in the
        // throughput profile. Constructing a VM/readiness group therefore
        // owns zero OS threads and opens no Selector. RT preallocation is a
        // separate native profile; this Java manager starts on first watch.
    }

    /** Number of currently live reactor threads, not historical shards. */
    public int reactorCount() { return liveReactors.get(); }
    public int pendingCount() { return pending.get(); }
    int pendingNotifications() { return notificationReservations.get(); }
    int activeAcceptLeases() {
        synchronized (lifecycle) { return acceptLeases.size(); }
    }
    int activeReadLeases() {
        synchronized (lifecycle) { return readLeases.size(); }
    }
    int activeWriteLeases() {
        synchronized (lifecycle) { return writeLeases.size(); }
    }
    boolean usesVmCompletionCarriers() {
        return boundCompletionExecutor != null || ownedCompletionExecutor == null;
    }
    int completionCarrierCount() {
        return boundCompletionExecutor != null || ownedCompletionExecutor == null
                ? 0 : ownedCompletionExecutor.getPoolSize();
    }

    /** One-time, pre-admission binding for an actual VM actor scheduler.
     * Standalone host NIO groups may retain their transitional private pool.
     * Binding after first I/O admission would strand notifications across
     * executor domains, so it is explicitly forbidden.
     */
    void bindVmCompletionExecutor(Executor executor) {
        Objects.requireNonNull(executor, "executor");
        synchronized (lifecycle) {
            if (closed || boundCompletionExecutor != null || !reactors.isEmpty()
                    || pending.get() != 0 || notificationReservations.get() != 0)
                throw new IllegalStateException(
                        "NIO completion scheduler must be bound before first I/O admission");
            boundCompletionExecutor = executor;
        }
    }

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
        if (boundCompletionExecutor == null && ownedCompletionExecutor != null) {
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
        Executor target = boundCompletionExecutor;
        if (target == null && ownedCompletionExecutor != null) return;
        if (target == null) target = completionExecutor;
        synchronized (notificationDispatch) {
            for (int i = 0; i < 128; ++i) {
                Runnable next = stagedNotifications.peekFirst();
                if (next == null) return;
                try {
                    target.execute(() -> OresVM.runAsNioWorker(next));
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
        synchronized (lifecycle) {
            if (closed || !listener.isOpen())
                return OresFuture.failed(new IOException("NIO TCP listener closed"));
            if (acceptLeases.containsKey(listener))
                return OresFuture.failed(new IllegalStateException(
                        "listener already has an outstanding TCP accept"));
            acceptLeases.put(listener, accepted);
        }

        final class AcceptPump {
            // Counts callbacks AND in-progress registration; the listener's
            // lease cannot be returned between watch retirement and accept().
            final AtomicInteger inFlight = new AtomicInteger();

            void releaseIfTerminal() {
                if (!accepted.isDone() || inFlight.get() != 0) return;
                synchronized (lifecycle) {
                    if (inFlight.get() == 0 && acceptLeases.get(listener) == accepted)
                        acceptLeases.remove(listener);
                }
            }

            void arm() {
                inFlight.incrementAndGet(); // before any externally visible watch
                if (accepted.isDone()) {
                    inFlight.decrementAndGet();
                    releaseIfTerminal();
                    return;
                }
                final OresFuture<Integer> readiness;
                try {
                    readiness = NioReadinessGroup.this.watch(listener, SelectionKey.OP_ACCEPT);
                } catch (RuntimeException | Error problem) {
                    accepted.failFromRuntime(problem);
                    inFlight.decrementAndGet();
                    releaseIfTerminal();
                    if (problem instanceof VirtualMachineError fatal) throw fatal;
                    if (problem instanceof ThreadDeath fatal) throw fatal;
                    return;
                }
                pendingWatch.set(readiness);
                readiness.whenCompleteRuntime((events, failure) -> {
                    pendingWatch.compareAndSet(readiness, null);
                    try {
                        if (accepted.isDone()) return;
                        if (failure != null) {
                            accepted.failFromRuntime(failure);
                            return;
                        }
                        SocketChannel socket = null;
                        try {
                            // The caller owns the listener and must keep it
                            // nonblocking for the entire accepted operation.
                            if (listener.isBlocking())
                                throw new IllegalStateException("listener became blocking");
                            socket = listener.accept();
                            if (socket == null) {
                                // A competing raw host accept consumed the
                                // readiness; re-arm without spinning.
                                arm();
                                return;
                            }
                            socket.configureBlocking(false);
                            if (accepted.completeFromRuntime(socket)) {
                                socket = null; // terminal Future owns accepted FD
                            } else {
                                socket.close(); // cancellation won after accept
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
                    } finally {
                        inFlight.decrementAndGet();
                        releaseIfTerminal();
                    }
                });
                // Cancellation may have raced a re-arm. An already-ready
                // callback still holds inFlight until its cleanup finishes.
                if (accepted.isDone()) {
                    pendingWatch.compareAndSet(readiness, null);
                    readiness.cancel(false);
                }
            }
        }
        new AcceptPump().arm();
        return accepted;
    }

    /**
     * Read at most maximum bytes from a nonblocking TCP channel using the
     * VM-owned selector. A read of -1 becomes an empty byte array (EOF).
     *
     * Only one read operation per channel is admitted until its terminal
     * subscribers have returned. Cancellation wins only before the kernel
     * read has been claimed, so it cannot silently consume socket data after
     * reporting successful revocation. A transient zero-byte read re-arms
     * readiness, without parking a CONTROL or actor carrier.
     *
     * The caller retains the channel and must not perform raw concurrent
     * reads or switch it to blocking mode. This substrate does not confer a
     * guest NETWORK capability or replace connection-level source lowering.
     */
    public OresFuture<byte[]> readAsync(SocketChannel channel, int maximum) {
        Objects.requireNonNull(channel, "channel");
        if (maximum < 1 || maximum > 65536)
            return OresFuture.failed(new IllegalArgumentException("invalid TCP read bound"));
        if (channel.isBlocking())
            throw new IllegalArgumentException("TCP channel must be nonblocking");
        if (ownedCompletionExecutor == null)
            return OresFuture.failed(new IllegalStateException(
                    "TCP read requires dedicated NIO completion workers"));
        AtomicReference<OresFuture<Integer>> pendingWatch = new AtomicReference<>();
        // 0 = cancellable, 1 = kernel read in progress / committed, 2 =
        // cancellation won. A readiness callback may never read after 2.
        AtomicInteger kernelPhase = new AtomicInteger();
        OresFuture<byte[]> received = new OresFuture<>(
                () -> kernelPhase.compareAndSet(0, 2),
                () -> {
                    OresFuture<Integer> pending = pendingWatch.getAndSet(null);
                    if (pending != null) pending.cancel(false);
                });
        synchronized (lifecycle) {
            if (closed || !channel.isOpen())
                return OresFuture.failed(new IOException("NIO TCP channel closed"));
            if (readLeases.containsKey(channel) || writeLeases.containsKey(channel))
                return OresFuture.failed(new IllegalStateException(
                        "channel already has an outstanding TCP I/O operation"));
            readLeases.put(channel, received);
        }

        final class ReadPump {
            final AtomicInteger inFlight = new AtomicInteger();

            void releaseIfTerminal() {
                if (!received.isDone() || inFlight.get() != 0) return;
                synchronized (lifecycle) {
                    if (inFlight.get() == 0 && readLeases.get(channel) == received)
                        readLeases.remove(channel);
                }
            }

            void arm() {
                inFlight.incrementAndGet();
                if (received.isDone()) {
                    inFlight.decrementAndGet();
                    releaseIfTerminal();
                    return;
                }
                final OresFuture<Integer> readiness;
                try {
                    readiness = NioReadinessGroup.this.watch(channel, SelectionKey.OP_READ);
                } catch (RuntimeException | Error problem) {
                    received.failFromRuntime(problem);
                    inFlight.decrementAndGet();
                    releaseIfTerminal();
                    if (problem instanceof VirtualMachineError fatal) throw fatal;
                    if (problem instanceof ThreadDeath fatal) throw fatal;
                    return;
                }
                pendingWatch.set(readiness);
                readiness.whenCompleteRuntime((events, failure) -> {
                    pendingWatch.compareAndSet(readiness, null);
                    try {
                        if (received.isDone()) return;
                        if (failure != null) {
                            received.failFromRuntime(failure);
                            return;
                        }
                        // The Future cancellation admission and this claim
                        // share one CAS, never a racy isCancelled() snapshot.
                        if (!kernelPhase.compareAndSet(0, 1)) return;
                        try {
                            if (channel.isBlocking())
                                throw new IllegalStateException("TCP channel became blocking");
                            ByteBuffer bytes = ByteBuffer.allocate(maximum);
                            int n = channel.read(bytes);
                            if (n == 0) {
                                // No kernel consumption: cancellation can
                                // safely become admissible again before rearm.
                                kernelPhase.compareAndSet(1, 0);
                                arm();
                            } else if (n < 0) {
                                received.completeFromRuntime(new byte[0]);
                            } else {
                                received.completeFromRuntime(Arrays.copyOf(bytes.array(), n));
                            }
                        } catch (IOException | RuntimeException | Error problem) {
                            received.failFromRuntime(problem);
                            if (problem instanceof VirtualMachineError fatal) throw fatal;
                            if (problem instanceof ThreadDeath fatal) throw fatal;
                        }
                    } finally {
                        inFlight.decrementAndGet();
                        releaseIfTerminal();
                    }
                });
                // Cancellation may have raced watch admission. The completion
                // callback still owns inFlight until its cleanup is finished.
                if (received.isDone()) {
                    pendingWatch.compareAndSet(readiness, null);
                    readiness.cancel(false);
                }
            }
        }
        new ReadPump().arm();
        return received;
    }

    /**
     * Write an immutable snapshot of up to 64 KiB on the shared selector.
     * Partial positive writes hold the kernel-operation claim until all bytes
     * are sent: cancellation must not report success after any bytes have
     * escaped. A zero-byte kernel attempt can be rearmed and cancelled.
     *
     * Read and write share exclusive channel admission, because the manager
     * admits one readiness key per channel. This is an intentionally
     * conservative host substrate until a duplex connection owner lands.
     */
    public OresFuture<Integer> writeAsync(SocketChannel channel, byte[] bytes) {
        Objects.requireNonNull(channel, "channel");
        if (bytes == null || bytes.length > 65536)
            return OresFuture.failed(new IllegalArgumentException("invalid TCP write bound"));
        if (channel.isBlocking())
            throw new IllegalArgumentException("TCP channel must be nonblocking");
        if (ownedCompletionExecutor == null)
            return OresFuture.failed(new IllegalStateException(
                    "TCP write requires dedicated NIO completion workers"));
        // Even no-op writes must observe channel closure and the active
        // socket lease. Otherwise they silently succeed during another I/O
        // operation or after the manager has stopped.
        if (bytes.length == 0) {
            synchronized (lifecycle) {
                if (closed || !channel.isOpen())
                    return OresFuture.failed(new IOException("NIO TCP channel closed"));
                if (readLeases.containsKey(channel) || writeLeases.containsKey(channel))
                    return OresFuture.failed(new IllegalStateException(
                            "channel already has an outstanding TCP I/O operation"));
                return OresFuture.completed(0);
            }
        }
        byte[] snapshot = Arrays.copyOf(bytes, bytes.length);
        ByteBuffer buffer = ByteBuffer.wrap(snapshot);
        AtomicReference<OresFuture<Integer>> pendingWatch = new AtomicReference<>();
        // 0 = cancellable before any kernel output, 1 = bytes committed or
        // in progress, 2 = cancelled without output.
        AtomicInteger kernelPhase = new AtomicInteger();
        OresFuture<Integer> written = new OresFuture<>(
                () -> kernelPhase.compareAndSet(0, 2),
                () -> {
                    OresFuture<Integer> pending = pendingWatch.getAndSet(null);
                    if (pending != null) pending.cancel(false);
                });
        synchronized (lifecycle) {
            if (closed || !channel.isOpen())
                return OresFuture.failed(new IOException("NIO TCP channel closed"));
            if (readLeases.containsKey(channel) || writeLeases.containsKey(channel))
                return OresFuture.failed(new IllegalStateException(
                        "channel already has an outstanding TCP I/O operation"));
            writeLeases.put(channel, written);
        }

        final class WritePump {
            final AtomicInteger inFlight = new AtomicInteger();

            void releaseIfTerminal() {
                if (!written.isDone() || inFlight.get() != 0) return;
                synchronized (lifecycle) {
                    if (inFlight.get() == 0 && writeLeases.get(channel) == written)
                        writeLeases.remove(channel);
                }
            }

            void arm() {
                inFlight.incrementAndGet();
                if (written.isDone()) {
                    inFlight.decrementAndGet();
                    releaseIfTerminal();
                    return;
                }
                final OresFuture<Integer> readiness;
                try {
                    readiness = NioReadinessGroup.this.watch(channel, SelectionKey.OP_WRITE);
                } catch (RuntimeException | Error problem) {
                    written.failFromRuntime(problem);
                    inFlight.decrementAndGet();
                    releaseIfTerminal();
                    if (problem instanceof VirtualMachineError fatal) throw fatal;
                    if (problem instanceof ThreadDeath fatal) throw fatal;
                    return;
                }
                pendingWatch.set(readiness);
                readiness.whenCompleteRuntime((events, failure) -> {
                    pendingWatch.compareAndSet(readiness, null);
                    try {
                        if (written.isDone()) return;
                        if (failure != null) {
                            written.failFromRuntime(failure);
                            return;
                        }
                        if (!kernelPhase.compareAndSet(0, 1)
                                && kernelPhase.get() != 1) return;
                        try {
                            if (channel.isBlocking())
                                throw new IllegalStateException("TCP channel became blocking");
                            int count = channel.write(buffer);
                            if (buffer.hasRemaining()) {
                                if (count == 0 && buffer.position() == 0) {
                                    // No byte has escaped: revocation remains
                                    // safe until the next kernel-write claim.
                                    kernelPhase.compareAndSet(1, 0);
                                }
                                arm();
                            } else {
                                written.completeFromRuntime(snapshot.length);
                            }
                        } catch (IOException | RuntimeException | Error problem) {
                            written.failFromRuntime(problem);
                            if (problem instanceof VirtualMachineError fatal) throw fatal;
                            if (problem instanceof ThreadDeath fatal) throw fatal;
                        }
                    } finally {
                        inFlight.decrementAndGet();
                        releaseIfTerminal();
                    }
                });
                if (written.isDone()) {
                    pendingWatch.compareAndSet(readiness, null);
                    readiness.cancel(false);
                }
            }
        }
        new WritePump().arm();
        return written;
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
            final Reactor initial;
            try {
                initial = reactors.isEmpty() ? startReactorLocked() : null;
            } catch (IOException | RuntimeException failure) {
                return OresFuture.failed(failure);
            }
            Reactor target = initial == null ? leastBacklogged() : initial;
            // Reactor scaling is about *runnable registration work*, not the
            // number of pending network reads. The active kernel wait is idle.
            if (target.commands.size() >= Math.max(8, commandCapacity / 2)) {
                if (consecutiveCommandPressure < 8) consecutiveCommandPressure++;
            } else {
                consecutiveCommandPressure = 0;
            }
            if (consecutiveCommandPressure >= 8 && reactors.size() < maxReactors) {
                try {
                    target = startReactorLocked();
                    consecutiveCommandPressure = 0;
                } catch (IOException | RuntimeException | Error failure) {
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

    /** lifecycle monitor must be held. Opens/starts exactly one reactor or
     * leaves the current reactor set unchanged on failure.
     */
    private Reactor startReactorLocked() throws IOException {
        if (closed) throw new RejectedExecutionException("NIO reactor stopped");
        if (reactors.size() >= maxReactors)
            throw new RejectedExecutionException("NIO reactor limit reached");
        Reactor reactor = new Reactor(reactors.size());
        liveReactors.incrementAndGet();
        try {
            reactor.start();
        } catch (RuntimeException | Error failure) {
            liveReactors.decrementAndGet();
            reactor.abortUnstarted();
            throw failure;
        }
        reactors.add(reactor);
        return reactor;
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
                    boolean sweptCancelledKeys = false;
                    while (work++ < 128 && (watch = commands.poll()) != null) {
                        // A cancelled SelectionKey stays registered until a
                        // selector operation processes its cancellation set.
                        // The next watch can be admitted at any instant: an
                        // earlier commands.isEmpty() probe is NOT a fence.
                        // Sweep only after actually dequeuing the first watch
                        // so a fast TCP read/write re-arm cannot observe its
                        // old invalid key and fail with CancelledKeyException.
                        if (!sweptCancelledKeys) {
                            selector.selectNow(); // never parks the NIO worker
                            sweptCancelledKeys = true;
                        }
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
            if (reactors.isEmpty()) {
                // A never-used throughput VM owns no reactor thread, so no
                // Reactor.finally block exists to retire this lazy executor.
                if (ownedCompletionExecutor != null) ownedCompletionExecutor.shutdown();
                return;
            }
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
        if (boundCompletionExecutor == null && ownedCompletionExecutor != null)
            return ownedCompletionExecutor.awaitTermination(left, TimeUnit.NANOSECONDS);
        // The VM owns its actor and CONTROL shutdown, which must happen AFTER all of our
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
