package dev.oreslang.runtime;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A single ActorRuntime-supervisor-owned admission boundary for one durable
 * partition. Not a guest object, not a Kafka offset allocator, and not a
 * substitute for actual CONTROL-lane supervisor lowering.
 *
 * The host-supplied store MUST atomically compare, append, and fsync the next
 * generation before returning it. The runtime does not accept an untrusted
 * guest number or string as a generation. There is no implicit fallback to
 * unsupervised allocation or automatic migration.
 *
 * Calls that may reserve or publish require a trusted blocking host context.
 * A completion only obtains commit authority while the monitor still fences
 * takeover. The callback MUST only enqueue an actor-owned commit; the actor
 * must recheck its activation and must not block or do guest work in it.
 */
public final class SupervisorStorageEpochAuthority implements AutoCloseable {
    /** Port to the trusted, exclusive-writer durable generation ledger. */
    public interface DurableGenerationStore {
        long currentGeneration();
        long reserveAndSyncNext(long expectedPrevious, String runtimeIssuedToken);
    }

    public static final class Activation {
        private final SupervisorStorageEpochAuthority gate;
        private final long generation;
        private final String token;
        private Activation(SupervisorStorageEpochAuthority gate, long generation, String token) {
            this.gate = gate;
            this.generation = generation;
            this.token = token;
        }
        public long generation() { return generation; }
        /** Host/CONTROL adapter only: never expose this bearer to guest code. */
        public String authority() {
            gate.requireHost();
            return token + ":" + generation;
        }
    }

    /**
     * A physical I/O loan survives activation revocation. Close in finally,
     * including on timeout, cancellation or disk failure. The queue ticket
     * additionally requires a fresh check at actual callback execution.
     */
    public static final class PendingIo implements AutoCloseable {
        private final SupervisorStorageEpochAuthority gate;
        private final Activation activation;
        private boolean enqueued;
        private boolean dispatched;
        private boolean physicalDone;
        private PendingIo(SupervisorStorageEpochAuthority gate, Activation activation) {
            this.gate = gate;
            this.activation = activation;
        }
        @Override public void close() { gate.releasePhysicalIo(this); }
    }

    /** Takeover revokes the old ticket but cannot activate until I/O drains. */
    /** Physical ActorCell turn lease; only runtime's actual actor dispatcher can mint. */
    static final class ActorTurnLease implements AutoCloseable {
        private final SupervisorStorageEpochAuthority gate;
        private boolean released;
        private ActorTurnLease(SupervisorStorageEpochAuthority gate) { this.gate = gate; }
        @Override public void close() {
            synchronized (gate.lock) {
                if (released) return;
                released = true;
                if (--gate.actorTurnsInFlight < 0)
                    throw new IllegalStateException("actor turn lease underflow");
                gate.lock.notifyAll();
            }
        }
    }

    public static final class QuiescencePendingException extends IllegalStateException {
        public QuiescencePendingException(int physicalIo, int executingCommits) {
            super("supervisor takeover waiting for physical I/O=" + physicalIo
                    + ", executing commits=" + executingCommits);
        }
    }

    private final Object lock = new Object();
    private final ActorRuntime runtime;
    private final ActorRuntime.ActorSupervisor supervisor;
    private final String partition;
    private final DurableGenerationStore store;
    private final SecureRandom random = new SecureRandom();
    private long generation;
    private Activation active;
    private boolean closed;
    private boolean poisoned;
    // Guarded by lock; these are physical operation receipts, NOT guest state.
    private int physicalIoInFlight;
    private int executingCommits;
    private int actorTurnsInFlight;

    SupervisorStorageEpochAuthority(ActorRuntime runtime,
            ActorRuntime.ActorSupervisor supervisor, String partition,
            DurableGenerationStore store) {
        this.runtime = Objects.requireNonNull(runtime);
        this.supervisor = Objects.requireNonNull(supervisor);
        this.partition = Objects.requireNonNull(partition);
        this.store = Objects.requireNonNull(store);
        requireHost();
        long persisted = store.currentGeneration();
        if (persisted < 0 || persisted == Long.MAX_VALUE) {
            throw new IllegalStateException("durable generation invalid or exhausted");
        }
        generation = persisted;
    }

    public String partition() { return partition; }
    public ActorRuntime.SupervisorId supervisorId() { return supervisor.id(); }

    private void requireHost() {
        runtime.requireStorageEpochHostContext(supervisor);
    }

    /**
     * Linearization order: revoke previous ticket, persist the next epoch,
     * then publish the new unforgeable ticket. A failed or ambiguous fsync
     * leaves this authority poisoned; the previous ticket never revives.
     */
    public Activation activate() {
        requireHost();
        synchronized (lock) {
            if (closed || poisoned)
                throw new IllegalStateException("storage supervisor authority retired/poisoned");
            // Revocation linearizes before quiescence check. A retry after
            // all physical loans drain may safely reserve the next epoch.
            active = null;
            if (physicalIoInFlight != 0 || executingCommits != 0
                    || actorTurnsInFlight != 0)
                throw new QuiescencePendingException(
                        physicalIoInFlight, executingCommits + actorTurnsInFlight);
            long expected = generation;
            if (expected == Long.MAX_VALUE - 1) {
                poisoned = true;
                throw new IllegalStateException("supervisor generation exhausted");
            }
            try {
                // Mint the runtime capability BEFORE storage FENCE, so the
                // durable helper never accepts a token chosen by the bridge.
                byte[] nonce = new byte[16];
                random.nextBytes(nonce);
                String token = HexFormat.of().formatHex(nonce);
                long persisted = store.reserveAndSyncNext(expected, token);
                if (persisted != expected + 1) {
                    throw new SecurityException("durable generation was not exactly incremented");
                }
                generation = persisted;
                active = new Activation(this, persisted, token);
                return active;
            } catch (RuntimeException | Error ambiguous) {
                poisoned = true;
                throw ambiguous;
            }
        }
    }

