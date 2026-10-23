package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Immutable actor-to-group Mailman envelope.
 *
 * The sender id is metadata only. Replies must re-enter actors through
 * ActorGroupContext.send so actor mailbox serialization remains authoritative.
 */
public record ActorMail<Out>(
        ActorRuntime.ActorId actor,
        ActorRuntime.ActorGroupId group,
        long sequence,
        Out message,
        long actorGeneration) {
    public ActorMail(
            ActorRuntime.ActorId actor,
            ActorRuntime.ActorGroupId group,
            long sequence,
            Out message) {
        this(actor, group, sequence, message, 0L);
    }

    public ActorMail {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(group, "group");
        if (actorGeneration < 0) {
            throw new IllegalArgumentException("actorGeneration must be >= 0");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be >= 0");
        }
    }
}
