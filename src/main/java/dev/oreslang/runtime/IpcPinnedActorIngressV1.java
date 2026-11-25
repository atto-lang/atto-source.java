package dev.oreslang.runtime;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Trusted host adapter; never grants CONTROL or looks up a wire-selected actor. */
final class IpcPinnedActorIngressV1<M> implements AutoCloseable {
    /** Trusted registration-time codec. Preflight must be bounded and
     * allocation-free with respect to payload size. It returns conservative
     * logical materialization credit, not a peer-declared size. */
    interface Decoder<M> {
        default void requireBinding(IpcSchemaBoundDataV1.Binding binding) { }
        long preflight(ByteBuffer body) throws IOException;
        M decode(ByteBuffer body, long admittedBytes) throws IOException;
    }
    private final ActorRuntime.ActorRef<M> recipient;
    private final IpcReceiptCorrelation.Envelope epoch;
    /** One broker authority owns key, connection binding, generation and replay.
     * The monitor is memory-only: never holds physical read, decoder or actor locks. */
    static final class Session {
        private final IpcPhysicalConnectionBroker.Authority.Transfer transfer;
        private final IpcAuthenticatedSocketTransport.InboundReplayGate replay =
                new IpcAuthenticatedSocketTransport.InboundReplayGate();
        private volatile boolean fenced;

        Session(IpcPhysicalConnectionBroker.Authority authority) {
            transfer=Objects.requireNonNull(authority,"authority").consume();
        }
        boolean fenced(){return fenced;}
        synchronized byte[] authenticate(byte[] frame,IpcReceiptCorrelation.Envelope epoch,
                IpcSchemaBoundDataV1.Binding binding) throws IOException {
            if(fenced)throw new IOException("IPC ingress session closed");
            return IpcSchemaBoundDataV1.authenticateAndUnwrap(frame,epoch,transfer.generation(),replay,
                    transfer.connectionBinding(),transfer.macKey(),binding);
        }
        synchronized void fence(){
            if(fenced)return;
            fenced=true;
            transfer.destroy();
        }
        synchronized boolean erased(){
            if(!fenced)return false;
            for(byte x:transfer.macKey())if(x!=0)return false;
            for(byte x:transfer.connectionBinding())if(x!=0)return false;
            return true;
        }
    }
    private final Session session;
    private final IpcSchemaBoundDataV1.Binding binding;
    private final Decoder<M> decoder;
    private final IpcIngressBudgetV1 budget;
    private final long maxDecodedBytes;

    IpcPinnedActorIngressV1(ActorRuntime.ActorRef<M> recipient,
            IpcReceiptCorrelation.Envelope epoch, IpcPhysicalConnectionBroker.Authority authority,
            IpcSchemaBoundDataV1.Binding binding, IpcIngressBudgetV1 budget,
            long maxDecodedBytes, Decoder<M> decoder) {
        RuntimeBlockingGuard.requireBlockingHostThread("register pinned IPC ingress");
        this.recipient = Objects.requireNonNull(recipient);
        this.epoch = Objects.requireNonNull(epoch);
        if (epoch.destinationIncarnation() != recipient.generation()
                || !epoch.destination().equals(recipient.id().value().toString())) {
            throw new IllegalArgumentException("IPC recipient identity/generation mismatch");
        }
        this.binding = Objects.requireNonNull(binding);
        this.decoder = Objects.requireNonNull(decoder);
        decoder.requireBinding(this.binding);
        this.budget = Objects.requireNonNull(budget);
        recipient.requireIpcIngressBudget(epoch.domain(),budget);
        if (maxDecodedBytes <= 0 || maxDecodedBytes > 8L*1024*1024)
            throw new IllegalArgumentException("invalid registered decoded limit");
        this.maxDecodedBytes = maxDecodedBytes;
        Session owned=new Session(authority);
        try{budget.registerSession(owned);}
        catch(RuntimeException|Error failure){owned.fence();throw failure;}
        this.session=owned;
    }

    /** The broker-owned session retains authentication keys; caller supplies only frame bytes.
     * Decoder is trusted, bounded and registered with the pinned schema.
     * This returns only after the ordinary runtime DATA send commits.
     * No receipt publication or retry authorization is provided here. */
    void admit(byte[] frame) throws IOException {
        RuntimeBlockingGuard.requireBlockingHostThread("admit pinned IPC DATA");
        if(session.fenced())throw new IOException("IPC ingress session closed");
        Objects.requireNonNull(frame, "frame");
        IpcIngressBudgetV1.Lease lease = budget.reserve(frame.length);
        try {
            authenticateDecodePublish(frame.clone(), lease);
        } finally { lease.close(); }
    }

    /** Read the length before allocating the frame; reserve tenant staging
     * before reading the body. A refusal requires transport close/discard;
     * callers must not parse another frame from the partially consumed stream. */
    void readAndAdmit(InputStream input) throws IOException {
        RuntimeBlockingGuard.requireBlockingHostThread("read pinned IPC DATA");
        if(session.fenced())throw new IOException("IPC ingress session closed");
        DataInputStream in = new DataInputStream(Objects.requireNonNull(input));
        int length = in.readInt();
        if (length < 32 || length > IpcSchemaBoundDataV1.MAX_AUTHENTICATED_FRAME)
            throw new IOException("invalid bounded schema DATA length");
        IpcIngressBudgetV1.Lease lease = budget.reserve(length);
        try {
            byte[] frame = new byte[length];
            in.readFully(frame);
            authenticateDecodePublish(frame, lease);
        } finally { lease.close(); }
    }

    private void authenticateDecodePublish(byte[] frame,IpcIngressBudgetV1.Lease lease) throws IOException {
        byte[] body=session.authenticate(frame,epoch,binding);
        ByteBuffer immutable = ByteBuffer.wrap(body).asReadOnlyBuffer();
        long decodedBytes = decoder.preflight(immutable.asReadOnlyBuffer());
        if (decodedBytes <= 0 || decodedBytes > maxDecodedBytes)
            throw new IOException("registered decoded credit exceeded");
        lease.reserveMaterialization(decodedBytes);
        // New read-only view: preflight cannot mutate bytes or its decode cursor.
        M message = Objects.requireNonNull(decoder.decode(immutable.asReadOnlyBuffer(), decodedBytes));
        // Do not resolve a new recipient by logical ID, even after restart.
        // Existing ActorRuntime.send owns the mailbox/ancestor DATA permit.
        recipient.sendAuthenticatedIpc(message,budget,session);
    }
    @Override public void close(){
        RuntimeBlockingGuard.requireBlockingHostThread("close IPC ingress session");
        recipient.closeIpcIngressSession(budget,session);
    }
    boolean authenticationErased(){return session.erased();}
}