    /**
     * Reserve a one-shot I/O completion witness while the activation is
     * current. A stale/foreign ticket never acquires one.
     */
    public PendingIo beginIo(Activation ticket) {
        requireHost();
        synchronized (lock) {
            if (!currentLocked(ticket))
                throw new SecurityException("stale or foreign supervisor activation");
            if (physicalIoInFlight == Integer.MAX_VALUE)
                throw new IllegalStateException("supervisor physical I/O receipt limit");
            ++physicalIoInFlight;
            return new PendingIo(this, ticket);
        }
    }

    /** Read-only status; possession of this result is NOT commit authority. */
    public boolean permits(PendingIo io) {
        synchronized (lock) {
            return io != null && io.gate == this && !io.physicalDone
                    && !io.enqueued && currentLocked(io.activation);
        }
    }

    /**
     * Enqueue at most one actor-owned COMMIT under the takeover fence.
     * This callback cannot be retried after an exception: its outcome may
     * already be visible. Never send a Kafka client ACK here.
     */
    public boolean enqueueCommitIfCurrent(PendingIo io, Runnable enqueueOnly) {
        requireHost();
        Objects.requireNonNull(enqueueOnly);
        synchronized (lock) {
            if (io == null || io.gate != this || io.physicalDone
                    || io.enqueued || !currentLocked(io.activation))
                return false;
            io.enqueued = true;
            try {
                enqueueOnly.run();
            } catch (RuntimeException | Error uncertain) {
                active = null;
                poisoned = true;
                throw uncertain;
            }
            return true;
        }
    }

    /**
     * Recheck an already queued COMMIT when the executor actually starts it.
     * A revoked callback never invokes guest code. The execution lease keeps
     * takeover from admitting the next generation until physical dispatch
     * has finished. Do not use for Kafka ACK; the actor still decides ACK.
     */
    public boolean dispatchCommitIfCurrent(PendingIo io, Runnable actorIngressOnly) {
        requireHost();
        Objects.requireNonNull(actorIngressOnly);
        synchronized (lock) {
            if (io == null || io.gate != this || !io.enqueued
                    || io.dispatched || !currentLocked(io.activation))
                return false;
            if (executingCommits == Integer.MAX_VALUE)
                throw new IllegalStateException("supervisor executing dispatch quota");
            io.dispatched = true;
            ++executingCommits;
        }
        try {
            actorIngressOnly.run();
            return true;
        } catch (RuntimeException | Error uncertain) {
            synchronized (lock) {
                active = null;
                poisoned = true;
            }
            throw uncertain;
        } finally {
            synchronized (lock) {
                --executingCommits;
                lock.notifyAll();
            }
        }
    }

    /**
     * Release the physical operation receipt independently of activation
     * revocation or runtime close. Idempotent, never resurrects authority.
     */
    private void releasePhysicalIo(PendingIo io) {
        synchronized (lock) {
            if (io == null || io.gate != this || io.physicalDone) return;
            io.physicalDone = true;
            if (--physicalIoInFlight < 0)
                throw new IllegalStateException("physical I/O receipt underflow");
            lock.notifyAll();
        }
    }

    /**
     * The actual ActorRuntime.ActorCell calls this when it obtains the
     * physical carrier, before init/mailbox/continuation dispatch. The
     * activation is a JVM object identity, never guest-supplied strings.
     */
    ActorTurnLease enterActorTurn(Activation ticket) {
        synchronized (lock) {
            if (!currentLocked(ticket))
                throw new SecurityException("actor turn has stale supervisor activation");
            if (actorTurnsInFlight == Integer.MAX_VALUE)
                throw new IllegalStateException("supervisor physical actor turn quota");
            ++actorTurnsInFlight;
            return new ActorTurnLease(this);
        }
    }

    /** Recheck immediately before every ordinary mail / continuation. */
    void requireCurrentActorTurn(Activation ticket) {
        synchronized (lock) {
            if (!currentLocked(ticket))
                throw new SecurityException("revoked supervisor actor mailbox/continuation");
        }
    }

    /** Opaque activation must belong to this exact gate and still be current. */
    void requireBinding(Activation ticket, ActorRuntime.ActorSupervisor owner) {
        if (owner != supervisor) throw new SecurityException("foreign supervisor actor binding");
        synchronized (lock) {
            if (!currentLocked(ticket))
                throw new SecurityException("stale/foreign actor activation binding");
        }
    }

    /** Covers registered fsyncs, executing commits AND bound physical actor turns. */
    public boolean physicallyQuiescent() {
        synchronized (lock) {
            return physicalIoInFlight == 0 && executingCommits == 0
                    && actorTurnsInFlight == 0;
        }
    }

    /** Explicit STOP/cancellation fences all outstanding completions. */
    public void cancel(Activation ticket) {
        requireHost();
        synchronized (lock) {
            if (!currentLocked(ticket))
                throw new SecurityException("stale or foreign supervisor cancellation");
            active = null;
        }
    }

    private boolean currentLocked(Activation ticket) {
        return !closed && !poisoned && !runtime.storageEpochRuntimeClosed()
                && ticket != null && ticket.gate == this
                && active == ticket && ticket.generation == generation;
    }

    /** Called by ActorRuntime at physical runtime teardown, not from guest. */
    void retireFromRuntime() {
        synchronized (lock) { active = null; closed = true; }
    }

    @Override public void close() {
        requireHost();
        retireFromRuntime();
    }
}
