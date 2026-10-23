package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot compiler bridge for a replacement source actor. The candidate
 * physical actor is initialized from a validated checkpoint instead of running
 * the candidate generation's on_start initializer.
 */
final class RestoringSourceActorExecutorV2
        implements ActorRuntime.ActorCodeExecutor {
    private final ActorRuntime.ActorCodeExecutor delegate;
    private final String actorTypeName;
    private final AtomicBoolean claimed = new AtomicBoolean();
    private byte[] checkpoint;

    RestoringSourceActorExecutorV2(
            ActorRuntime.ActorCodeExecutor delegate,
            String actorTypeName,
            byte[] checkpoint) {
        this.delegate = Objects.requireNonNull(delegate, "candidate executor");
        if (actorTypeName == null || actorTypeName.isBlank())
            throw new IllegalArgumentException("actor type name cannot be blank");
        this.actorTypeName = actorTypeName;
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (checkpoint.length > 1_048_576)
            throw new IllegalArgumentException("checkpoint exceeds 1 MiB");
        this.checkpoint = checkpoint.clone();
    }

    @Override
    public SharedCodeImageStore.CodeImage codeImage() {
        return delegate.codeImage();
    }

    @Override
    public ActorRuntime.ActorOwnedGuestState initializeActor(
            String actorTypeName,
            ActorRuntime.ActorContext<Object> context) {
        if (!this.actorTypeName.equals(actorTypeName))
            throw new SecurityException("restored actor type mismatch");
        if (!claimed.compareAndSet(false, true))
            throw new IllegalStateException(
                    "restoring source executor may initialize exactly one actor");
        byte[] bytes = checkpoint;
        checkpoint = null;
        try {
            return Objects.requireNonNull(
                    delegate.restoreActorState(actorTypeName, bytes, context),
                    "candidate restore returned null actor state");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public void receiveActor(
            String actorTypeName,
            ActorRuntime.ActorOwnedGuestState state,
            ActorRuntime.ActorInboxMail<Object> mail,
            ActorRuntime.ActorContext<Object> context) throws Exception {
        delegate.receiveActor(actorTypeName, state, mail, context);
    }

    @Override
    public Object actorUpgradeDomain() {
        return delegate.actorUpgradeDomain();
    }

    @Override
    public String actorCodeSha256(String actorTypeName) {
        return delegate.actorCodeSha256(actorTypeName);
    }

    @Override
    public String actorStateSchemaSha256(String actorTypeName) {
        return delegate.actorStateSchemaSha256(actorTypeName);
    }

    @Override
    public String actorMessageAbiSha256(String actorTypeName) {
        return delegate.actorMessageAbiSha256(actorTypeName);
    }

    @Override
    public byte[] snapshotActorState(
            String actorTypeName,
            ActorRuntime.ActorOwnedGuestState state,
            ActorRuntime.ActorContext<Object> context) {
        return delegate.snapshotActorState(actorTypeName, state, context);
    }

    @Override
    public ActorRuntime.ActorOwnedGuestState restoreActorState(
            String actorTypeName,
            byte[] snapshot,
            ActorRuntime.ActorContext<Object> context) {
        return delegate.restoreActorState(actorTypeName, snapshot, context);
    }
}
