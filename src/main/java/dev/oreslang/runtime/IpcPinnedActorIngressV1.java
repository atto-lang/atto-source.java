package dev.oreslang.runtime;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Trusted host adapter; never grants CONTROL or looks up a wire-selected actor. */
final class IpcPinnedActorIngressV1<M> {
    /** Trusted registration-time codec. Preflight must be bounded and
     * allocation-free with respect to payload size. It returns conservative
     * logical materialization credit, not a peer-declared size. */
    interface Decoder<M> {
        long preflight(ByteBuffer body) throws IOException;
        M decode(ByteBuffer body, long admittedBytes) throws IOException;
    }
    private final ActorRuntime.ActorRef<M> recipient;
    private final IpcReceiptCorrelation.Envelope epoch;
    private final long physicalGeneration;
    private final IpcAuthenticatedSocketTransport.InboundReplayGate replay =
            new IpcAuthenticatedSocketTransport.InboundReplayGate();
    private final IpcSchemaBoundDataV1.Binding binding;
    private final Decoder<M> decoder;
    private final IpcIngressBudgetV1 budget;
    private final long maxDecodedBytes;

    IpcPinnedActorIngressV1(ActorRuntime.ActorRef<M> recipient,
            IpcReceiptCorrelation.Envelope epoch, long physicalGeneration,
            IpcSchemaBoundDataV1.Binding binding, IpcIngressBudgetV1 budget,
            long maxDecodedBytes, Decoder<M> decoder) {
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
        this.budget = Objects.requireNonNull(budget);
        recipient.requireIpcIngressBudget(epoch.domain(),budget);
        if (maxDecodedBytes <= 0 || maxDecodedBytes > 8L*1024*1024)
            throw new IllegalArgumentException("invalid registered decoded limit");
        this.maxDecodedBytes = maxDecodedBytes;
    }

    /** Caller owns the authenticated-session key and immutable frame snapshot.
     * Decoder is trusted, bounded and registered with the pinned schema.
     * This returns only after the ordinary runtime DATA send commits.
     * No receipt publication or retry authorization is provided here. */
    void admit(byte[] frame, byte[] connectionBinding, byte[] key) throws IOException {
        RuntimeBlockingGuard.requireBlockingHostThread("admit pinned IPC DATA");
        Objects.requireNonNull(frame, "frame");
        IpcIngressBudgetV1.Lease lease = budget.reserve(frame.length);
        try {
            authenticateDecodePublish(frame.clone(), connectionBinding, key, lease);
        } finally { lease.close(); }
    }

    /** Read the length before allocating the frame; reserve tenant staging
     * before reading the body. A refusal requires transport close/discard;
     * callers must not parse another frame from the partially consumed stream. */
    void readAndAdmit(InputStream input, byte[] connectionBinding, byte[] key) throws IOException {
        RuntimeBlockingGuard.requireBlockingHostThread("read pinned IPC DATA");
        DataInputStream in = new DataInputStream(Objects.requireNonNull(input));
        int length = in.readInt();
        if (length < 32 || length > IpcSchemaBoundDataV1.MAX_AUTHENTICATED_FRAME)
            throw new IOException("invalid bounded schema DATA length");
        IpcIngressBudgetV1.Lease lease = budget.reserve(length);
        try {
            byte[] frame = new byte[length];
            in.readFully(frame);
            authenticateDecodePublish(frame, connectionBinding, key, lease);
        } finally { lease.close(); }
    }

    private void authenticateDecodePublish(byte[] frame, byte[] connectionBinding, byte[] key,
            IpcIngressBudgetV1.Lease lease) throws IOException {
        byte[] body = IpcSchemaBoundDataV1.authenticateAndUnwrap(
                frame, epoch, physicalGeneration, replay, connectionBinding, key, binding);
        ByteBuffer immutable = ByteBuffer.wrap(body).asReadOnlyBuffer();
        long decodedBytes = decoder.preflight(immutable.asReadOnlyBuffer());
        if (decodedBytes <= 0 || decodedBytes > maxDecodedBytes)
            throw new IOException("registered decoded credit exceeded");
        lease.reserveMaterialization(decodedBytes);
        // New read-only view: preflight cannot mutate bytes or its decode cursor.
        M message = Objects.requireNonNull(decoder.decode(immutable.asReadOnlyBuffer(), decodedBytes));
        // Do not resolve a new recipient by logical ID, even after restart.
        // Existing ActorRuntime.send owns the mailbox/ancestor DATA permit.
        recipient.send(message);
    }
}
