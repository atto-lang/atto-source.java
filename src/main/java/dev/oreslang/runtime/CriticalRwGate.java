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
 * <p>Each gate uses its own short metadata monitor, plus a small global
 * identity registry for fail-closed nested logical-owner admission.
 * Neither monitor waits for permits or runs guest code. Waiters suspend
 * on OresFuture and resume through OresScheduler.</p>
 */
public final class CriticalRwGate implements AutoCloseable {
    public static final int MAX_WAITERS = 8192;

    public enum Mode { READ, WRITE }

    /**
     * Runtime-owned logical execution whose terminal notification is fenced to
     * the complete guest-turn unwind. A foreign close may request cancellation,
     * but it must not free a granted permit while that guest code is live.
     * Detached host tickets deliberately do not implement this protocol.
     */
    interface FencedLogicalOwner {
        void cancelAndReleaseAfterUnwind(Runnable release);
    }

    private enum State { NEW, QUEUED, GRANTED, CLOSED }

    // Domain registration is brief and cross-gate. Queue state is protected
    // by a distinct gate-local monitor: an unrelated tenant's gate must not
    // contend on one process-global admission/queue monitor.
    private static final Object DOMAIN_COORDINATOR = new Object();
    private static final java.util.concurrent.atomic.AtomicInteger WAITING_COUNT =
            new java.util.concurrent.atomic.AtomicInteger();
    private final Object coordinator = new Object();
    public static final int MAX_ACTIVE_READERS = MAX_WAITERS;

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
        private boolean completionAttached; // guarded by COORDINATOR
        private boolean externalCloseRequested; // guarded by COORDINATOR
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
            Object caller = OresScheduler.currentTaskDomain();
            if (caller == null) caller = ActorRuntime.currentActorExecutionDomain();
            if (caller == null) caller = this; // independent detached host async request
            return request(caller);
        }

        /**
         * Synchronous critical entry: only host embedders may wait on an OS
         * thread. A contended actor/scheduler call fails without parking.
         * Host recursion uses thread identity to reject same-domain nesting.
         */
        public void enterSync() {
            Object caller = OresScheduler.currentTaskDomain();
            if (caller == null) caller = ActorRuntime.currentActorExecutionDomain();
            boolean onCarrier = ActorRuntime.inActorExecution()
                    || OresScheduler.current() != null;
            if (caller == null) caller = Thread.currentThread();
            OresFuture<Void> ready = request(caller);
            if (onCarrier) {
                if (!isHeld()) {
                    close();
                    throw new IllegalStateException(
                            "contended synchronous critical admission would park an actor/scheduler carrier");
                }
                return;
            }
            try {
                ready.join();
                if (!isHeld()) {
                    throw new CancellationException("critical permit revoked before host entry");
                }
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private OresFuture<Void> request(Object caller) {
            boolean grantNow = false;
            synchronized (coordinator) {
                if (state != State.NEW) {
                    throw new IllegalStateException("RW ticket already requested or closed");
                }
                if (closed) throw new IllegalStateException("RW gate is closed");
                boolean canEnter = waiters.isEmpty() && writer == null
                        && (mode == Mode.READ
                            ? readers.size() < MAX_ACTIVE_READERS : readers.isEmpty());
                if (!canEnter && waiters.size() >= MAX_WAITERS) {
                    throw new IllegalStateException("RW gate wait queue is full");
                }
                // Strict lock order: gate-local coordinator before the small
                // process-wide domain registry. Never acquire them in reverse.
                synchronized (DOMAIN_COORDINATOR) {
                    Ticket prior = DOMAINS.get(caller);
                    if (prior != null) {
                        if (prior.mode == Mode.READ && mode == Mode.WRITE) {
                            throw new IllegalStateException(
                                    "read-to-write critical upgrade is forbidden");
                        }
                        throw new IllegalStateException(
                                "recursive logical nested critical RW acquisition requires verified reentrancy/lock ordering");
                    }
                    DOMAINS.put(caller, this);
                }
                domain = caller;
                if (canEnter) {
                    grantLocked(this);
                    grantNow = true;
                } else {
                    state = State.QUEUED;
                    waiters.addLast(this);
                    WAITING_COUNT.incrementAndGet();
                }
            }
            if (grantNow) admitted.completeFromRuntime(null);
            return admitted;
        }

        /**
         * Observe admission only after requesting it. An unrequested ticket
         * cannot be used as an await point: that would suspend forever.
         */
        public OresFuture<Void> admission() {
            synchronized (coordinator) {
                if (state == State.NEW) {
                    throw new IllegalStateException("RW admission was not requested");
                }
                return admitted;
            }
        }

        /**
         * Bind exactly one owning task completion to the ticket. The callback
         * revokes either a pending admission or an active grant on cancellation,
         * return or failure. Do not bind unrelated task lifetimes.
         */
        public void releaseOnCompletion(OresFuture<?> completion) {
            Objects.requireNonNull(completion, "completion");
            synchronized (coordinator) {
                if (state == State.CLOSED) {
                    throw new IllegalStateException("cannot bind a released RW ticket");
                }
                if (completionAttached) {
                    throw new IllegalStateException("RW ticket completion already bound");
                }
                completionAttached = true;
            }
            // Completion may already be settled; never execute callbacks under
            // the metadata coordinator or the guest's critical region.
            completion.whenCompleteRuntime((value, error) -> close());
        }

        public boolean isHeld() {
            synchronized (coordinator) {
                return state == State.GRANTED && !closed;
            }
        }

        @Override
        public void close() {
            FencedLogicalOwner owner = null;
            synchronized (coordinator) {
                if (state == State.CLOSED || externalCloseRequested) return;
                // A compiler-owned logical task or actor may hold this permit
                // while guest instructions are executing on another carrier.
                // In that case, close is a cancellation *request*, not release.
                // The owning runtime must publish its completion only after
                // the guest turn and its context boundary have unwound.
                if (domain instanceof FencedLogicalOwner fenced
                        && domain != OresScheduler.currentTaskDomain()
                        && domain != ActorRuntime.currentActorExecutionDomain()) {
                    externalCloseRequested = true;
                    owner = fenced;
                }
            }
            if (owner != null) {
                owner.cancelAndReleaseAfterUnwind(this::finishClose);
                return;
            }
            finishClose();
        }

        private void finishClose() {
            List<Ticket> wake = List.of();
            synchronized (coordinator) {
                if (state == State.CLOSED) return;
                State former = state;
                state = State.CLOSED;
                if (domain != null) {
                    synchronized (DOMAIN_COORDINATOR) {
                        DOMAINS.remove(domain, this);
                    }
                }
                if (former == State.QUEUED) {
                    if (waiters.remove(this)) WAITING_COUNT.decrementAndGet();
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

    /**
     * Legacy source `critical` is exclusively WRITE. Callers migrating from
     * CriticalGate must use this ticket on the SAME object/static/file gate
     * as the READ and WRITE modes; independent gates do not synchronize.
     */
    public Ticket newCriticalTicket() {
        return newTicket(Mode.WRITE);
    }

    public Ticket newTicket(Mode mode) {
        Objects.requireNonNull(mode, "mode");
        synchronized (coordinator) {
            if (closed) throw new IllegalStateException("RW gate is closed");
        }
        return new Ticket(mode);
    }

    /** Must be called under the gate-local coordinator with no active writer. */
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
            WAITING_COUNT.decrementAndGet();
            grantLocked(next);
            return List.of(next);
        }
        List<Ticket> wake = new ArrayList<>();
        while (!waiters.isEmpty() && waiters.peekFirst().mode == Mode.READ
                && readers.size() < MAX_ACTIVE_READERS) {
            Ticket next = waiters.removeFirst();
            WAITING_COUNT.decrementAndGet();
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
        synchronized (coordinator) { return waiters.size(); }
    }

    public static int waitingLogicalDomains() {
        return WAITING_COUNT.get();
    }

    public int activeReaderCount() {
        synchronized (coordinator) { return readers.size(); }
    }

    public boolean hasWriter() {
        synchronized (coordinator) { return writer != null; }
    }

    /** Revoke every outstanding grant, pending waiter and future resume. */
    @Override
    public void close() {
        List<Ticket> revoke = new ArrayList<>();
        synchronized (coordinator) {
            if (closed) return;
            closed = true;
            revoke.addAll(readers.keySet());
            if (writer != null) revoke.add(writer);
            revoke.addAll(waiters);
            readers.clear();
            writer = null;
            WAITING_COUNT.addAndGet(-waiters.size());
            waiters.clear();
            synchronized (DOMAIN_COORDINATOR) {
                for (Ticket ticket : revoke) {
                    ticket.state = State.CLOSED;
                    if (ticket.domain != null) DOMAINS.remove(ticket.domain, ticket);
                }
            }
        }
        for (Ticket ticket : revoke) {
            ticket.admitted.failFromRuntime(
                    new CancellationException("RW gate closed during admission"));
        }
    }
}
