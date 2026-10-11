package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * One dedicated, blocking host CONTROL loop per actor carrier pool.
 *
 * <p>The descriptor is eagerly constructed; the thread starts on the first
 * successfully registered actor. It never runs guest code and does not own
 * carrier workers. All signals are coalesced under a single monitor, so
 * concurrent producer notifications cannot lose wakeups or fill an unbounded
 * event queue. The underlying carrier pool still owns bounded work admission.</p>
 *
 * <p>The pool scheduler is the sole authority admitting runnable actor turns
 * into the established bounded carrier executor. A request's caller waits for
 * the fast admission decision (not actor execution), so rejection remains
 * synchronous and actor cancellation semantics are unchanged. All guest code
 * still executes on carrier threads, never on the control thread.</p>
 */
final class ActorPoolControlScheduler {
    private final Object monitor = new Object();
    private final Set<ActorRuntime.ActorId> actors = new HashSet<>();
    private final ArrayDeque<DispatchRequest> dispatches = new ArrayDeque<>();
    private final String threadName;
    private final int capacity;
    private Thread thread;
    private boolean closed;
    private boolean terminated;
    private long pendingWakeups;
    private long handledWakeups;

    ActorPoolControlScheduler(String name) {
        this(name, 16_384);
    }

    ActorPoolControlScheduler(String name, int capacity) {
        threadName = Objects.requireNonNull(name, "name") + "-pool-scheduler";
        if (capacity <= 0) throw new IllegalArgumentException("pool scheduler capacity must be positive");
        this.capacity = capacity;
    }

    private static final class DispatchRequest {
        final ExecutorService workerPool;
        final int lane;
        final Runnable command;
        final CountDownLatch admitted = new CountDownLatch(1);
        volatile Throwable failure;

        private DispatchRequest(ExecutorService workerPool, int lane, Runnable command) {
            this.workerPool = workerPool;
            this.lane = lane;
            this.command = command;
        }

        void complete(Throwable failed) {
            failure = failed;
            admitted.countDown();
        }
    }

    /**
     * Fast synchronous admission handshake with the pool's dedicated CONTROL
     * thread. Does not await actor execution, only carrier queue placement.
     */
    void dispatch(ExecutorService workerPool, int lane, Runnable command) {
        Objects.requireNonNull(workerPool, "workerPool");
        Objects.requireNonNull(command, "command");
        DispatchRequest request = new DispatchRequest(workerPool, lane, command);
        synchronized (monitor) {
            if (closed || thread == null || Thread.currentThread() == thread)
                throw new RejectedExecutionException("pool scheduler is not accepting turns");
            if (dispatches.size() >= capacity)
                throw new RejectedExecutionException("pool scheduler admission queue full");
            dispatches.addLast(request);
            signalLocked();
        }
        boolean interrupted = false;
        for (;;) {
            try {
                request.admitted.await();
                break;
            } catch (InterruptedException exception) {
                // This has already been admitted to the scheduler queue.
                // Abandoning the handshake would orphan its actor schedule.
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (request.failure instanceof RuntimeException runtime) throw runtime;
        if (request.failure instanceof Error fatal) throw fatal;
        if (request.failure != null)
            throw new IllegalStateException("unexpected pool dispatch failure", request.failure);
    }

    void registerActor(ActorRuntime.ActorId actor) {
        Objects.requireNonNull(actor, "actor");
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("pool scheduler is closed");
            if (!actors.add(actor)) throw new IllegalStateException("duplicate actor registration");
            if (thread == null) {
                Thread created = Thread.ofPlatform().daemon(true)
                        .name(threadName).unstarted(this::controlLoop);
                try {
                    created.start();
                    thread = created;
                } catch (RuntimeException | Error failure) {
                    actors.remove(actor);
                    throw failure;
                }
            }
            signalLocked();
        }
    }

    void unregisterActor(ActorRuntime.ActorId actor) {
        synchronized (monitor) {
            if (actors.remove(actor)) signalLocked();
        }
    }

    void signalActorWork() {
        synchronized (monitor) {
            if (!closed && thread != null) signalLocked();
        }
    }

    /** Called by the owning master CONTROL authority, not by guest code. */
    void signalMasterControl() {
        synchronized (monitor) {
            if (!closed && thread != null) signalLocked();
        }
    }

    private void signalLocked() {
        // Coalesce signals rather than accumulate an unbounded event mailbox.
        pendingWakeups = 1L;
        monitor.notifyAll();
    }

    private void controlLoop() {
        try {
            for (;;) {
                DispatchRequest request;
                synchronized (monitor) {
                    while (!closed && pendingWakeups == 0 && dispatches.isEmpty()) {
                        try {
                            monitor.wait(); // event-driven select/_blocking wakeup
                        } catch (InterruptedException interrupted) {
                            // A spurious external interrupt must not end the
                            // pool's CONTROL loop or silently cancel actors.
                            if (!closed) continue;
                        }
                    }
                    if (closed) {
                        while (!dispatches.isEmpty()) {
                            dispatches.removeFirst().complete(
                                    new RejectedExecutionException("pool scheduler shutting down"));
                        }
                        return;
                    }
                    pendingWakeups = 0;
                    handledWakeups++;
                    request = dispatches.pollFirst();
                }
                if (request == null) continue;
                Throwable failure = null;
                try {
                    // This queues onto worker carriers; it must never invoke
                    // guest code inline on this CONTROL thread.
                    if (request.workerPool instanceof ActorAffinityDispatcher lanes) {
                        lanes.executeOnLane(request.lane, request.command);
                    } else {
                        request.workerPool.execute(request.command);
                    }
                } catch (RuntimeException | Error admissionFailure) {
                    failure = admissionFailure;
                } finally {
                    request.complete(failure);
                }
            }
        } finally {
            synchronized (monitor) {
                while (!dispatches.isEmpty()) {
                    dispatches.removeFirst().complete(
                            new RejectedExecutionException("pool scheduler thread terminated"));
                }
                terminated = true;
                monitor.notifyAll();
            }
        }
    }

    boolean started() {
        synchronized (monitor) { return thread != null; }
    }

    int actorCount() {
        synchronized (monitor) { return actors.size(); }
    }

    long handledWakeups() {
        synchronized (monitor) { return handledWakeups; }
    }

    void shutdown() {
        synchronized (monitor) {
            closed = true;
            actors.clear();
            while (!dispatches.isEmpty()) {
                dispatches.removeFirst().complete(
                        new RejectedExecutionException("pool scheduler shutting down"));
            }
            monitor.notifyAll();
        }
    }

    boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        long budget = Math.max(0, unit.toNanos(timeout));
        long start = System.nanoTime();
        synchronized (monitor) {
            if (!closed) return false;
            if (thread == null) return true;
            while (!terminated) {
                long remaining = budget - (System.nanoTime() - start);
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
            }
            return true;
        }
    }
}
