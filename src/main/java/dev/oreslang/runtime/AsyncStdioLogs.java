package dev.oreslang.runtime;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
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
    private static final int MAX_PENDING_BARRIERS = 1_024;
    private static final int MAX_PENDING_ADMISSIONS = 64;
    // Includes the currently executing sink record, not just queued records.
    private static final int MAX_ACCEPTED_BYTES = 4 * 1_048_576;
    private static final int MAX_PENDING_BYTES = 1_048_576;
    private static final long CLOSE_TIMEOUT_MILLIS = 5_000;

    private record Record(long sequence, String text, int bytes, OresFuture<Void> completion) { }
    private record Barrier(long sequence, OresFuture<Void> completion) { }

    private final Object lock = new Object();
    private final ArrayBlockingQueue<Record> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final List<Barrier> barriers = new ArrayList<>();
    private final ArrayDeque<PendingAdmission> waiting = new ArrayDeque<>();
    private int waitingBytes;
    private int acceptedBytes;

    private final class PendingAdmission {
        final String text;
        final int bytes;
        final OresFuture<Void> admitted;
        PendingAdmission(String text, int bytes) {
            this.text = text;
            this.bytes = bytes;
            this.admitted = new OresFuture<>(() -> cancelWaiting(this), () -> { });
        }
    }

    private boolean cancelWaiting(PendingAdmission pending) {
        synchronized (lock) {
            if (!waiting.remove(pending)) return false;
            waitingBytes -= pending.bytes;
            return true;
        }
    }

    // All queue/sequence changes and cancellation arbitration occur under lock.
    private boolean canAdmitLocked(int bytes) {
        return queue.remainingCapacity() > 0 && bytes <= MAX_ACCEPTED_BYTES - acceptedBytes;
    }

    private void enqueueLocked(String text, int bytes, OresFuture<Void> delivery) {
        if (!canAdmitLocked(bytes)) throw new IllegalStateException("async stdio queue is full (record or byte budget)");
        long next = submitted + 1;
        if (!queue.offer(new Record(next, text, bytes, delivery))) {
            throw new IllegalStateException("async stdio queue is full (1024 records)");
        }
        acceptedBytes += bytes;
        submitted = next;
        startDrainLocked();
        wake.release();
    }

    private static OresFuture<Void> deliveryFuture() {
        return new OresFuture<>(() -> false, () -> { });
    }

    private static int checkedBytes(String text) {
        java.util.Objects.requireNonNull(text, "text");
        if (text.length() > MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("async stdio record exceeds 65536 UTF-8 bytes");
        }
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("async stdio record exceeds 65536 UTF-8 bytes");
        }
        return bytes;
    }

    /**
     * Runtime-internal admission primitive, NOT yet wired to guest .write().
     * A pending future represents a full channel; the scheduler must park
     * the logical producer, not join/get on a carrier. Success means the
     * record was enqueued, not physically delivered. Cancellation may win
     * only before admission. The pending-record quota is a separate
     * resource limit; callers must charge their retained actor state as well.
     */
    OresFuture<Void> admitBuffered(String text) {
        int bytes = checkedBytes(text);
        synchronized (lock) {
            if (failure != null) return OresFuture.failed(failure);
            if (!accepting) return OresFuture.failed(
                    new IllegalStateException("async stdio writer is closed"));
            if (waiting.isEmpty() && canAdmitLocked(bytes)) {
                enqueueLocked(text, bytes, deliveryFuture());
                return OresFuture.completed(null);
            }
            if (waiting.size() >= MAX_PENDING_ADMISSIONS
                    || bytes > MAX_PENDING_BYTES - waitingBytes) {
                return OresFuture.failed(new IllegalStateException(
                        "async stdio pending admission quota exhausted"));
            }
            PendingAdmission pending = new PendingAdmission(text, bytes);
            waiting.addLast(pending);
            waitingBytes += bytes;
            return pending.admitted;
        }
    }

    // Called under lock; completion notifications are delivered outside lock.
    private List<PendingAdmission> detachWaitingLocked() {
        List<PendingAdmission> detached = new ArrayList<>(waiting);
        waiting.clear();
        waitingBytes = 0;
        return detached;
    }

    private static void rejectWaiting(List<PendingAdmission> pending, Throwable error) {
        for (PendingAdmission admission : pending) admission.admitted.failFromRuntime(error);
    }
    private final Semaphore wake = new Semaphore(0);
    private final PrintWriter writer;
    private Thread drain;
    private long submitted;
    private long completed;
    // Completed sink writes are distinct from delivered Future callbacks.
    // While an earlier completion is being notified, later flush calls must
    // queue behind it instead of returning an already-settled Future.
    private boolean deliveringCompletions;
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
        int bytes = checkedBytes(text);
        synchronized (lock) {
            if (failure != null) throw new IllegalStateException("async stdio writer failed", failure);
            if (!accepting) throw new IllegalStateException("async stdio writer is closed");
            if (!waiting.isEmpty()) {
                throw new IllegalStateException("async stdio queue is full (waiting producers)");
            }
            OresFuture<Void> completion = deliveryFuture();
            enqueueLocked(text, bytes, completion);
            return completion;
        }
    }

    public OresFuture<Void> flushAsync() {
        synchronized (lock) {
            if (failure != null) return OresFuture.failed(failure);
            if (!accepting) return OresFuture.failed(
                    new IllegalStateException("async stdio writer is closed"));
            if (submitted == completed && !deliveringCompletions)
                return OresFuture.completed(null);
            if (barriers.size() >= MAX_PENDING_BARRIERS) {
                throw new IllegalStateException("async stdio flush barriers exhausted");
            }
            OresFuture<Void> barrier = new OresFuture<>(() -> false, () -> { });
            barriers.add(new Barrier(submitted, barrier));
            return barrier;
        }
    }

    /** Called only under lock; failed startup revokes all queued admissions. */
    private void startDrainLocked() {
        if (drain != null) return;
        try {
            drain = Thread.ofPlatform().name("ores-stdio-async-drain")
                    .daemon(true).unstarted(this::runDrain);
            drain.start();
        } catch (RuntimeException | Error startFailure) {
            failPending(startFailure);
            throw new IllegalStateException("async stdio worker failed to start", startFailure);
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
                PendingAdmission promoted = null;
                List<PendingAdmission> rejected = List.of();
                synchronized (lock) {
                    if (error != null && failure == null) failure = error;
                    error = failure;
                    acceptedBytes -= record.bytes();
                    completed = record.sequence();
                    deliveringCompletions = true;
                    if (error != null) {
                        rejected = detachWaitingLocked();
                    } else if (accepting && !waiting.isEmpty() && canAdmitLocked(waiting.peekFirst().bytes)) {
                        promoted = waiting.removeFirst();
                        waitingBytes -= promoted.bytes;
                        enqueueLocked(promoted.text, promoted.bytes, deliveryFuture());
                    }
                    // Barrier sequence numbers are nondecreasing by admission.
                    // Complete the ready prefix in FIFO registration order so a
                    // later flush observer cannot be notified before an earlier
                    // barrier covering the same or an older output prefix.
                    int readyCount = 0;
                    for (Barrier barrier : barriers) {
                        if (error == null && barrier.sequence() > completed) break;
                        ready.add(barrier);
                        readyCount++;
                    }
                    barriers.subList(0, readyCount).clear();
                }
                if (promoted != null) promoted.admitted.completeFromRuntime(null);
                if (error != null) rejectWaiting(rejected, error);
                if (error == null) record.completion().completeFromRuntime(null);
                else record.completion().failFromRuntime(error);
                for (Barrier barrier : ready) {
                    if (error == null) barrier.completion().completeFromRuntime(null);
                    else barrier.completion().failFromRuntime(error);
                }
                // flushAsync() may have registered another barrier after the
                // sink write advanced completed but before its earlier
                // observers were notified. Drain all such late barriers before
                // publishing that callbacks are fully delivered. The final
                // empty check and flag transition are atomic with admission.
                for (;;) {
                    List<Barrier> late = new ArrayList<>();
                    synchronized (lock) {
                        int readyCount = 0;
                        for (Barrier barrier : barriers) {
                            if (error == null && barrier.sequence() > completed) break;
                            late.add(barrier);
                            readyCount++;
                        }
                        barriers.subList(0, readyCount).clear();
                        if (late.isEmpty()) {
                            deliveringCompletions = false;
                            break;
                        }
                    }
                    for (Barrier barrier : late) {
                        if (error == null) barrier.completion().completeFromRuntime(null);
                        else barrier.completion().failFromRuntime(error);
                    }
                }
            }
            // The final synchronous-stdout flush also belongs to this bounded
            // worker, even if no async log was ever admitted. Otherwise a
            // blocked host PrintWriter can hang OresContext.close() forever.
            // Never retry the sink after an already observed write failure.
            synchronized (lock) {
                if (failure != null) return;
            }
            writer.flush();
            if (writer.checkError()) {
                throw new IllegalStateException("async stdio sink reported a final flush error");
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
        List<PendingAdmission> rejected;
        synchronized (lock) {
            if (failure == null) failure = error;
            accepting = false;
            pending = List.copyOf(barriers);
            barriers.clear();
            rejected = detachWaitingLocked();
        }
        rejectWaiting(rejected, error);
        for (Barrier barrier : pending) barrier.completion().failFromRuntime(error);
        Record remaining;
        while ((remaining = queue.poll()) != null) remaining.completion().failFromRuntime(error);
    }

    /** Seal after actor/native-I/O and auxiliary producer shutdown.
     * This does not join or wait on the potentially blocked host writer.
     * Once sealed, new logAsync records and flushAsync barriers are rejected;
     * accepted records still drain through close().
     */
    public void sealAdmission() {
        List<PendingAdmission> rejected;
        synchronized (lock) {
            accepting = false;
            rejected = detachWaitingLocked();
            if (drain != null) wake.release();
        }
        rejectWaiting(rejected, new IllegalStateException("async stdio writer is closed"));
    }

    /**
     * Graceful context shutdown drains accepted records before the host stream
     * is released. A stuck sink is reported after five seconds rather than
     * hanging process termination forever. SIGKILL and host crashes cannot be
     * flushed by software.
     */
    @Override public void close() {
        Thread worker;
        sealAdmission();
        synchronized (lock) {
            // A context may have used only synchronous stdout. Spawn the
            // drain worker on close to perform that last host flush off-carrier.
            if (drain == null && failure == null) startDrainLocked();
            worker = drain;
            if (worker != null) wake.release();
        }
        if (worker == null) {
            synchronized (lock) {
                if (failure != null) throw new IllegalStateException("async stdio drain failed", failure);
            }
            return;
        }
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
