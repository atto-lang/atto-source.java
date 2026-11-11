package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Host-only bridge from an admitted actor envelope/activation to protected
 * float state. Guest code cannot mint turn proofs, choose a handler identity,
 * park a carrier, or get a reference to the backing scalar array.
 *
 * WARNING: This is not a compiled .atto handle_state implementation and
 * does not yet attach its lifetime to ActorCell STOP/finalization. Production
 * compiler emission stays fail-closed until those lifecycle proofs exist.
 */
final class NeuronActorScalarTurnStateV0 implements AutoCloseable {
    private final ActorRuntime runtime;
    private final ActorRuntime.ActorRef<?> actor;
    private final long generation;
    private final ActorStateReadWriteLoans storage;

    private NeuronActorScalarTurnStateV0(ActorRuntime.NeuronActorTurnProof proof,
                                        ActorStateReadWriteLoans storage) {
        this.runtime = proof.runtime();
        this.actor = proof.actorRef();
        this.generation = storage.generation();
        this.storage = storage;
    }

    /** May be called only during one trusted actor's admitted initialization. */
    static NeuronActorScalarTurnStateV0 createOnCurrentTurn(
            int maxPending, int maxReaders, List<String> floatFields) {
        ActorRuntime.NeuronActorTurnProof proof =
                ActorRuntime.mintNeuronActorTurnProof();
        proof.checkedHandlerIdentity();
        // The trusted ActorRef mints the generation; a caller-provided number
        // could accidentally bind a recycled actor to the wrong state epoch.
        long generation = proof.actorRef().generation();
        ActorStateReadWriteLoans storage =
                new ActorStateReadWriteLoans(generation, maxPending, maxReaders, floatFields);
        return new NeuronActorScalarTurnStateV0(proof, storage);
    }

    private ActorRuntime.NeuronActorTurnProof currentOwnerTurn() {
        ActorRuntime.NeuronActorTurnProof proof =
                ActorRuntime.mintNeuronActorTurnProof();
        if (proof.runtime() != runtime || proof.actorRef() != actor) {
            throw new SecurityException("foreign actor cannot access neuron state");
        }
        proof.checkedHandlerIdentity();
        return proof;
    }

    /**
     * Nonparking, FIFO-fair fast path. null means the state is currently
     * contended. Callers must yield/retry through the actor scheduler;
     * spinning or joining an unresolved Future on a carrier is forbidden.
     */
    ReadScope tryRead() {
        ActorRuntime.NeuronActorTurnProof proof = currentOwnerTurn();
        var loan = storage.tryAcquireReadNow(proof.checkedHandlerIdentity(), generation);
        return loan == null ? null : new ReadScope(proof, loan);
    }

    WriteScope tryWrite() {
        ActorRuntime.NeuronActorTurnProof proof = currentOwnerTurn();
        var loan = storage.tryAcquireWriteNow(proof.checkedHandlerIdentity(), generation);
        return loan == null ? null : new WriteScope(proof, loan);
    }

    class ReadScope implements AutoCloseable {
        private final ActorRuntime.NeuronActorTurnProof proof;
        private final ActorStateReadWriteLoans.Loan loan;
        private boolean released;

        private ReadScope(ActorRuntime.NeuronActorTurnProof proof,
                          ActorStateReadWriteLoans.Loan loan) {
            this.proof = proof;
            this.loan = loan;
        }

        final Object requireCurrentOwner() {
            if (released) throw new IllegalStateException("neuron state scope already closed");
            // Revalidate at EVERY access. A stored Scope from an earlier
            // envelope must never authorize another message, carrier or actor.
            if (proof.runtime() != runtime || proof.actorRef() != actor)
                throw new SecurityException("foreign neuron state scope");
            return proof.checkedHandlerIdentity();
        }

        double readFloat(String field) {
            return storage.readFloat(loan, requireCurrentOwner(), generation,
                    Objects.requireNonNull(field, "field"));
        }

        @Override public void close() {
            if (released) return;
            released = true;
            // Cleanup may execute during actor STOP after the original turn
            // has exited; do not require an active turn to release a loan.
            loan.close();
        }
    }

    final class WriteScope extends ReadScope {
        private final ActorStateReadWriteLoans.WriteLoan writer;
        private WriteScope(ActorRuntime.NeuronActorTurnProof proof,
                           ActorStateReadWriteLoans.WriteLoan writer) {
            super(proof, writer);
            this.writer = writer;
        }

        void writeFloat(String field, double value) {
            storage.writeFloat(writer, requireCurrentOwner(), generation,
                    Objects.requireNonNull(field, "field"), value);
        }
    }

    /** Must be called by the actor lifecycle after STOP admission is fenced. */
    @Override public void close() { storage.close(); }

    /**
     * Scalar zeroization is allowed only after this bridge has retired and
     * every logical loan released. Physical actor turn/FFI quiescence remains
     * the ActorRuntime's separate responsibility.
     */
    boolean tryClearAfterQuiescence() {
        return storage.tryClearRetiredScalarStorage();
    }

    long generation() { return generation; }
    int activeLoanCount() { return storage.activeCount(); }
}
