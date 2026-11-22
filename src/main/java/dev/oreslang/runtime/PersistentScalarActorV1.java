package dev.oreslang.runtime;

import dev.oreslang.compiler.PersistentScalarMirV1;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Host-activated executor for the checked PersistentScalarMirV1 subset.
 *
 * One instance owns one actor-private scalar region and one storage epoch.
 * All calls run under a single actor turn monitor. State is mutated in the
 * actor-private region only AFTER the SQLite/provider commit is confirmed.
 * An ambiguous commit failure poisons the activation; a new activation must
 * load authoritative storage instead of assuming rollback succeeded.
 *
 * Runtime wiring into the general source ActorRuntime is intentionally still
 * gated. Guest actors are not allowed to create this trusted executor/store.
 */
public final class PersistentScalarActorV1 implements AutoCloseable {
    private final PersistentScalarMirV1.Plan plan;
    private final DurableActorStore storage;
    private final DurableActorStore.Address address;
    private final ScalarStateContainer state;
    private final long epoch;
    private long stateVersion;
    private boolean closed;

    private PersistentScalarActorV1(PersistentScalarMirV1.Plan plan,
                                    DurableActorStore storage,
                                    DurableActorStore.Address address,
                                    DurableActorStore.Activation activated) {
        this.plan = plan;
        this.storage = storage;
        this.address = address;
        this.epoch = activated.epoch();
        if (!activated.pendingOutbox().isEmpty()) {
            // The scalar profile cannot dispatch general effects. Do not drop
            // pending intents or pretend that their effects are complete.
            throw new IllegalStateException("PERSISTENT_SCALAR_OUTBOX_UNSUPPORTED");
        }
        long initial = plan.decode(activated.snapshot());
        this.stateVersion = activated.snapshot().stateVersion();
        if (stateVersion == Long.MAX_VALUE)
            throw new IllegalStateException("persistent state version exhausted");
        this.state = new ScalarStateContainer(initial, 4);
        try {
            // Recovered inbound deliveries were durably accepted before the
            // last crash. Replay them in inbox sequence BEFORE publishing ready.
            for (DurableActorStore.Inbound inbound : activated.inbox())
                process_committed_delivery(inbound.deliveryId(),
                        plan.decode_increment_message(inbound.payload()));
        } catch (RuntimeException | Error error) {
            closed = true;
            state.close();
            throw error;
        }
    }

    /**
     * Supervisor-only. The host first provisions the key with plan.initial_snapshot()
     * if absent; activation then fences every previous incarnation atomically.
     */
    public static PersistentScalarActorV1 open(PersistentScalarMirV1.Plan plan,
                                                DurableActorStore store,
                                                DurableActorStore.Address address) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(address, "address");
        DurableActorStore.Activation activation = store.activate(address);
        return new PersistentScalarActorV1(plan, store, address, activation);
    }

    /** Durable send+turn. Empty means the exact duplicate is still pending. */
    public synchronized OptionalLong increment(String deliveryId, long delta) {
        requireActive();
        if (deliveryId == null || deliveryId.isBlank())
            throw new IllegalArgumentException("delivery ID is required");
        try {
            // This is an actor-origin message, so the epoch check and durable
            // admission are one SQLite transaction. Stale actors may not
            // enqueue commands for the new incarnation to execute.
            var original = new DurableActorStore.Inbound(
                    deliveryId, plan.encode_increment_message(delta));
            if (!storage.enqueueFenced(address, epoch, original)) {
                // A committed duplicate returns its ORIGINAL committed result,
                // never the actor's current state. A still-pending duplicate
                // remains unacknowledged until its turn actually commits.
                var committed = storage.committedDelivery(address, epoch, original);
                return committed.isPresent()
                        ? OptionalLong.of(plan.decode(committed.get()))
                        : OptionalLong.empty();
            }
            return OptionalLong.of(process_committed_delivery(deliveryId, delta));
        } catch (RuntimeException | Error failure) {
            poison();
            throw failure;
        }
    }

    /** Actor-private read: does not expose a pointer or writable state loan. */
    public synchronized long get_count() {
        requireActive();
        try {
            // The checked local scalar cannot be exposed after this epoch was
            // replaced in SQLite, even if its private memory is still alive.
            storage.requireActive(address, epoch, stateVersion);
            return state.tryRead().orElseThrow(
                    () -> new IllegalStateException("persistent actor state unavailable"));
        } catch (RuntimeException | Error failure) {
            poison();
            throw failure;
        }
    }

    public synchronized long state_version() {
        requireActive();
        try {
            storage.requireActive(address, epoch, stateVersion);
            return stateVersion;
        } catch (RuntimeException | Error failure) {
            poison();
            throw failure;
        }
    }

    public long activation_epoch() { return epoch; }

    private long process_committed_delivery(String deliveryId, long delta) {
        requireActive();
        if (stateVersion == Long.MAX_VALUE)
            throw new IllegalStateException("state version exhausted");
        try {
            long before = state.tryRead().orElseThrow(
                    () -> new IllegalStateException("actor state read unavailable"));
            // Checked arithmetic MUST precede durable mutation. Overflow leaves
            // the delivery pending and poisons this activation until recovery.
            long next = Math.addExact(before, delta);
            var turn = new DurableActorStore.Turn(address, epoch, stateVersion,
                    deliveryId, plan.snapshot(stateVersion + 1, next), List.of());
            long committed = storage.commitTurn(turn);
            if (committed != stateVersion + 1)
                throw new IllegalStateException("store returned invalid committed version");
            // ScalarStateContainer is private; the enclosing monitor prevents
            // another actor turn from interleaving between commit and publish.
            if (state.trySet(next).isEmpty())
                throw new IllegalStateException("state region unavailable after commit");
            stateVersion = committed;
            return next; // durable-success acknowledgement only after commit
        } catch (RuntimeException | Error failure) {
            // A transport/driver exception does not prove a failed transaction.
            // Never let this possibly-stale incarnation process another turn.
            poison();
            throw failure;
        }
    }

    private void poison() {
        closed = true;
        state.close();
    }

    private void requireActive() {
        if (closed)
            throw new IllegalStateException("persistent actor activation closed or poisoned");
    }

    /** Requires quiesced actor turns. Does not close the host-owned database. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        state.close();
        if (!state.readyForReclamation())
            throw new IllegalStateException("actor state has outstanding loans");
        storage.hibernate(address, epoch);
    }
}
