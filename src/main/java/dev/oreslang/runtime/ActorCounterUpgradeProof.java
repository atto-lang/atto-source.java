package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

/**
 * Trusted-host executable proof of a state-preserving actor-code transaction.
 *
 * This runs messages through a REAL ActorRuntime actor and its stable ActorRef,
 * but the replaceable code is a trusted Java host function, NOT a compiled
 * Oreslang source actor, sandboxed executable or native AOT image.
 *
 * No guest or user actor may acquire this supervisor-only coordinator. The
 * caller must independently attest code and schema digests and ensure the
 * replacement/restorer cannot mutate the old actor state.
 */
final class ActorCounterUpgradeProof {
    private record Version(ActorUpgradeEpochGate.Image image,
                           VersionedActorCell state,
                           LongBinaryOperator apply) { }

    private final ActorRuntime runtime;
    private final ActorRuntime.ActorRef<Long> ref;
    private final ActorUpgradeEpochGate generation;
    private final Object transactionLock = new Object();
    private volatile Version active;

    private ActorCounterUpgradeProof(ActorRuntime runtime,
                                     ActorRuntime.ActorRef<Long> ref,
                                     ActorUpgradeEpochGate.Image image,
                                     long initial,
                                     LongBinaryOperator apply) {
        this.runtime = runtime;
        this.ref = ref;
        this.generation = new ActorUpgradeEpochGate(1, image);
        this.active = new Version(image, new VersionedActorCell(initial), apply);
    }

    /**
     * Uses the explicit trusted-host factory capability. A real source actor
     * must instead use the compiler-owned ActorCodeExecutor boundary.
     */
    static ActorCounterUpgradeProof spawn(ActorRuntime runtime,
                                         ActorUpgradeEpochGate.Image image,
                                         long initial,
                                         LongBinaryOperator apply) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(apply, "apply");
        AtomicReference<ActorCounterUpgradeProof> installed = new AtomicReference<>();
        ActorRuntime.ActorRef<Long> actor = runtime.<Long>spawnSharedTrusted(
                context -> (message, ctx) -> {
                    ActorCounterUpgradeProof owner = installed.get();
                    if (owner == null)
                        throw new IllegalStateException("counter proof not installed before actor startup");
                    owner.deliver(message);
                });
        ActorCounterUpgradeProof proof =
                new ActorCounterUpgradeProof(runtime, actor, image, initial, apply);
        installed.set(proof);
        return proof;
    }

    ActorRuntime.ActorRef<Long> actorRef() { return ref; }
    long epoch() { return generation.epoch(); }
    long value() { return active.state().snapshot().value(); }
    ActorUpgradeEpochGate.Image image() { return generation.image(); }

    private void deliver(long delta) {
        if (!runtime.currentThreadOwnsActorExecutionLease()
                || !ActorRuntime.currentActorId().orElseThrow().equals(ref.id()))
            throw new SecurityException("counter proof invoked outside actor execution domain");
        // Real messages acquire a generation lease while mutating owned state.
        // A conflicting/superseded snapshot is NOT silently retried.
        try (ActorUpgradeEpochGate.Lease pin = generation.pin()) {
            Version version = active;
            VersionedActorCell.Snapshot previous = version.state().snapshot();
            long updated = version.apply().applyAsLong(previous.value(), delta);
            if (version.state().tryCommit(previous, updated)
                    != VersionedActorCell.Commit.COMMITTED)
                throw new IllegalStateException("actor counter commit lost generation ownership");
        }
    }

    /**
     * Attempts one bounded, nonblocking upgrade. If the old actor is still
     * executing, returns false without changing its code or state.
     *
     * The restorer consumes only a verified scalar checkpoint copy. It must
     * return a fresh value without touching the existing actor or its broker.
     * Failures before publish abort the candidate and restore old ingress.
     */
    boolean upgrade(ActorUpgradeEpochGate.Image proposed,
                    LongBinaryOperator replacement,
                    LongUnaryOperator restore) {
        Objects.requireNonNull(proposed, "proposed");
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(restore, "restore");

        synchronized (transactionLock) {
            try (ActorRuntime.HotUpgradeIngressFreeze frozen =
                         runtime.freezeUserIngressForHotUpgrade(ref)) {
                if (!frozen.schedulerDrained()) return false;

                ActorUpgradeEpochGate.Candidate candidate = null;
                boolean committed = false;
                try {
                    candidate = generation.prepare(proposed);
                    Version previous = active;
                    VersionedActorCell.Snapshot current = previous.state().snapshot();
                    byte[] bytes = ByteBuffer.allocate(Long.BYTES)
                            .order(ByteOrder.BIG_ENDIAN)
                            .putLong(current.value()).array();
                    ActorCheckpointEnvelopeV1.Header header =
                            new ActorCheckpointEnvelopeV1.Header(
                                    ref.id().value(), generation.epoch(),
                                    previous.image().codeSha256(),
                                    previous.image().stateSchemaSha256(),
                                    previous.image().messageAbiSha256(),
                                    previous.image().capabilitySha256());
                    byte[] checkpoint = ActorCheckpointEnvelopeV1.encode(
                            new ActorCheckpointEnvelopeV1.Snapshot(header, bytes));
                    byte[] restored = ActorCheckpointEnvelopeV1.decode(
                            checkpoint, header).stateBytes();
                    if (restored.length != Long.BYTES)
                        throw new IllegalStateException("counter actor checkpoint must contain one int64");
                    long candidateValue = restore.applyAsLong(
                            ByteBuffer.wrap(restored).order(ByteOrder.BIG_ENDIAN).getLong());
                    // V1 explicitly excludes schema migrations. Even a trusted
                    // restorer must reproduce the exact counter value, not
                    // silently reinterpret or rewrite checkpointed state.
                    if (candidateValue != current.value())
                        throw new SecurityException(
                                "same-schema counter restore changed persistent actor state");
                    Version next = new Version(proposed,
                            new VersionedActorCell(candidateValue), replacement);

                    // No actor message can enter while frozen. Publication and
                    // the host-code swap are adjacent operations under this
                    // supervisor mutex; neither callback nor I/O runs here.
                    if (!frozen.schedulerDrained() || !generation.publish(candidate))
                        return false;
                    committed = true;
                    active = next;
                    // Retiring old state is safe for this narrow host scalar
                    // proof only because no outstanding generation pins exist.
                    previous.state().retire();
                    return true;
                } finally {
                    if (!committed && candidate != null && generation.frozen())
                        generation.abort(candidate);
                }
            }
        }
    }
}
