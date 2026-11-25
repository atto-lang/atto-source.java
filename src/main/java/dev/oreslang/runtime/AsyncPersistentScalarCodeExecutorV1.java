package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.PersistentScalarMirV1;
import dev.oreslang.parser.Parser;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Restricted, verified scalar actor whose SQLite operations never execute on
 * an ActorRuntime carrier. Only the compiler-proved increment/get_count source
 * profile is accepted; no general durable handlers or guest reflection.
 *
 * <p>A privileged host first calls prepare() OUTSIDE actor execution. It
 * activates and replays committed SQLite inbox work on the provider worker.
 * Only the fully prepared immutable activation may then be spawned. A mailbox
 * turn suspends through ActorRuntime's existing serialized continuation lane
 * until the fenced database receipt returns; private state and actor outputs
 * are published exclusively on that actor's own continuation turn.
 */
public final class AsyncPersistentScalarCodeExecutorV1
        implements ActorRuntime.ActorCodeExecutor {
    private static final int MAX_RECOVERY_INBOX = 4096;

    private final SharedCodeImageStore.CodeImage image;
    private final PersistentScalarMirV1.Plan plan;
    private final AsyncSqliteDurableProviderV1 io;
    private final DurableActorStore.Address address;
    private final long epoch;
    private final DurableActorStore.Snapshot prepared;

    private AsyncPersistentScalarCodeExecutorV1(
            SharedCodeImageStore.CodeImage image,
            PersistentScalarMirV1.Plan plan,
            AsyncSqliteDurableProviderV1 io,
            DurableActorStore.Address address,
            long epoch,
            DurableActorStore.Snapshot prepared) {
        this.image = Objects.requireNonNull(image, "code image");
        this.plan = Objects.requireNonNull(plan, "verified plan");
        this.io = Objects.requireNonNull(io, "privileged storage provider");
        this.address = Objects.requireNonNull(address, "logical actor address");
        if (epoch <= 0) throw new IllegalArgumentException("invalid prepared epoch");
        this.epoch = epoch;
        this.prepared = Objects.requireNonNull(prepared, "prepared snapshot");
        plan.decode(prepared);
        if (!plan.equals(PersistentScalarMirV1.lower(image.program())))
            throw new SecurityException("prepared image does not match checked scalar MIR");
    }

    /**
     * Supervisor-only preparation. Does not block the caller or create actor
     * state off-thread. The host must independently provision the logical key
     * using io.bootstrap() before preparing it. The current source build has
     * no guest capability to call this method.
     */
    public static CompletionStage<AsyncPersistentScalarCodeExecutorV1> prepare(
            String source,
            SharedCodeImageStore images,
            AsyncSqliteDurableProviderV1 io,
            DurableActorStore.Address address) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(images, "code image store");
        Objects.requireNonNull(io, "durable provider");
        Objects.requireNonNull(address, "address");
        if (ActorRuntime.isActorCarrierThread())
            throw new SecurityException("durable preparation is host-only, not an actor turn");

        // Go through the string entry point first: it enforces the source
        // byte budget BEFORE parsing, unlike lower(Ast.Program).
        PersistentScalarMirV1.Plan plan = PersistentScalarMirV1.lower(source);
        Ast.Program program = Parser.parse(source);
        String key = "persistent-scalar:" + plan.codeGeneration();
        SharedCodeImageStore.CodeImage image = images.get(key);
        if (image == null) image = images.publish(key, program);
        else if (!plan.equals(PersistentScalarMirV1.lower(image.program())))
            throw new SecurityException("reused source image does not match verified MIR");
        SharedCodeImageStore.CodeImage verifiedImage = image;

        return io.activate(address).thenCompose(activation -> {
            if (!activation.pendingOutbox().isEmpty())
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "PERSISTENT_SCALAR_OUTBOX_UNSUPPORTED"));
            if (activation.inbox().size() > MAX_RECOVERY_INBOX)
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "PERSISTENT_SCALAR_RECOVERY_BUDGET_EXCEEDED"));
            // Reject unknown schema/code generations before any replay writes.
            plan.decode(activation.snapshot());
            if (activation.snapshot().stateVersion() == Long.MAX_VALUE)
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "persistent state version exhausted"));
            CompletionStage<DurableActorStore.Snapshot> replay =
                    CompletableFuture.completedFuture(activation.snapshot());
            // A finite chain, not an unbounded recursive Java callback stack.
            for (DurableActorStore.Inbound inbound : activation.inbox()) {
                replay = replay.thenCompose(previous -> {
                    long before = plan.decode(previous);
                    long delta = plan.decode_increment_message(inbound.payload());
                    long next = Math.addExact(before, delta);
                    if (previous.stateVersion() == Long.MAX_VALUE)
                        throw new IllegalStateException("persistent state version exhausted");
                    var updated = plan.snapshot(previous.stateVersion() + 1, next);
                    var turn = new DurableActorStore.Turn(address, activation.epoch(),
                            previous.stateVersion(), inbound.deliveryId(), updated, List.of());
                    return io.commitTurn(turn).thenApply(committed -> {
                        if (committed != updated.stateVersion())
                            throw new IllegalStateException(
                                    "provider returned inconsistent replay receipt");
                        return updated;
                    });
                });
            }
            return replay.thenApply(snapshot -> new AsyncPersistentScalarCodeExecutorV1(
                    verifiedImage, plan, io, address, activation.epoch(), snapshot));
        });
    }

    public String actor_type_name() { return plan.actorName(); }
    public DurableActorStore.Address address() { return address; }
    @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }

    private static final class OwnedState implements ActorRuntime.ActorOwnedGuestState {
        private final ActorRuntime.ActorId owner;
        private final ScalarStateContainer state;
        private final long epoch;
        private long version;
        private boolean closed;

        private OwnedState(ActorRuntime.ActorId owner, long initial,
                           long epoch, long version) {
            this.owner = Objects.requireNonNull(owner, "actor owner");
            this.epoch = epoch;
            this.version = version;
            this.state = new ScalarStateContainer(initial, 4);
        }

        @Override public ActorRuntime.ActorId ownerActorId() { return owner; }

        private long read() {
            if (closed) throw new IllegalStateException("durable actor state closed");
            return state.tryRead().orElseThrow(
                    () -> new IllegalStateException("actor-local scalar state unavailable"));
        }

        private void publish(long expectedVersion, long committedVersion, long value) {
            if (closed || version != expectedVersion
                    || committedVersion != expectedVersion + 1
                    || state.trySet(value).isEmpty()) {
                closeLocal();
                throw new IllegalStateException("durable commit publish lost actor turn ownership");
            }
            version = committedVersion;
        }

        private void closeLocal() {
            if (closed) return;
            closed = true;
            state.close();
        }
    }

    private void requireContext(
            String actorTypeName, ActorRuntime.ActorContext<Object> context) {
        if (!plan.actorName().equals(actorTypeName))
            throw new SecurityException("unverified durable actor source type");
        if (context.kind() != ActorRuntime.ActorKind.PRIVATE)
            throw new SecurityException("durable actor must be PRIVATE");
        if (context.codeImage().orElseThrow() != image)
            throw new SecurityException("durable actor code image mismatch");
        if (context.privateMemory().isEmpty())
            throw new SecurityException("durable actor private memory domain missing");
        if (!context.runtime().currentThreadOwnsActorExecutionLease())
            throw new SecurityException("durable actor needs its active scheduler lease");
    }

    @Override
    public ActorRuntime.ActorOwnedGuestState initializeActor(
            String actorTypeName, ActorRuntime.ActorContext<Object> context) {
        requireContext(actorTypeName, context);
        // No I/O or activation/replay on an actor carrier. Ready is published
        // only after the host-provisioned, fully replayed immutable snapshot is
        // materialized as actor-private state.
        return new OwnedState(context.self().id(), plan.decode(prepared),
                epoch, prepared.stateVersion());
    }

    private record Outcome(boolean newCommit, long version, long value) { }

    /**
     * Strict sequence: check original-key admission -> committed receipt or
     * state CAS -> actor-carrier continuation -> publish scalar/output ->
     * runtime resumes next queued user mail. Producer never invokes guest code.
     */
    private void suspend(
            ActorRuntime.ActorContext<Object> context,
            OwnedState owned,
            String requestId,
            long expectedVersion,
            CompletionStage<Outcome> stage) {
        ActorRuntime runtime = context.runtime();
        OresFuture<Outcome> future = OresFuture.from(stage);
        ActorRuntime.ContinuationTarget target = runtime.captureCurrentContinuationTarget();
        runtime.enqueueOnCompletion(future, target, (result, failure) -> {
            if (failure != null) {
                owned.closeLocal();
                throw new IllegalStateException(
                        "PERSISTENT_SCALAR_ASYNC_COMMIT_FAILED; recover under new epoch",
                        failure);
            }
            if (result == null) {
                owned.closeLocal();
                throw new IllegalStateException("missing durable async completion receipt");
            }
            if (result.newCommit()) {
                owned.publish(expectedVersion, result.version(), result.value());
            } else if (owned.version != expectedVersion) {
                owned.closeLocal();
                throw new IllegalStateException("duplicate durable result crossed a stale turn");
            }
            // This is a scheduler-minted actor continuation, never an I/O thread.
            runtime.emitCurrentActorOutput(List.of(requestId, result.value()));
        });
        // Enqueue the result callback FIRST, then register mailbox resumption.
        // FIFO continuation ordering guarantees output/state is settled before
        // ActorRuntime marks the suspended receive processed. If the Future
        // already completed, both still enqueue and run on the actor carrier.
        runtime.suspendCurrentUserMailboxUntil(future);
    }

    @Override
    public void receiveActor(
            String actorTypeName,
            ActorRuntime.ActorOwnedGuestState actorState,
            ActorRuntime.ActorInboxMail<Object> mail,
            ActorRuntime.ActorContext<Object> context) {
        requireContext(actorTypeName, context);
        if (!(actorState instanceof OwnedState owned)
                || !owned.ownerActorId().equals(context.self().id()))
            throw new SecurityException("durable actor state ownership mismatch");
        if (!(mail.value() instanceof List<?> parts))
            throw new IllegalArgumentException("durable scalar expects a data-only command");
        long before = owned.read();
        long expectedVersion = owned.version;
        if (parts.size() == 3 && "increment".equals(parts.get(0))
                && parts.get(1) instanceof String id && parts.get(2) instanceof Long amount) {
            if (expectedVersion == Long.MAX_VALUE)
                throw new IllegalStateException("persistent state version exhausted");
            if (id.isBlank() || id.length() > 256)
                throw new IllegalArgumentException("invalid durable delivery ID");
            // Admission precedes checked arithmetic. An overflowing command
            // remains durably pending for explicit supervisor recovery, exactly
            // as in the original synchronous scalar actor contract.
            var original = new DurableActorStore.Inbound(
                    id, plan.encode_increment_message(amount));
            CompletionStage<Outcome> committed = io.enqueueFenced(address, epoch, original)
                    .thenCompose(inserted -> {
                        if (!inserted) {
                            return io.committedDelivery(address, epoch, original)
                                    .thenApply(existing -> {
                                        DurableActorStore.Snapshot receipt = existing.orElseThrow(
                                                () -> new IllegalStateException(
                                                        "PERSISTENT_SCALAR_PENDING_DUPLICATE"));
                                        return new Outcome(false, receipt.stateVersion(),
                                                plan.decode(receipt));
                                    });
                        }
                        long next = Math.addExact(before, amount);
                        var snapshot = plan.snapshot(expectedVersion + 1, next);
                        var turn = new DurableActorStore.Turn(address, epoch, expectedVersion,
                                id, snapshot, List.of());
                        return io.commitTurn(turn).thenApply(version -> {
                            if (version != snapshot.stateVersion())
                                throw new IllegalStateException(
                                        "provider returned invalid committed state version");
                            return new Outcome(true, version, next);
                        });
                    });
            suspend(context, owned, id, expectedVersion, committed);
        } else if (parts.size() == 2 && "get_count".equals(parts.get(0))
                && parts.get(1) instanceof String id) {
            if (id.isBlank() || id.length() > 256)
                throw new IllegalArgumentException("invalid durable read ID");
            CompletionStage<Outcome> fenced = io.requireActive(address, epoch, expectedVersion)
                    .thenApply(ignored -> new Outcome(false, expectedVersion, before));
            suspend(context, owned, id, expectedVersion, fenced);
        } else {
            throw new IllegalArgumentException("unverified durable scalar mailbox command");
        }
    }

    @Override
    public void tearDownActor(
            String actorTypeName,
            ActorRuntime.ActorOwnedGuestState actorState,
            ActorRuntime.ActorContext<Object> context) {
        requireContext(actorTypeName, context);
        if (!(actorState instanceof OwnedState owned)
                || !owned.ownerActorId().equals(context.self().id()))
            throw new SecurityException("durable actor teardown ownership mismatch");
        owned.closeLocal();
        // Hibernation is host I/O, never a blocking carrier operation. The
        // provider owns its lifetime and error reporting; final durable
        // hibernation is not acknowledged by ActorRuntime's STOP future yet.
        io.hibernate(address, epoch);
    }
}
