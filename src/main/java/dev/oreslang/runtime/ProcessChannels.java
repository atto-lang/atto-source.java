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

        // Direction and capability checks must guard the primitive itself:
        // source readch/writech lower directly to these methods, not only
        // through SelectCase factories. A Channel<String> upcast is no bypass.
        @Override public java.util.Optional<T> tryRead() {
            checkGuestRead();
            return super.tryRead();
        }

        @Override public OresFuture<T> readAsync() {
            checkGuestRead();
            return super.readAsync();
        }

        @Override public boolean tryWrite(T value) {
            checkGuestWrite();
            return super.tryWrite(value);
        }

        @Override public OresFuture<Void> writeAsync(T value) {
            checkGuestWrite();
            return super.writeAsync(value);
        }

        /** VM-only console record submission; does not grant guest write access. */
        private boolean writeOwned(T value) {
            return super.tryWrite(value);
        }

        /** Guest code cannot close process-owned channels through Channel.close. */
        @Override public void close() {
            throw new IllegalStateException(name + " is process-owned; close is reserved to the VM");
        }

        @Override public void close(Throwable cause) {
            throw new IllegalStateException(name + " is process-owned; close is reserved to the VM");
        }

        private void closeOwned() { super.close((Throwable) null); }
        private void closeOwned(Throwable failed) { super.close(failed); }
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
    private final Map<String, CompletableFuture<Void>> stderrBarriers =
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
                .name("ores-process-stderr").start(() -> drain(err, stderr, stderrBarriers));
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
        if (closing.get()) throw new IllegalStateException("async stdout is closing");
        if (outputFailure.get() != null)
            throw new IllegalStateException("async stdout writer failed", outputFailure.get());
        if (!out.writeOwned(Objects.requireNonNull(record) + System.lineSeparator()))
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
        return CompletableFuture.allOf(
                flushOutput(out, stdoutBarriers),
                flushOutput(err, stderrBarriers));
    }

    private CompletableFuture<Void> flushOutput(
            ConsoleChannel<String> channel, Map<String, CompletableFuture<Void>> barriers) {
        // The private identity key cannot be forged by equal-content guest strings.
        String marker = new String("\u0000ORES_IO_FLUSH_MARKER");
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        synchronized (barriers) {
            if (barriers.size() >= 1024)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("too many outstanding console flush barriers"));
            barriers.put(marker, barrier);
        }
        ChannelRuntime.writeForRuntime(channel, marker).whenCompleteRuntime((ignored, error) -> {
            if (error != null) {
                barriers.remove(marker);
                barrier.completeExceptionally(error);
            }
        });
        return barrier;
    }

    private void drain(ConsoleChannel<String> source,
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
            if (!source.isClosed()) source.closeOwned(failed);
        }
    }

    private void feedInput() {
        try {
            for (String line; !closing.get() && (line = stdin.readLine()) != null;) {
                ChannelRuntime.writeForRuntime(in, line).join();
            }
        } catch (Throwable failed) {
            // Real input errors remain errors; VM-initiated shutdown is orderly.
            if (!closing.get()) in.closeOwned(failed);
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
