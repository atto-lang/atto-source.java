package dev.oreslang.runtime;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A reader/writer API on the ORIGINAL legacy CriticalGate admission engine.
 *
 * <p>Construct a standalone gate for a distinct protection domain, or obtain
 * the cached facade from CriticalGate.rwGate() when old and new critical
 * callables protect the same receiver, class or code unit. All tickets on
 * that domain share FIFO queues, reader cohorts and deadlock detection.</p>
 */
public final class CriticalRwGate implements AutoCloseable {
    public static final int MAX_WAITERS = CriticalGate.MAX_WAITERS;
    public static final int MAX_ACTIVE_READERS = CriticalGate.MAX_ACTIVE_READERS;
    // A facade tracks tickets from construction through retirement, including
    // never-requested tickets. Bound that storage, not only the core wait queue.
    public static final int MAX_TRACKED_TICKETS = MAX_ACTIVE_READERS + MAX_WAITERS;
    public enum Mode { READ, WRITE }

    private static final Object OWNER_LOCK = new Object();
    private static final IdentityHashMap<Object, Ticket> OWNERS =
            new IdentityHashMap<>();

    private final CriticalGate engine;
    private final Set<Ticket> tickets = ConcurrentHashMap.newKeySet();
    private boolean closed;

    public CriticalRwGate() { this(new CriticalGate()); }

    CriticalRwGate(CriticalGate engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public final class Ticket implements AutoCloseable {
        private final Mode mode;
        private final CriticalGate.Ticket underlying;
        private boolean requested;
        private boolean closed;
        private boolean completionAttached;
        private Object ownerDomain;

        private Ticket(Mode mode) {
            this.mode = mode;
            this.underlying = engine.newTicket(mode == Mode.READ
                    ? CriticalGate.Mode.READ : CriticalGate.Mode.WRITE);
            // A core ticket revoked by actor STOP must also retire this
            // facade's cross-gate owner registry and strongly held wrapper.
            // close() is idempotent, so normal wrapper release is safe too.
            this.underlying.onClose(this::close);
        }

        public Mode mode() { return mode; }

        private void registerOwner(Object identity) {
            synchronized (this) {
                if (requested || closed)
                    throw new IllegalStateException("RW ticket already requested or closed");
                synchronized (OWNER_LOCK) {
                    Ticket held = OWNERS.get(identity);
                    if (held != null) {
                        if (held.mode == Mode.READ && mode == Mode.WRITE)
                            throw new IllegalStateException("read-to-write critical upgrade is forbidden");
                        throw new IllegalStateException(
                                "recursive logical nested critical RW admission is not verified");
                    }
                    OWNERS.put(identity, this);
                    ownerDomain = identity;
                }
                requested = true;
            }
        }

        private void unregisterOwner() {
            Object identity;
            synchronized (this) { identity = ownerDomain; ownerDomain = null; }
            if (identity != null) {
                synchronized (OWNER_LOCK) { OWNERS.remove(identity, this); }
            }
        }

        private Object logicalOwner(boolean synchronous) {
            Object identity = OresScheduler.currentTaskDomain();
            if (identity == null) identity = ActorRuntime.currentActorExecutionDomain();
            if (identity == null) identity = synchronous ? Thread.currentThread() : this;
            return identity;
        }

        private void registerOrReclaim(boolean synchronous) {
            try {
                registerOwner(logicalOwner(synchronous));
            } catch (RuntimeException | Error failure) {
                boolean neverRequested;
                synchronized (this) {
                    neverRequested = !requested && !closed;
                }
                // A denied *new* ticket must not remain tracked. However, a
                // duplicate enter on a previously admitted ticket must NOT
                // revoke that original admission.
                if (neverRequested) close();
                throw failure;
            }
        }

        public OresFuture<Void> enterAsync() {
            registerOrReclaim(false);
            try {
                OresFuture<Void> readiness = underlying.enterAsync();
                readiness.whenCompleteRuntime((value, failure) -> {
                    // An admission denial/cancellation is terminal for the
                    // wrapper, not merely its logical owner registration.
                    // Without close(), failed waiters remain strongly held by
                    // the facade's tracking set until whole-domain shutdown.
                    // A successfully granted Future cannot later fail, so
                    // this never revokes a live successful grant.
                    if (failure != null) close();
                });
                return readiness;
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        public void enterSync() {
            registerOrReclaim(true);
            try {
                underlying.enterSync();
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        public OresFuture<Void> admission() {
            synchronized (this) {
                if (!requested && !closed)
                    throw new IllegalStateException("RW admission was not requested");
            }
            return underlying.admission();
        }

        public void releaseOnCompletion(OresFuture<?> completion) {
            Objects.requireNonNull(completion, "completion");
            synchronized (this) {
                if (closed) throw new IllegalStateException("cannot bind a released RW ticket");
                if (completionAttached)
                    throw new IllegalStateException("RW ticket completion already bound");
                completionAttached = true;
            }
            completion.whenCompleteRuntime((value, failure) -> close());
        }

        public boolean isHeld() { return underlying.isHeld(); }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            try { underlying.close(); }
            finally {
                unregisterOwner();
                tickets.remove(this);
            }
        }
    }

    public Ticket newTicket(Mode mode) {
        Objects.requireNonNull(mode, "mode");
        synchronized (this) {
            if (closed) throw new IllegalStateException("RW gate is closed");
            if (tickets.size() >= MAX_TRACKED_TICKETS) {
                throw new IllegalStateException("RW tracked ticket limit reached");
            }
            Ticket ticket = new Ticket(mode);
            tickets.add(ticket);
            return ticket;
        }
    }

    /** An existing unqualified source critical always requests WRITE. */
    public Ticket newCriticalTicket() { return newTicket(Mode.WRITE); }

    /** Package-private diagnostic for denied-ticket lifecycle regressions. */
    int trackedTicketCount() { return tickets.size(); }

    public int queuedCount() { return engine.queuedCount(); }
    public int activeReaderCount() { return engine.activeReaderCount(); }
    public boolean hasWriter() { return engine.hasWriter(); }

    public static int waitingLogicalDomains() {
        return CriticalGate.waitingLogicalDomains();
    }

    /** Called when the owning core gate is retired directly by actor STOP. */
    void onEngineClosed() {
        List<Ticket> revoked;
        synchronized (this) {
            closed = true;
            revoked = List.copyOf(tickets);
        }
        // Never call ticket callbacks under a facade or core monitor.
        // Unregister logical ownership even for already-granted Futures:
        // their successful completion cannot be turned into cancellation.
        for (Ticket ticket : revoked) ticket.close();
    }

    @Override
    public void close() {
        try {
            engine.closeAll();
        } finally {
            // Standalone facades do not have a cached back-reference from
            // their engine, so explicitly drain their owner registry too.
            onEngineClosed();
        }
    }
}
