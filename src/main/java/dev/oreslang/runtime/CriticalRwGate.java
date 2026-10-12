package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Runtime-only, non-thread-affine reader/writer admission gate.
 *
 * <p>This is an admission substrate, NOT public source-language lowering.
 * The compiler must still prove protected-state effects and bind every
 * generated critical callable to the same owning gate. In particular a
 * successful Future is not permission to execute after STOP/cancellation:
 * the admitted continuation must check its ticket is still held.</p>
 *
 * <p>Coordination uses a short metadata monitor shared with other RW gates,
 * but NEVER waits for a permit or runs a guest turn under that monitor.
 * Waiters suspend on OresFuture and resume through OresScheduler.</p>
 */
public final class CriticalRwGate implements AutoCloseable {
    public static final int MAX_WAITERS = 8192;

    public enum Mode { READ, WRITE }

    private enum State { NEW, QUEUED, GRANTED, CLOSED }

    private static final Object COORDINATOR = new Object();

    // A domain may request one RW ticket at a time. Until generalized
    // ordering/reentrancy is proven, nested critical acquisitions fail closed.
    // Unlike thread identities these tokens survive scheduler await/resume.
    private static final IdentityHashMap<Object, Ticket> DOMAINS =
            new IdentityHashMap<>();

    private final ArrayDeque<Ticket> waiters = new ArrayDeque<>();
    private final IdentityHashMap<Ticket, Boolean> readers = new IdentityHashMap<>();
    private Ticket writer;
    private boolean closed;

    public final class Ticket implements AutoCloseable {
        private final Mode mode;
        private final OresFuture<Void> admitted = new OresFuture<>(this::close);
        private State state = State.NEW; // guarded by COORDINATOR
        private Object domain;

        private Ticket(Mode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
        }

        public Mode mode() {
            return mode;
        }

        /**
         * Request a logical permit without blocking any physical carrier.
         * The resulting Future must be awaited by a compiler-lowered task.
         */
        public OresFuture<Void> enterAsync() {
            boolean grantNow = false;
            Object caller = OresScheduler.currentTaskDomain();
            if (caller == null) caller = ActorRuntime.currentActorExecutionDomain();
            if (caller == null) caller = this; // independent detached host async request
            synchronized (COORDINATOR) {
                if (state != State.NEW) {
                    throw new IllegalStateException("RW ticket already requested or closed");
                }
                if (closed) throw new IllegalStateException("RW gate is closed");
                Ticket prior = DOMAINS.get(caller);
                if (prior != null) {
                    if (prior.mode == Mode.READ && mode == Mode.WRITE) {
                        throw new IllegalStateException(
                                "read-to-write critical upgrade is forbidden");
                    }
                    throw new IllegalStateException(
                            "nested critical RW acquisition requires verified reentrancy/lock ordering");
                }
                boolean canEnter = waiters.isEmpty() && writer == null
                        && (mode == Mode.READ || readers.isEmpty());
                if (!canEnter && waiters.size() >= MAX_WAITERS) {
                    throw new IllegalStateException("RW gate wait queue is full");
                }
                domain = caller;
                DOMAINS.put(caller, this);
                if (canEnter) {
                    grantLocked(this);
                    grantNow = true;
                } else {
                    state = State.QUEUED;
                    waiters.addLast(this);
                }
            }
            if (grantNow) admitted.completeFromRuntime(null);
            return admitted;
        }

        public OresFuture<Void> admission() {
            return admitted;
        }

        /** Attach task completion to guarantee release on all settled outcomes. */
        public void releaseOnCompletion(OresFuture<?> completion) {
            Objects.requireNonNull(completion, "completion")
                    .whenCompleteRuntime((value, error) -> close());
        }

        public boolean isHeld() {
            synchronized (COORDINATOR) {
                return state == State.GRANTED && !closed;
            }
        }

        @Override
        public void close() {
            List<Ticket> wake = List.of();
            synchronized (COORDINATOR) {
                if (state == State.CLOSED) return;
                State former = state;
                state = State.CLOSED;
                if (domain != null) DOMAINS.remove(domain, this);
                if (former == State.QUEUED) {
                    waiters.remove(this);
                    // A cancelled queued writer may have been the only
                    // barrier keeping later readers behind active readers.
                    // Re-evaluate the new FIFO head immediately, without
                    // waiting for those unrelated readers to exit.
                    wake = drainLocked();
                } else if (former == State.GRANTED) {
                    if (mode == Mode.READ) {
                        readers.remove(this);
                    } else if (writer == this) {
                        writer = null;
                    }
                    wake = drainLocked();
                }
            }
            // Never run Future callbacks inside the coordinator monitor.
            admitted.failFromRuntime(new CancellationException("RW ticket released"));
            publish(wake);
        }
    }

    public Ticket newTicket(Mode mode) {
        Objects.requireNonNull(mode, "mode");
        synchronized (COORDINATOR) {
            if (closed) throw new IllegalStateException("RW gate is closed");
        }
        return new Ticket(mode);
    }

    /** Must be called under COORDINATOR with no active writer. */
    private void grantLocked(Ticket ticket) {
        ticket.state = State.GRANTED;
        if (ticket.mode == Mode.READ) {
            readers.put(ticket, Boolean.TRUE);
        } else {
            if (writer != null || !readers.isEmpty()) {
                throw new IllegalStateException("RW writer overlap invariant");
            }
            writer = ticket;
        }
    }

    /**
     * Strict FIFO cohort admission. A writer at queue head is admitted only
     * once all prior readers exit; readers behind a writer cannot overtake it.
     */
    private List<Ticket> drainLocked() {
        if (closed || writer != null || waiters.isEmpty()) return List.of();
        if (waiters.peekFirst().mode == Mode.WRITE) {
            if (!readers.isEmpty()) return List.of();
            Ticket next = waiters.removeFirst();
            grantLocked(next);
            return List.of(next);
        }
        List<Ticket> wake = new ArrayList<>();
        while (!waiters.isEmpty() && waiters.peekFirst().mode == Mode.READ) {
            Ticket next = waiters.removeFirst();
            grantLocked(next);
            wake.add(next);
        }
        return wake;
    }

    private static void publish(List<Ticket> wake) {
        for (Ticket ticket : wake) {
            // close/cancel may have invalidated a grant before notification.
            if (ticket.isHeld()) ticket.admitted.completeFromRuntime(null);
        }
    }

    public int queuedCount() {
        synchronized (COORDINATOR) { return waiters.size(); }
    }

    public int activeReaderCount() {
        synchronized (COORDINATOR) { return readers.size(); }
    }

    public boolean hasWriter() {
        synchronized (COORDINATOR) { return writer != null; }
    }

    /** Revoke every outstanding grant, pending waiter and future resume. */
    @Override
    public void close() {
        List<Ticket> revoke = new ArrayList<>();
        synchronized (COORDINATOR) {
            if (closed) return;
            closed = true;
            revoke.addAll(readers.keySet());
            if (writer != null) revoke.add(writer);
            revoke.addAll(waiters);
            readers.clear();
            writer = null;
            waiters.clear();
            for (Ticket ticket : revoke) {
                ticket.state = State.CLOSED;
                if (ticket.domain != null) DOMAINS.remove(ticket.domain, ticket);
            }
        }
        for (Ticket ticket : revoke) {
            ticket.admitted.failFromRuntime(
                    new CancellationException("RW gate closed during admission"));
        }
    }
}
