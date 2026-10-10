package dev.oreslang.runtime;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Three context-owned, identity-stable specializations of Channel<String>.
 * out/err carry arbitrary text chunks; in carries text lines. They are not
 * host process-global System streams and never confer cross-context authority.
 */
public final class ProcessChannels implements AutoCloseable {
    public enum Direction { READ, WRITE }

    public static final class ConsoleChannel<T> extends ChannelRuntime.Channel<T> {
        private final OresContext owner;
        private final IsolatePolicy.Capability capability;
        private final Direction direction;
        private final String name;

        private ConsoleChannel(OresContext owner, String name,
                               IsolatePolicy.Capability capability, Direction direction, int capacity) {
            super(capacity);
            this.owner = Objects.requireNonNull(owner);
            this.capability = capability;
            this.direction = direction;
            this.name = name;
        }

        public void checkGuestRead() {
            owner.requireCapability(capability, name + ".readch");
            if (direction != Direction.READ)
                throw new IllegalArgumentException(name + " is a write-only channel");
        }

        public void checkGuestWrite() {
            owner.requireCapability(capability, name + ".writech");
            if (direction != Direction.WRITE)
                throw new IllegalArgumentException(name + " is a read-only channel");
        }

        public Direction direction() { return direction; }

        /** Guest code cannot close process-owned channels through Channel.close. */
        @Override public void close() {
            throw new IllegalStateException(name + " is process-owned; close is reserved to the VM");
        }

        @Override public void close(Throwable cause) {
            throw new IllegalStateException(name + " is process-owned; close is reserved to the VM");
        }

        private void closeOwned() { super.close((Throwable) null); }
    }

    private final ConsoleChannel<String> out;
    private final ConsoleChannel<String> err;
    private final ConsoleChannel<String> in;
    private final PrintWriter stdout;
    private final PrintWriter stderr;
    private final BufferedReader stdin;
    private final Thread outWorker;
    private final Thread errWorker;
    private final Thread inWorker;
    private final AtomicBoolean stdinStarted = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<Throwable> outputFailure =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final Map<String, CompletableFuture<Void>> stdoutBarriers =
            Collections.synchronizedMap(new IdentityHashMap<>());

    public ProcessChannels(OresContext owner, BufferedReader stdin,
                           PrintWriter stdout, PrintWriter stderr) {
        this.stdin = Objects.requireNonNull(stdin);
        this.stdout = Objects.requireNonNull(stdout);
        this.stderr = Objects.requireNonNull(stderr);
        this.out = new ConsoleChannel<>(owner, "process.stdout",
                IsolatePolicy.Capability.STDOUT, Direction.WRITE, 8192);
        this.err = new ConsoleChannel<>(owner, "process.stderr",
                IsolatePolicy.Capability.STDOUT, Direction.WRITE, 8192);
        this.in = new ConsoleChannel<>(owner, "process.stdin",
                IsolatePolicy.Capability.STDIN, Direction.READ, 64);
        this.outWorker = Thread.ofPlatform().daemon(true)
                .name("ores-process-stdout").start(() -> drain(out, stdout, stdoutBarriers));
        this.errWorker = Thread.ofPlatform().daemon(true)
                .name("ores-process-stderr").start(() -> drain(err, stderr, null));
        this.inWorker = Thread.ofPlatform().daemon(true)
                .name("ores-process-stdin").unstarted(this::feedInput);
    }

    public ConsoleChannel<String> stdout() { return out; }
    public ConsoleChannel<String> stderr() { return err; }
    public ConsoleChannel<String> stdin() {
        if (stdinStarted.compareAndSet(false, true)) inWorker.start();
        return in;
    }

    /** stdout enqueue is immediate admission, never a delivery acknowledgement. */
    public void logAsync(String record) {
        if (!out.tryWrite(Objects.requireNonNull(record) + System.lineSeparator()))
            throw new IllegalStateException("async stdout channel buffer full");
    }

    /**
     * FIFO delivery barrier shared by stdio.out/process.stdout and async logs.
     * The unique marker is never exposed to userland, and is compared by identity.
     */
    public CompletableFuture<Void> flushAsyncLogs() {
        if (outputFailure.get() != null)
            return CompletableFuture.failedFuture(outputFailure.get());
        if (closing.get()) return CompletableFuture.failedFuture(
                new IllegalStateException("console channels are closing"));
        String marker = new String("\u0000ORES_STDOUT_FLUSH_MARKER");
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        synchronized (stdoutBarriers) {
            if (stdoutBarriers.size() >= 1024)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("too many outstanding stdout flush barriers"));
            stdoutBarriers.put(marker, barrier);
        }
        ChannelRuntime.writeForRuntime(out, marker).whenCompleteRuntime((ignored, error) -> {
            if (error != null) {
                stdoutBarriers.remove(marker);
                barrier.completeExceptionally(error);
            }
        });
        return barrier;
    }

    private static void drain(ConsoleChannel<String> source,
                              PrintWriter target, Map<String, CompletableFuture<Void>> barriers) {
        try {
            for (;;) {
                String chunk;
                try { chunk = ChannelRuntime.readForRuntime(source).join(); }
                catch (java.util.concurrent.CompletionException failure) {
                    if (failure.getCause() instanceof ChannelRuntime.ChannelClosedException) break;
                    throw failure;
                }
                CompletableFuture<Void> barrier = barriers == null ? null : barriers.remove(chunk);
                if (barrier != null) {
                    target.flush();
                    if (target.checkError())
                        barrier.completeExceptionally(new IllegalStateException("stdout flush failed"));
                    else barrier.complete(null);
                } else {
                    target.print(chunk);
                    if (target.checkError()) throw new IllegalStateException("console output failed");
                }
            }
            target.flush();
            if (target.checkError()) throw new IllegalStateException("console output flush failed");
        } catch (Throwable failed) {
            if (barriers != null) {
                synchronized (barriers) {
                    for (CompletableFuture<Void> barrier : barriers.values())
                        barrier.completeExceptionally(failed);
                    barriers.clear();
                }
            }
            outputFailure.compareAndSet(null, failed);
            // Channel closure wakes pending guest writers/readers.
            if (!source.isClosed()) source.closeOwned();
        }
    }

    private void feedInput() {
        try {
            for (String line; !closing.get() && (line = stdin.readLine()) != null;) {
                ChannelRuntime.writeForRuntime(in, line).join();
            }
        } catch (Throwable ignored) {
            // stdin cancellation/EOF closes the channel; consumers observe EOF.
        } finally {
            in.closeOwned();
        }
    }

    @Override public void close() {
        if (!closing.compareAndSet(false, true)) return;
        out.closeOwned();
        err.closeOwned();
        // Interrupt the daemon feeder. The embedding host owns stdin and may
        // be performing an uninterruptible read; never close its stream here.
        if (stdinStarted.get()) inWorker.interrupt();
        in.closeOwned();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try {
            outWorker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(
                    Math.max(0, deadline - System.nanoTime()))));
            errWorker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(
                    Math.max(0, deadline - System.nanoTime()))));
            if (outWorker.isAlive() || errWorker.isAlive())
                throw new IllegalStateException("console channel workers did not drain before VM shutdown");
            if (outputFailure.get() != null)
                throw new IllegalStateException("console output worker failed", outputFailure.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted draining console channels", interrupted);
        }
    }
}
