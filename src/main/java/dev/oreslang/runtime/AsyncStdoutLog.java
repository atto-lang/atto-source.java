package dev.oreslang.runtime;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Context-owned ordered asynchronous stdout sink.
 *
 * Enqueue never performs stdout I/O on the caller. A bounded queue provides
 * explicit backpressure: full queues fail closed rather than silently dropping
 * records or parking an actor carrier. Flush is a FIFO barrier: it completes
 * only after all accepted preceding records reach PrintWriter.flush().
 *
 * This is an in-process drain guarantee, not fsync/durable storage or a
 * guarantee that an external pipe reader has consumed its bytes.
 */
public final class AsyncStdoutLog implements AutoCloseable {
    private sealed interface Event permits Record, Barrier, Stop {}
    private record Record(String line) implements Event {}
    private record Barrier(CompletableFuture<Void> result) implements Event {}
    private enum Stop implements Event { INSTANCE }

    private final PrintWriter writer;
    private final ArrayBlockingQueue<Event> queue;
    private final Thread worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private volatile Throwable terminalFailure;

    public AsyncStdoutLog(PrintWriter writer, int capacity) {
        this.writer = Objects.requireNonNull(writer, "writer");
        if (capacity < 2) throw new IllegalArgumentException("capacity must be >= 2");
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.worker = Thread.ofPlatform().name("ores-async-stdout").daemon(true).start(this::run);
    }

    /** Never waits on the writer, and never blocks waiting for a queue slot. */
    public void logAsync(String line) {
        Objects.requireNonNull(line, "line");
        synchronized (queue) {
            ensureOpen();
            if (!queue.offer(new Record(line)))
                throw new IllegalStateException("async stdout log buffer full");
        }
    }

    /** An asynchronous ordered flush barrier; callers can await it without parking an actor carrier. */
    public CompletableFuture<Void> flushAsync() {
        synchronized (queue) {
            if (terminalFailure != null) return CompletableFuture.failedFuture(terminalFailure);
            if (closed.get()) return terminated;
            CompletableFuture<Void> result = new CompletableFuture<>();
            if (!queue.offer(new Barrier(result)))
                return CompletableFuture.failedFuture(new IllegalStateException("async stdout log buffer full"));
            return result;
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("async stdout logging closed");
        if (terminalFailure != null) throw new IllegalStateException("async stdout logging failed", terminalFailure);
    }

    private void flushWriter() {
        writer.flush();
        if (writer.checkError()) throw new IllegalStateException("async stdout writer failed");
    }

    private void run() {
        try {
            for (;;) {
                Event event = queue.take();
                if (event instanceof Record record) {
                    writer.print(record.line());
                    writer.println();
                    if (writer.checkError()) throw new IllegalStateException("async stdout writer failed");
                } else if (event instanceof Barrier barrier) {
                    try { flushWriter(); barrier.result().complete(null); }
                    catch (Throwable failed) { barrier.result().completeExceptionally(failed); throw new IllegalStateException("async stdout flush failed", failed); }
                } else if (event == Stop.INSTANCE) {
                    flushWriter();
                    terminated.complete(null);
                    return;
                }
            }
        } catch (Throwable failed) {
            terminalFailure = failed;
            terminated.completeExceptionally(failed);
            for (Event event; (event = queue.poll()) != null;) {
                if (event instanceof Barrier b) b.result().completeExceptionally(failed);
            }
        }
    }

    /** Called from context teardown, not from an actor carrier. */
    @Override public void close() {
        close(Duration.ofSeconds(5));
    }

    public void close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long nanos = timeout.toNanos();
        long deadline = System.nanoTime() + nanos;
        synchronized (queue) {
            if (closed.compareAndSet(false, true) && terminalFailure == null) {
                // A shutdown controller may wait for capacity; actor carriers never do.
                try {
                    if (!queue.offer(Stop.INSTANCE, Math.max(0, nanos), TimeUnit.NANOSECONDS))
                        throw new IllegalStateException("async stdout log shutdown queue timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted scheduling async stdout drain", e);
                }
            }
        }
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            terminated.get(remaining, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("async stdout did not drain before shutdown", e);
        }
    }
}
