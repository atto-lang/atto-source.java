package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Trusted host adapter for ordered source-actor mailbox middleware.
 *
 * The middleware stages execute synchronously on the admitted actor lane via
 * ActorCodeExecutor.prepareActorMail. This bridge is not exposed to guest
 * code and does NOT enable @ActorMiddleware/next_val async source decorators.
 *
 * A stage may produce a new envelope with a cleaned *scalar* value. The
 * ActorRuntime performs the authoritative identity, sendability and reserved
 * memory checks after the entire chain runs.
 */
public final class ActorMailboxMiddlewareBridge implements ActorRuntime.ActorCodeExecutor {
    @FunctionalInterface
    public interface Stage {
        ActorRuntime.ActorInboxMail<Object> apply(
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) throws Exception;
    }

    private final ActorRuntime.ActorCodeExecutor delegate;
    private final List<Stage> stages;

    private ActorMailboxMiddlewareBridge(
            ActorRuntime.ActorCodeExecutor delegate, List<Stage> stages) {
        this.delegate = Objects.requireNonNull(delegate, "source executor");
        this.stages = List.copyOf(Objects.requireNonNull(stages, "middleware stages"));
        if (this.stages.size() > 16) {
            throw new IllegalArgumentException("actor middleware chain exceeds 16 stages");
        }
    }

    public static ActorRuntime.ActorCodeExecutor wrap(
            ActorRuntime.ActorCodeExecutor executor, List<Stage> stages) {
        return new ActorMailboxMiddlewareBridge(executor, stages);
    }

    @Override
    public SharedCodeImageStore.CodeImage codeImage() {
        return delegate.codeImage();
    }

    @Override
    public ActorRuntime.ActorOwnedGuestState initializeActor(
            String actorTypeName, ActorRuntime.ActorContext<Object> context) throws Exception {
        return delegate.initializeActor(actorTypeName, context);
    }

    @Override
    public ActorRuntime.ActorInboxMail<Object> prepareActorMail(
            String actorTypeName, ActorRuntime.ActorOwnedGuestState state,
            ActorRuntime.ActorInboxMail<Object> mail,
            ActorRuntime.ActorContext<Object> context) throws Exception {
        ActorRuntime.ActorInboxMail<Object> current = Objects.requireNonNull(
                delegate.prepareActorMail(actorTypeName, state, mail, context),
                "wrapped source executor returned null mailbox");
        for (Stage stage : stages) {
            current = Objects.requireNonNull(
                    stage.apply(current, context),
                    "actor middleware stage returned null mailbox");
            // Reject forged sender, recipient or sequence *before* the next
            // trusted stage is invoked, preventing internal chain confusion.
            if (!mail.recipient().equals(current.recipient())
                    || !mail.sender().equals(current.sender())
                    || mail.sequence() != current.sequence()) {
                throw new SecurityException("actor middleware stage attempted to change mailbox identity");
            }
        }
        return current;
    }

    @Override
    public void receiveActor(
            String actorTypeName, ActorRuntime.ActorOwnedGuestState state,
            ActorRuntime.ActorInboxMail<Object> mail,
            ActorRuntime.ActorContext<Object> context) throws Exception {
        delegate.receiveActor(actorTypeName, state, mail, context);
    }
}
