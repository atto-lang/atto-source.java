// M7 host-side cooperative scheduler with a closed-world compiled-frame ABI.
// A frame yields ONLY at compiler-inserted safe points. Never claims forced preemption,
// arbitrary guest borrow safety, or hard-real-time scheduling guarantees.
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public final class AttoM7CooperativeRuntime implements AutoCloseable {
    public enum Admission { ACCEPTED, FULL, CLOSED, STALE }
    public enum Step { YIELD, DONE }
    public enum ControlKind { STOP, PANIC }
    @FunctionalInterface public interface FrameFactory { Frame new_frame(long value); }
    public interface Frame {
        Step advance(Slice slice) throws Exception;
        long result();
        int resume_pc();
    }
    public static final class Slice {
        private final int maxReductions;
        private final long deadline;
        private int charged;
        Slice(int maxReductions, long maxNanos) {
            this.maxReductions = maxReductions;
            this.deadline = System.nanoTime() + maxNanos;
        }
        // Called at a compiler-verified safe point AFTER committing live locals.
        public boolean cooperate() {
            if (charged == maxReductions)
                throw new IllegalStateException("safe-point reductions exceeded");
            charged++;
            return charged >= maxReductions || System.nanoTime() - deadline >= 0;
        }
        public int charged_reductions() { return charged; }
    }
    // Record equality is NOT authority. Resolve by object identity plus generation.
    public record ActorRef(String name, long generation) {}
    public record Snapshot(long accepted, long completed, long failed, long abandoned,
                           long yielded, long slices, long resultSum, int queued,
                           boolean inFlight, boolean running, boolean accepting,
                           boolean retired, int controlsDelivered, ControlKind lastControl,
                           int maxConcurrentReceives, int carrierIdentities, int resumePc) {}
    public record Queues(int ready, int control, int actors, int running) {}

    private static final class Actor {
        final ActorRef ref;
        final FrameFactory factory;
        final int capacity;
        final ArrayDeque<Long> inbox = new ArrayDeque<>();
        final Set<Long> carriers = new HashSet<>();
        Frame active;
        boolean running, scheduled, accepting = true, retired, fenced;
        boolean controlQueued;
        ControlKind pendingControl, lastControl;
        long accepted, completed, failed, abandoned, yielded, slices, resultSum;
        int concurrent, maxConcurrent, controlsDelivered;
        Actor(ActorRef ref, int capacity, FrameFactory factory) {
            this.ref = ref; this.capacity = capacity; this.factory = factory;
        }
    }

    private final Object gate = new Object();
    private final Map<String, Actor> actors = new HashMap<>();
    private final Map<String, Long> generations = new HashMap<>();
    private final ArrayDeque<Actor> ready = new ArrayDeque<>();
    private final ArrayDeque<Actor> controls = new ArrayDeque<>();
    private final List<Thread> carriers = new ArrayList<>();
    private final Thread supervisor;
    private final int maxActors, reductionBudget;
    private final long nanoBudget;
    private boolean closing, stopping, fullyClosed;

    public AttoM7CooperativeRuntime(int carrierCount, int maxActors,
                                    int reductionBudget, long sliceMillis) {
        if (carrierCount < 1 || carrierCount > 32 || maxActors < 1 || maxActors > 256 ||
            reductionBudget < 1 || reductionBudget > 65536 ||
            sliceMillis < 1 || sliceMillis > 60_000)
            throw new IllegalArgumentException("bounded carrier, actor and slice budgets required");
        this.maxActors = maxActors;
        this.reductionBudget = reductionBudget;
        this.nanoBudget = TimeUnit.MILLISECONDS.toNanos(sliceMillis);
        this.supervisor = new Thread(this::supervise, "atto-m7-CONTROL");
        supervisor.start();
        for (int i = 0; i < carrierCount; i++) {
            Thread thread = new Thread(this::carry, "atto-m7-carrier-" + i);
            carriers.add(thread);
            thread.start();
        }
    }

    public ActorRef spawn(String name, int capacity, FrameFactory factory) {
        if (name == null || name.isEmpty() || name.length() > 128 || factory == null ||
            capacity < 1 || capacity > 4096)
            throw new IllegalArgumentException("invalid actor");
        synchronized (gate) {
            if (closing || stopping) throw new IllegalStateException("runtime closed");
            Actor previous = actors.get(name);
            if (previous != null && !previous.retired)
                throw new IllegalStateException("actor name already active");
            if (previous == null && actors.size() >= maxActors)
                throw new IllegalStateException("actor registration limit");
            long generation = Math.addExact(generations.getOrDefault(name, 0L), 1L);
            generations.put(name, generation);
            var ref = new ActorRef(name, generation);
            actors.put(name, new Actor(ref, capacity, factory));
            return ref;
        }
    }
    private Actor resolve(ActorRef ref) {
        if (ref == null) return null;
        Actor actor = actors.get(ref.name());
        return actor != null && actor.ref == ref && !actor.retired ? actor : null;
    }
    public Admission try_send(ActorRef ref, long value) {
        synchronized (gate) {
            Actor actor = resolve(ref);
            if (actor == null) return Admission.STALE;
            if (closing || stopping || !actor.accepting || actor.fenced) return Admission.CLOSED;
            if (actor.inbox.size() >= actor.capacity) return Admission.FULL;
            actor.inbox.addLast(value);
            actor.accepted++;
            schedule(actor);
            return Admission.ACCEPTED;
        }
    }
    private void schedule(Actor actor) {
        if (!actor.scheduled && !actor.running && !actor.fenced &&
            (actor.active != null || !actor.inbox.isEmpty())) {
            actor.scheduled = true;
            ready.addLast(actor);
            gate.notifyAll();
        }
    }
    private void control(Actor actor, ControlKind kind) {
        if (actor.controlQueued) {
            if (kind == ControlKind.PANIC) actor.pendingControl = kind;
            return;
        }
        if (actor.lastControl == ControlKind.PANIC ||
            (actor.lastControl == ControlKind.STOP && kind == ControlKind.STOP))
            return;
        actor.controlQueued = true;
        actor.pendingControl = kind;
        controls.addLast(actor);
        gate.notifyAll();
    }
    public boolean request_stop(ActorRef ref) {
        synchronized (gate) {
            Actor actor = resolve(ref);
            if (actor == null) return false;
            actor.accepting = false;
            control(actor, ControlKind.STOP);
            gate.notifyAll();
            return true;
        }
    }
    private void retire_if_quiescent(Actor actor) {
        if (!actor.accepting && actor.active == null && !actor.running &&
            actor.inbox.isEmpty() && !actor.controlQueued) {
            actor.retired = true;
            if (actor.scheduled) ready.remove(actor);
            actor.scheduled = false;
            gate.notifyAll();
        }
    }
    private void supervise() {
        try {
            synchronized (gate) {
                for (;;) {
                    while (controls.isEmpty() && !stopping) gate.wait();
                    if (controls.isEmpty() && stopping) return;
                    Actor actor = controls.removeFirst();
                    ControlKind kind = actor.pendingControl;
                    actor.pendingControl = null;
                    actor.controlQueued = false;
                    actor.controlsDelivered++;
                    actor.lastControl = kind;
                    if (kind == ControlKind.PANIC) {
                        actor.fenced = true;
                        actor.accepting = false;
                        actor.abandoned += actor.inbox.size();
                        actor.inbox.clear();
                        // A currently running callback is allowed to physically quiesce.
                        // A yielded frame is safe to abandon here (no carrier owns it).
                        if (!actor.running && actor.active != null) {
                            actor.abandoned++;
                            actor.active = null;
                        }
                    }
                    if (!actor.fenced) schedule(actor);
                    retire_if_quiescent(actor);
                    gate.notifyAll();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (gate) {
                if (!stopping) throw new AssertionError("M7 supervisor interrupted", e);
            }
        }
    }
    private void carry() {
        try {
            while (true) {
                Actor actor;
                Frame frame;
                synchronized (gate) {
                    for (;;) {
                        if (stopping && ready.isEmpty() && controls.isEmpty()) return;
                        // Supervisor CONTROL must finish before any new DATA resume/start.
                        if (!controls.isEmpty() || ready.isEmpty()) {
                            gate.wait(); continue;
                        }
                        actor = ready.removeFirst();
                        actor.scheduled = false;
                        if (actor.fenced || actor.retired || actor.running) continue;
                        if (actor.active == null) {
                            if (actor.inbox.isEmpty()) continue;
                            long mail = actor.inbox.removeFirst();
                            try { actor.active = actor.factory.new_frame(mail); }
                            catch (Throwable e) {
                                // Failed factory consumed the accepted mail, not a new send.
                                actor.failed++;
                                actor.fenced = true;
                                actor.accepting = false;
                                actor.abandoned += actor.inbox.size();
                                actor.inbox.clear();
                                control(actor, ControlKind.PANIC);
                                continue;
                            }
                            if (actor.active == null) {
                                actor.failed++;
                                actor.fenced = true; actor.accepting = false;
                                actor.abandoned += actor.inbox.size(); actor.inbox.clear();
                                control(actor, ControlKind.PANIC);
                                continue;
                            }
                        }
                        frame = actor.active;
                        actor.running = true;
                        actor.concurrent++;
                        actor.maxConcurrent = Math.max(actor.maxConcurrent, actor.concurrent);
                        actor.carriers.add(Thread.currentThread().threadId());
                        break;
                    }
                }
                var slice = new Slice(reductionBudget, nanoBudget);
                Step result = null;
                Throwable failure = null;
                try { result = frame.advance(slice); }
                catch (Throwable e) { failure = e; }
                synchronized (gate) {
                    actor.running = false;
                    actor.concurrent--;
                    actor.slices++;
                    if (failure == null && (result == null ||
                        (result == Step.YIELD && slice.charged_reductions() == 0))) {
                        failure = new IllegalStateException("invalid safe-point frame outcome");
                    }
                    if (failure != null) {
                        actor.failed++;
                        actor.active = null;
                        actor.accepting = false;
                        actor.fenced = true;
                        actor.abandoned += actor.inbox.size();
                        actor.inbox.clear();
                        control(actor, ControlKind.PANIC);
                    } else if (result == Step.YIELD) {
                        // The same accepted turn remains in flight and carries its PC.
                        actor.yielded++;
                    } else {
                        try {
                            actor.resultSum = Math.addExact(actor.resultSum, frame.result());
                            actor.completed++;
                        } catch (Throwable e) {
                            actor.failed++;
                            actor.accepting = false;
                            actor.fenced = true;
                            actor.abandoned += actor.inbox.size();
                            actor.inbox.clear();
                            control(actor, ControlKind.PANIC);
                        }
                        actor.active = null;
                    }
                    if (!actor.fenced) schedule(actor);
                    retire_if_quiescent(actor);
                    gate.notifyAll();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (gate) {
                if (!stopping) throw new AssertionError("M7 carrier interrupted", e);
            }
        }
    }
    public Snapshot snapshot(ActorRef ref) {
        synchronized (gate) {
            if (ref == null) throw new IllegalArgumentException("null actor ref");
            Actor actor = actors.get(ref.name());
            if (actor == null || actor.ref != ref)
                throw new IllegalArgumentException("stale actor handle");
            return new Snapshot(actor.accepted, actor.completed, actor.failed,
                actor.abandoned, actor.yielded, actor.slices, actor.resultSum,
                actor.inbox.size(), actor.active != null, actor.running,
                actor.accepting, actor.retired, actor.controlsDelivered,
                actor.lastControl, actor.maxConcurrent, actor.carriers.size(),
                actor.active == null ? -1 : actor.running ? -2 : actor.active.resume_pc());
        }
    }
    public Queues queues() {
        synchronized (gate) {
            int active = 0;
            for (Actor actor : actors.values()) if (actor.running) active++;
            return new Queues(ready.size(), controls.size(), actors.size(), active);
        }
    }
    public static void check_conservation(Snapshot s) {
        if (s.accepted() != s.completed() + s.failed() + s.abandoned() +
            s.queued() + (s.inFlight() ? 1L : 0L))
            throw new AssertionError("M7 accepted DATA conservation failed: " + s);
    }
    public boolean await_terminal(ActorRef ref, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (gate) {
            while (true) {
                if (snapshot(ref).retired()) return true;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(gate, remaining);
            }
        }
    }
    @Override public synchronized void close() throws Exception {
        if (fullyClosed) return;
        if (Thread.currentThread() == supervisor || carriers.contains(Thread.currentThread()))
            throw new IllegalStateException("cannot synchronously close on internal thread");
        List<ActorRef> live = new ArrayList<>();
        synchronized (gate) {
            closing = true;
            for (Actor actor : actors.values()) if (!actor.retired) live.add(actor.ref);
            for (ActorRef ref : live) request_stop(ref);
        }
        for (ActorRef ref : live)
            if (!await_terminal(ref, 30_000))
                throw new IllegalStateException("refusing to reclaim live frame " + ref.name());
        synchronized (gate) { stopping = true; gate.notifyAll(); }
        supervisor.join(10_000);
        for (Thread carrier : carriers) carrier.join(10_000);
        if (supervisor.isAlive() || carriers.stream().anyMatch(Thread::isAlive))
            throw new IllegalStateException("M7 runtime threads not physically quiesced");
        fullyClosed = true;
    }
}
