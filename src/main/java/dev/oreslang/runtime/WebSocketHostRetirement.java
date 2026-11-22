package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Emergency physical-close lane when AsyncNativeIo CONTROL admission is full.
 *
 * <p>Only already-admitted host connections are queued. A bounded, fixed
 * number of daemon workers drains a deduplicated FIFO; one stalled kernel
 * close cannot monopolize every retirement lane. This is not an actor
 * scheduler or an authority-granting API. No physical close executes on
 * an actor or CONTROL notification carrier.</p>
 *
 * <p>There is no unbounded per-request executor queue: the queue contains
 * at most one entry for each pending physical connection. The process-wide
 * count of listeners/connections still requires a separate aggregate budget.
 * This is host-worker-scheduled cleanup, NOT hard-real-time retirement.</p>
 */
final class WebSocketHostRetirement implements AutoCloseable {
    private static final WebSocketHostRetirement SHARED =
            new WebSocketHostRetirement(4, 8, 25);

    private final Set<AutoCloseable> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<AutoCloseable> fifo =
            new ConcurrentLinkedQueue<>();
    private final ScheduledThreadPoolExecutor workers;
    private final int batchSize;
    private volatile boolean closed;

    static WebSocketHostRetirement shared() {
        return SHARED;
    }

    /** Package-private deterministic test seam; no guest or source exposure. */
    WebSocketHostRetirement(int lanes, int batchSize, int delayMillis) {
        if (lanes < 1 || lanes > 4 || batchSize < 1 || batchSize > 32
                || delayMillis < 1 || delayMillis > 1000) {
            throw new IllegalArgumentException("invalid WebSocket host retirement budget");
        }
        this.batchSize = batchSize;
        workers = new ScheduledThreadPoolExecutor(lanes, task -> {
            Thread thread = new Thread(task, "ores-websocket-host-retirement");
            thread.setDaemon(true);
            return thread;
        });
        workers.setRemoveOnCancelPolicy(true);
        workers.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        for (int i = 0; i < lanes; i++) {
            workers.scheduleWithFixedDelay(
                    this::drainBatch, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    /** Atomic, nonblocking host notification: never calls close inline. */
    void request(AutoCloseable resource) {
        Objects.requireNonNull(resource, "WebSocket resource");
        if (closed) throw new IllegalStateException("host retirement lane closed");
        if (pending.add(resource)) fifo.offer(resource);
    }

    /** Cancel a redundant pending notification after successful direct close. */
    void forget(AutoCloseable resource) {
        pending.remove(Objects.requireNonNull(resource, "WebSocket resource"));
    }

    private void drainBatch() {
        for (int i = 0; i < batchSize; i++) {
            AutoCloseable resource = fifo.poll();
            if (resource == null) return;
            if (!pending.contains(resource)) continue;
            try {
                resource.close();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Exception | Error failedClose) {
                // The physical AsyncNativeIo resource ledger, not an early
                // Future receipt, still owns incomplete native loans.
                // Do not stop unrelated host retirement lanes.
            } finally {
                pending.remove(resource);
            }
        }
    }

    @Override public void close() {
        closed = true;
        workers.shutdownNow();
        fifo.clear();
        pending.clear();
    }
}
