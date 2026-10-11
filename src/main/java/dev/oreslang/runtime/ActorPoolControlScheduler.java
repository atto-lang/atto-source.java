package dev.oreslang.runtime;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
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
 * <p>This is the pool lifecycle/control substrate; actual runBatch admission
 * remains on ActorRuntime's established bounded dispatcher path until the
 * scheduler-dispatch migration has its own source-actor cancellation proof.</p>
 */
final class ActorPoolControlScheduler {
    private final Object monitor = new Object();
    private final Set<ActorRuntime.ActorId> actors = new HashSet<>();
    private final String threadName;
    private Thread thread;
    private boolean closed;
    private boolean terminated;
    private long pendingWakeups;
    private long handledWakeups;

    ActorPoolControlScheduler(String name) {
        threadName = Objects.requireNonNull(name, "name") + "-pool-scheduler";
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
            synchronized (monitor) {
                for (;;) {
                    while (!closed && pendingWakeups == 0) {
                        try {
                            monitor.wait(); // host-level *_blocking/select wakeup
                        } catch (InterruptedException interrupted) {
                            // Interrupt is a wakeup, never an implicit shutdown.
                            if (!closed) continue;
                        }
                    }
                    if (closed) return;
                    pendingWakeups = 0;
                    // All actor bookkeeping and CONTROL observations stay on
                    // this thread. Never invoke guest handlers while locked.
                    handledWakeups++;
                }
            }
        } finally {
            synchronized (monitor) {
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
