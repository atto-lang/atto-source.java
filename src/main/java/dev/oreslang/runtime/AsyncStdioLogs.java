package dev.oreslang.runtime;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * Context-owned, non-carrier stdout logging. Only the daemon writer touches the
 * host PrintWriter; actor/root callers format and enqueue immutable strings.
 *
 * Each accepted record has a completion Future. Detached Futures do not cancel
 * accepted output. A flush Future is a FIFO barrier for records accepted before
 * that call; it does not flush records admitted afterward.
 */
public final class AsyncStdioLogs implements AutoCloseable {
    private static final int MAX_RECORD_BYTES = 65_536;
    private static final int QUEUE_CAPACITY = 1_024;
    // Byte budget includes the in-flight record, not just the records in queue.
    private static final long MAX_PENDING_BYTES = 8L * 1024 * 1024;
    private static final int MAX_PENDING_BARRIERS = 1_024;
    private static final long CLOSE_TIMEOUT_MILLIS = 5_000;

    private record Record(long sequence, String text, int bytes, OresFuture<Void> completion) { }
    private record Barrier(long sequence, OresFuture<Void> completion) { }

    private final Object lock = new Object();
    private final ArrayBlockingQueue<Record> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final List<Barrier> barriers = new ArrayList<>();
    private final Semaphore wake = new Semaphore(0);
    private final PrintWriter writer;
    private Thread drain;
    private long submitted;
    private long completed;
    private long pendingBytes;
    private boolean accepting = true;
    private Throwable failure;

    public AsyncStdioLogs(PrintWriter writer) {
        this.writer = java.util.Objects.requireNonNull(writer, "writer");
        // Lazily start only for contexts that actually emit async logs.
    }

    /**
     * Fail immediately when full, closed, or oversized: records may never be
     * dropped silently. No sink I/O, future joining, or queue wait on caller.
     */
    public OresFuture<Void> logAsync(String text) {
        java.util.Objects.requireNonNull(text, "text");
        if (text.length() > MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("async stdio record exceeds 65536 UTF-8 bytes");
        }
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("async stdio record exceeds 65536 UTF-8 bytes");
        }
        synchronized (lock) {
            if (failure != null) throw new IllegalStateException("async stdio writer failed", failure);
            if (!accepting) throw new IllegalStateException("async stdio writer is closed");
            if (pendingBytes + bytes > MAX_PENDING_BYTES) {
                throw new IllegalStateException("async stdio pending byte budget exceeded (8388608 bytes)");
            }
            OresFuture<Void> completion = new OresFuture<>(() -> false, () -> { });
            long next = submitted + 1;
            if (!queue.offer(new Record(next, text, bytes, completion))) {
                throw new IllegalStateException("async stdio queue is full (1024 records)");
            }
            submitted = next;
            pendingBytes += bytes;
            if (drain == null) {
                try {
                    drain = Thread.ofPlatform().name("ores-stdio-async-drain")
                            .daemon(true).unstarted(this::runDrain);
                    drain.start();
                } catch (RuntimeException | Error startFailure) {
                    failPending(startFailure);
                    throw new IllegalStateException("async stdio worker failed to start", startFailure);
                }
            }
            wake.release();
            return completion;
        }
    }

    public OresFuture<Void> flushAsync() {
        synchronized (lock) {
            if (failure != null) return OresFuture.failed(failure);
            // Shutdown closes barrier admission as well as record admission.
            // Otherwise callers could keep adding awaiters to a stuck drain
            // after close() has already exhausted its bounded deadline.
            if (!accepting) throw new IllegalStateException("async stdio writer is closed");
            if (submitted == completed) return OresFuture.completed(null);
            if (barriers.size() >= MAX_PENDING_BARRIERS) {
                throw new IllegalStateException("async stdio flush barriers exhausted");
            }
            OresFuture<Void> barrier = new OresFuture<>(() -> false, () -> { });
            barriers.add(new Barrier(submitted, barrier));
            return barrier;
        }
    }

    private void runDrain() {
        try {
            for (;;) {
                synchronized (lock) {
                    if (!accepting && queue.isEmpty()) break;
                }
                wake.acquire();
                Record record = queue.poll();
                if (record == null) continue;
                Throwable error;
                synchronized (lock) { error = failure; }
                if (error == null) {
                    try {
                        // PrintWriter is shared with legacy synchronous stdio;
                        // its internal writer lock serializes both paths.
                        writer.print(record.text());
                        writer.flush();
                        if (writer.checkError()) {
                            throw new IllegalStateException("async stdio sink reported a write error");
                        }
                    } catch (Throwable writeFailure) {
                        error = writeFailure;
                    }
                }
                List<Barrier> ready = new ArrayList<>();
                synchronized (lock) {
                    if (error != null && failure == null) failure = error;
                    error = failure;
                    pendingBytes -= record.bytes();
                    completed = record.sequence();
                    for (int i = barriers.size() - 1; i >= 0; i--) {
                        Barrier barrier = barriers.get(i);
                        if (barrier.sequence() <= completed || error != null) {
                            barriers.remove(i);
                            ready.add(barrier);
                        }
                    }
                }
                if (error == null) record.completion().completeFromRuntime(null);
                else record.completion().failFromRuntime(error);
                for (Barrier barrier : ready) {
                    if (error == null) barrier.completion().completeFromRuntime(null);
                    else barrier.completion().failFromRuntime(error);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failPending(new IllegalStateException("async stdio drain was interrupted", interrupted));
        } catch (Throwable error) {
            failPending(error);
        }
    }

    private void failPending(Throwable error) {
        List<Barrier> pending;
        synchronized (lock) {
            if (failure == null) failure = error;
            accepting = false;
            pending = List.copyOf(barriers);
            barriers.clear();
        }
        for (Barrier barrier : pending) barrier.completion().failFromRuntime(error);
        Record remaining;
        while ((remaining = queue.poll()) != null) {
            synchronized (lock) { pendingBytes -= remaining.bytes(); }
            remaining.completion().failFromRuntime(error);
        }
    }

    /**
     * Graceful context shutdown drains accepted records before the host stream
     * is released. A stuck sink is reported after five seconds rather than
     * hanging process termination forever. SIGKILL and host crashes cannot be
     * flushed by software.
     */
    @Override public void close() {
        Thread worker;
        synchronized (lock) {
            accepting = false;
            worker = drain;
            if (worker != null) wake.release();
        }
        if (worker == null) return;
        try {
            worker.join(CLOSE_TIMEOUT_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while flushing async stdio", interrupted);
        }
        if (worker.isAlive()) {
            throw new IllegalStateException("async stdio drain exceeded 5000ms shutdown deadline; logs may be incomplete");
        }
        synchronized (lock) {
            if (failure != null) throw new IllegalStateException("async stdio drain failed", failure);
        }
    }
}
