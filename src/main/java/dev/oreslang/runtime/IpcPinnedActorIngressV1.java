package dev.oreslang.runtime;

import java.io.IOException;
import java.util.Objects;

/** Trusted host adapter; never grants CONTROL or looks up a wire-selected actor. */
final class IpcPinnedActorIngressV1<M> {
    @FunctionalInterface interface Decoder<M> { M decode(byte[] body) throws IOException; }
    private final ActorRuntime.ActorRef<M> recipient;
    private final IpcReceiptCorrelation.Envelope epoch;
    private final long physicalGeneration;
    private final IpcAuthenticatedSocketTransport.InboundReplayGate replay =
            new IpcAuthenticatedSocketTransport.InboundReplayGate();
    private final IpcSchemaBoundDataV1.Binding binding;
    private final Decoder<M> decoder;

    IpcPinnedActorIngressV1(ActorRuntime.ActorRef<M> recipient,
            IpcReceiptCorrelation.Envelope epoch, long physicalGeneration,
            IpcSchemaBoundDataV1.Binding binding, Decoder<M> decoder) {
        RuntimeBlockingGuard.requireBlockingHostThread("register pinned IPC ingress");
        this.recipient = Objects.requireNonNull(recipient);
        this.epoch = Objects.requireNonNull(epoch);
        if (physicalGeneration <= 0
                || epoch.destinationIncarnation() != recipient.generation()
                || !epoch.destination().equals(recipient.id().value().toString())) {
            throw new IllegalArgumentException("IPC recipient identity/generation mismatch");
        }
        this.physicalGeneration = physicalGeneration;
        this.binding = Objects.requireNonNull(binding);
        this.decoder = Objects.requireNonNull(decoder);
    }

    /** Caller owns the authenticated-session key and immutable frame snapshot.
     * Decoder is trusted, bounded and registered with the pinned schema.
     * This returns only after the ordinary runtime DATA send commits.
     * No receipt publication or retry authorization is provided here. */
    void admit(byte[] frame, byte[] connectionBinding, byte[] key) throws IOException {
        RuntimeBlockingGuard.requireBlockingHostThread("admit pinned IPC DATA");
        byte[] body = IpcSchemaBoundDataV1.authenticateAndUnwrap(
                frame, epoch, physicalGeneration, replay, connectionBinding, key, binding);
        M message = Objects.requireNonNull(decoder.decode(body));
        // Do not resolve a new recipient by logical ID, even after restart.
        // Existing ActorRuntime.send owns the mailbox/ancestor DATA permit.
        recipient.send(message);
    }
}
