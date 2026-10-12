package dev.oreslang.runtime;

import java.util.EnumSet;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Exactly one VM-owned master scheduler CONTROL thread.
 *
 * <p>Master -> pool communication only enqueues a wake event. The pool's own
 * dedicated CONTROL thread observes the signal via its blocking wait; guest
 * actor/continuation code never executes on either scheduler thread.</p>
 */
final class OresVMMasterControlLoop {
    private final Object monitor = new Object();
    private final EnumSet<ActorRuntime.ActorKind> pending =
            EnumSet.noneOf(ActorRuntime.ActorKind.class);
    private Thread thread;
    private ActorRuntime attached;
    private boolean closed;
    private boolean terminated;

    void start() {
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("master scheduler closed");
            if (thread != null) return;
            Thread created = Thread.ofPlatform().daemon(true)
                    .name("oresvm-master-scheduler")
                    .unstarted(this::runLoop);
            created.start();
            thread = created;
        }
    }

    void attach(ActorRuntime actors) {
        Objects.requireNonNull(actors, "actors");
        synchronized (monitor) {
            if (closed || thread == null) throw new IllegalStateException("master scheduler unavailable");
            if (attached != null && attached != actors)
                throw new IllegalStateException("master scheduler cannot change actor runtime");
            attached = actors;
            monitor.notifyAll();
        }
    }

    void signal(ActorRuntime.ActorKind kind) {
        Objects.requireNonNull(kind, "kind");
        synchronized (monitor) {
            if (closed) return;
            pending.add(kind); // coalesce repeated actor-turn readiness notifications
            monitor.notifyAll();
        }
    }

    private void runLoop() {
        try {
            for (;;) {
                ActorRuntime target;
                EnumSet<ActorRuntime.ActorKind> batch;
                synchronized (monitor) {
                    while (!closed && (attached == null || pending.isEmpty())) {
                        try {
                            monitor.wait(); // blocking select/_blocking equivalent
                        } catch (InterruptedException interrupted) {
                            if (!closed) continue;
                        }
                    }
                    if (closed) return;
                    target = attached;
                    batch = EnumSet.copyOf(pending);
                    pending.clear();
                }
                for (ActorRuntime.ActorKind kind : batch) {
                    // May only signal the subordinate scheduler, never run its
                    // handlers on the master scheduler CONTROL thread.
                    target.wakeActorPoolSchedulerFromMaster(kind);
                }
            }
        } finally {
            synchronized (monitor) {
                terminated = true;
                monitor.notifyAll();
            }
        }
    }

    int startedThreads() {
        synchronized (monitor) { return thread == null ? 0 : 1; }
    }

    void shutdown() {
        synchronized (monitor) {
            closed = true;
            pending.clear();
            attached = null;
            monitor.notifyAll();
        }
    }

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long budget = Math.max(0, Objects.requireNonNull(unit).toNanos(timeout));
        long since = System.nanoTime();
        synchronized (monitor) {
            if (!closed) return false;
            if (thread == null) return true;
            while (!terminated) {
                long remaining = budget - (System.nanoTime() - since);
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
            }
            return true;
        }
    }
}
