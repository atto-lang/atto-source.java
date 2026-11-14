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

    public static final class PendingIo {
        private final Activation activation;
        private boolean completed;
        private PendingIo(Activation activation) { this.activation = activation; }
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
            active = null;
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
            return new PendingIo(ticket);
        }
    }

    /** Read-only status; possession of this result is NOT commit authority. */
    public boolean permits(PendingIo io) {
        synchronized (lock) {
            return io != null && !io.completed && currentLocked(io.activation);
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
            if (io == null || io.completed || !currentLocked(io.activation))
                return false;
            io.completed = true;
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
