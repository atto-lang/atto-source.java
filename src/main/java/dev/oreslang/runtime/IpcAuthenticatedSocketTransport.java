package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Trusted host-only authenticated stream adapter for the receipt substrate.
 *
 * <p>This is deliberately package-private: Oreslang guest actors never receive
 * a Socket, MAC key, transport lease, frame writer or close authority. A
 * supervisor/broker constructs one adapter per authenticated physical
 * connection and immutable transport epoch.</p>
 *
 * <p>The shared MAC key is an embedding/broker input, not a guest capability.
 * Production deployments should obtain it from an authenticated channel/key
 * agreement and rotate it with the transport epoch. This class does not do
 * DNS, connection establishment or authorization policy.</p>
 */
final class IpcAuthenticatedSocketTransport implements AutoCloseable {
    private static final int MAGIC = 0x4f524553; // ORES
    private static final int VERSION = 3;
    private static final int TYPE_DATA = 1;
    private static final int TYPE_RECEIPT = 2;
    private static final int RECEIPT_REMOTE_ACCEPTED = 1;
    private static final int RECEIPT_HANDLER_COMMITTED = 2;
    private static final int RECEIPT_HANDLER_FAILED = 3;
    private static final int MAC_BYTES = 32;
    private static final int CONNECTION_BINDING_BYTES = 16;
    private static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TOKEN_BYTES = 96;
    // Pre-authentication allocation caps. DATA needs payload headroom; a
    // receipt is only header + envelope + stage + MAC and must never consume
    // megabytes merely because an unauthenticated peer advertised that length.
    private static final int MAX_DATA_FRAME_BYTES = MAX_PAYLOAD_BYTES + 1024;
    private static final int MAX_RECEIPT_FRAME_BYTES = 1024;

    private final Socket socket;
    private final IpcReceiptCompletionBridge receipts;
    private final IpcTransportSessionLease sessionLease;
    private final IpcReceiptCorrelation.Envelope trustedEpoch;
    private final long physicalGeneration;
    private final byte[] macKey;
    private final byte[] connectionBinding;
    private final InputStream input;
    private final OutputStream output;
    private final IpcTransportSessionLease.CleanupScheduler cleanupScheduler;
    // Opaque host identity used only to bind one receipt bridge to this exact
    // physical connection object. It is never serialized or guest-visible.
    private final Object physicalOwner = new Object();
    private final AtomicBoolean abortScheduled = new AtomicBoolean();
    private final Object sendGate = new Object();
    private final Object readGate = new Object();
    private final Object writeGate = new Object();

    IpcAuthenticatedSocketTransport(
            Socket socket,
            IpcReceiptCompletionBridge receipts,
            IpcReceiptCorrelation.Envelope trustedEpoch,
            long physicalGeneration,
            int maxOutstandingWrites,
            byte[] macKey,
            byte[] connectionBinding,
            IpcTransportSessionLease.CleanupScheduler cleanupScheduler) throws IOException {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.trustedEpoch = Objects.requireNonNull(trustedEpoch, "trustedEpoch");
        if (physicalGeneration <= 0) {
            throw new IllegalArgumentException("physical generation must be positive");
        }
        if (maxOutstandingWrites != 1) {
            throw new IllegalArgumentException(
                    "TCP physical write concurrency must be exactly one; "
                    + "receipt lifetimes are bounded separately");
        }
        if (macKey == null || macKey.length < 32 || macKey.length > 128) {
            throw new IllegalArgumentException("IPC MAC key must be 32..128 bytes");
        }
        if (connectionBinding == null
                || connectionBinding.length != CONNECTION_BINDING_BYTES) {
            throw new IllegalArgumentException(
                    "IPC connection binding must be exactly 16 bytes");
        }
        boolean nonzeroBinding = false;
        for (byte value : connectionBinding) nonzeroBinding |= value != 0;
        if (!nonzeroBinding) {
            throw new IllegalArgumentException(
                    "IPC connection binding must not be all zero");
        }
        this.cleanupScheduler = Objects.requireNonNull(
                cleanupScheduler, "cleanupScheduler");
        byte[] privateMacKey = macKey.clone();
        byte[] privateConnectionBinding = connectionBinding.clone();
        final InputStream socketInput;
        final OutputStream socketOutput;
        try {
            socketInput = socket.getInputStream();
            socketOutput = socket.getOutputStream();
        } catch (IOException | RuntimeException | Error constructionFailure) {
            Arrays.fill(privateMacKey, (byte) 0);
            try {
                socket.close();
            } catch (IOException closeFailure) {
                constructionFailure.addSuppressed(closeFailure);
            }
            throw constructionFailure;
        }
        try {
            receipts.claimPhysicalTransport(
                    physicalOwner, trustedEpoch, physicalGeneration);
        } catch (RuntimeException | Error bindingFailure) {
            Arrays.fill(privateMacKey, (byte) 0);
            try {
                socket.close();
            } catch (IOException closeFailure) {
                bindingFailure.addSuppressed(closeFailure);
            }
            throw bindingFailure;
        }
        this.macKey = privateMacKey;
        this.connectionBinding = privateConnectionBinding;
        this.physicalGeneration = physicalGeneration;
        this.input = socketInput;
        this.output = socketOutput;
        this.sessionLease = new IpcTransportSessionLease(
                trustedEpoch,
                physicalGeneration,
                maxOutstandingWrites,
                receipts,
                physicalOwner,
                (ignoredEnvelope, preparedFrame) -> {
                    synchronized (writeGate) {
                        writeWireFrame(output, preparedFrame);
                    }
                },
                () -> {
                    try {
                        // Close first so a receipt reader blocked in kernel I/O
                        // is interrupted without waiting on readGate.
                        socket.close();
                    } finally {
                        // A reader may already have consumed a complete frame
                        // and be authenticating/applying it under readGate.
                        // Do not destroy the key until that ordered receive
                        // transaction exits. Taking readGate before close would
                        // deadlock a blocked socket read.
                        synchronized (readGate) {
                            Arrays.fill(this.macKey, (byte) 0);
                        }
                    }
                },
                this.cleanupScheduler);
    }

    /**
     * Admit and synchronously attempt one authenticated DATA frame.
     *
     * <p>If a physical write fails after dispatch commitment, the session
     * lease initiates close/quarantine and the outcome is indeterminate rather
     * than "never sent". The returned completion remains the authoritative
     * receipt outcome. Host callers should record {@link SendSubmission#error}
     * for diagnostics, never use it as replay authorization.</p>
     */
    SendSubmission send(IpcReceiptCorrelation.Envelope message, byte[] payload) {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC socket send");
        Objects.requireNonNull(message, "message");
        byte[] source = Objects.requireNonNull(payload, "payload");
        requireTrustedEpoch(message);
        if (source.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("IPC payload too large");
        }
        byte[] body = source.clone();
        String digest = sha256Hex(body);
        if (!digest.equals(message.payloadSha256())) {
            throw new IllegalArgumentException("IPC payload digest mismatch");
        }

        // Complete all deterministic in-memory encoding before admission or
        // dispatch commitment. Encoding failure therefore cannot create an
        // ambiguous remote-delivery state or consume a correlation slot.
        final byte[] preparedFrame;
        try {
            preparedFrame = encodeDataFrame(
                    message, body, physicalGeneration, connectionBinding, macKey);
        } catch (IOException impossibleMemoryEncodingFailure) {
            throw new IllegalStateException(
                    "in-memory authenticated IPC frame encoding failed",
                    impossibleMemoryEncodingFailure);
        }

        synchronized (sendGate) {
            try {
                var attempt = sessionLease.sendPreparedFrame(
                        physicalGeneration, message, preparedFrame);
                return new SendSubmission(attempt.outcome(), attempt.error());
            } catch (IpcTransportSessionLease.PreDispatchRejectedException rejected) {
                CompletionStage<IpcReceiptCorrelation.Stage> outcome =
                        CompletableFuture.completedFuture(
                                IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE)
                                .minimalCompletionStage();
                return new SendSubmission(outcome, rejected);
            }
        }
    }

    /**
     * Read one MAC-authenticated peer receipt and apply it to the host ledger.
     * Local-only verdicts are rejected both by codec policy and the ledger.
     */
    IpcReceiptCorrelation.Stage readVerifiedReceipt() throws IOException {
        RuntimeBlockingGuard.requireBlockingHostThread("IPC receipt read");
        final IpcReceiptCorrelation.Stage stage;
        final IpcReceiptCompletionBridge.DeferredVerifiedCompletion completion;
        try {
            // TCP receipt order is protocol order. Keep frame consumption,
            // authentication, epoch validation and the ledger transition under
            // one receive gate so concurrent host readers cannot read ACK then
            // COMMIT in order but apply those state transitions out of order.
            // Publishing a terminal completion is deliberately deferred until
            // after readGate is released because dependent callbacks can
            // synchronously reenter transport shutdown.
            synchronized (readGate) {
                byte[] frame = readReceiptWireFrame(input);
                DecodedReceipt receipt = decodeReceiptFrame(
                        frame, connectionBinding, macKey);
                if (receipt.physicalGeneration() != physicalGeneration) {
                    throw new IOException("stale IPC physical transport generation");
                }
                requireTrustedEpoch(receipt.envelope());
                stage = receipt.stage();
                completion = receipts.acceptVerifiedDeferred(
                        physicalOwner, receipt.envelope(),
                        physicalGeneration, stage);
            }
        } catch (IOException | RuntimeException | Error failure) {
            // Authentication, framing, EOF and state-machine violations are
            // fatal to this physical session. Never continue parsing a
            // desynchronized or hostile stream after an invalid receipt.
            requestClose();
            throw failure;
        }
        if (completion != null) completion.publish();
        return stage;
    }

    void requestClose() {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC transport close request");
        // Fence new sends first. If a physical write is still pinned, submit
        // the abort to the same bounded host cleanup domain; never run
        // Socket.close() on the requesting/actor carrier.
        sessionLease.requestClose();
        var snapshot = sessionLease.snapshot();
        if (snapshot.phase() == IpcTransportSessionLease.Phase.CLOSING
                && snapshot.activeWrites() > 0) {
            schedulePhysicalAbort();
        }
    }

    /**
     * Host-supervisor bounded shutdown wait. A timeout never means closed:
     * unresolved writes are quarantined and the generation remains unusable.
     * Do not invoke this blocking wait from an actor carrier.
     */
    boolean requestCloseAndAwait(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0 || timeoutMillis > 300_000) {
            throw new IllegalArgumentException("invalid IPC close wait");
        }
        RuntimeBlockingGuard.requireBlockingHostThread("IPC transport close wait");
        requestClose();
        if (sessionLease.awaitTerminal(timeoutMillis)) return true;
        var snapshot = sessionLease.snapshot();
        if (snapshot.phase() != IpcTransportSessionLease.Phase.RETIRED
                && snapshot.phase() != IpcTransportSessionLease.Phase.QUARANTINED) {
            sessionLease.quarantineIfUnsettled(
                    new IOException("IPC transport close/drain deadline exceeded"));
        }
        return false;
    }

    private void schedulePhysicalAbort() {
        if (!abortScheduled.compareAndSet(false, true)) return;
        Thread caller = Thread.currentThread();
        try {
            cleanupScheduler.enqueue(() -> {
                // A bad scheduler may dispatch this callback onto a different
                // actor carrier. Reject before any lease monitor acquisition
                // or Socket.close(); a host supervisor timeout/retry keeps the
                // generation fail-closed without parking guest execution.
                RuntimeBlockingGuard.requireBlockingHostThread(
                        "IPC transport abort worker");
                if (Thread.currentThread() == caller) {
                    sessionLease.quarantineIfUnsettled(new IllegalStateException(
                            "IPC abort scheduler executed inline on requesting carrier"));
                    return;
                }
                try {
                    socket.close();
                } catch (IOException abortFailure) {
                    sessionLease.quarantineIfUnsettled(abortFailure);
                }
            });
        } catch (RuntimeException | Error schedulingFailure) {
            sessionLease.quarantineIfUnsettled(schedulingFailure);
        }
    }

    /**
     * Explicitly retry only the physical abort of a quarantined generation
     * while a write is still pinned. This never reopens send admission and
     * never retires receipts; after the writer drains, the supervisor must
     * separately invoke retryQuarantinedClose().
     */
    void retryQuarantinedAbort() {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC transport quarantined abort retry");
        var snapshot = sessionLease.snapshot();
        if (snapshot.phase() != IpcTransportSessionLease.Phase.QUARANTINED
                || snapshot.activeWrites() <= 0) {
            throw new IllegalStateException(
                    "transport has no quarantined active write to abort");
        }
        abortScheduled.set(false);
        schedulePhysicalAbort();
    }

    /**
     * Explicit host-supervisor recovery after bounded cleanup scheduling or
     * physical-close confirmation failure. Never automatic and never guest
     * callable; a quarantined generation cannot reopen for new writes.
     */
    void retryQuarantinedClose() {
        RuntimeBlockingGuard.requireBlockingHostThread(
                "IPC transport quarantined close retry");
        sessionLease.retryQuarantinedClose();
    }

    IpcTransportSessionLease.Snapshot snapshot() {
        return sessionLease.snapshot();
    }

    /** Package-local audit hook: exposes no key bytes. */
    boolean keyCloneZeroized() {
        for (byte value : macKey) if (value != 0) return false;
        return true;
    }

    @Override
    public void close() {
        requestClose();
    }

    private void requireTrustedEpoch(IpcReceiptCorrelation.Envelope message) {
        if (!sameEpoch(trustedEpoch, message)) {
            throw new IllegalArgumentException("message does not match authenticated transport epoch");
        }
    }

    private static boolean sameEpoch(
            IpcReceiptCorrelation.Envelope a,
            IpcReceiptCorrelation.Envelope b) {
        return a.domain().equals(b.domain())
                && a.sender().equals(b.sender())
                && a.destination().equals(b.destination())
                && a.session().equals(b.session())
                && a.senderIncarnation() == b.senderIncarnation()
                && a.destinationIncarnation() == b.destinationIncarnation()
                && a.routeEpoch() == b.routeEpoch()
                && a.capabilityGeneration() == b.capabilityGeneration();
    }

    record SendSubmission(
            CompletionStage<IpcReceiptCorrelation.Stage> outcome,
            Throwable error) {
        SendSubmission {
            Objects.requireNonNull(outcome, "outcome");
        }
        boolean writeAttemptFailed() { return error != null; }
    }

    record DecodedData(
            IpcReceiptCorrelation.Envelope envelope,
            long physicalGeneration,
            byte[] payload) {
        DecodedData {
            Objects.requireNonNull(envelope, "envelope");
            payload = Objects.requireNonNull(payload, "payload").clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
    }

    private record DecodedReceipt(
            IpcReceiptCorrelation.Envelope envelope,
            long physicalGeneration,
            IpcReceiptCorrelation.Stage stage) { }

    /**
     * Constant-memory inbound DATA replay gate. One instance belongs to one
     * authenticated physical connection direction. TCP preserves byte order;
     * a repeated or decreasing authenticated sequence therefore represents
     * replay/stale state rather than benign network reordering.
     */
    static final class InboundReplayGate {
        private long lastMessageSequence;
        private long lastFrameSequence;

        synchronized void accept(IpcReceiptCorrelation.Envelope envelope) {
            Objects.requireNonNull(envelope, "envelope");
            if (envelope.messageSequence() <= lastMessageSequence
                    || envelope.frameSequence() <= lastFrameSequence) {
                throw new IllegalArgumentException(
                        "duplicate/reordered authenticated IPC DATA sequence");
            }
            lastMessageSequence = envelope.messageSequence();
            lastFrameSequence = envelope.frameSequence();
        }

        synchronized long lastMessageSequence() { return lastMessageSequence; }
        synchronized long lastFrameSequence() { return lastFrameSequence; }
    }

    static byte[] encodeDataFrame(
            IpcReceiptCorrelation.Envelope envelope,
            byte[] payload,
            long physicalGeneration,
            byte[] connectionBinding,
            byte[] key) throws IOException {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("IPC payload too large");
        }
        if (!sha256Hex(payload).equals(envelope.payloadSha256())) {
            throw new IllegalArgumentException("IPC payload digest mismatch");
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            writeHeader(out, TYPE_DATA, physicalGeneration, connectionBinding);
            writeEnvelope(out, envelope);
            out.writeInt(payload.length);
            out.write(payload);
        }
        return appendMac(raw.toByteArray(), key);
    }

    static DecodedData decodeDataFrame(
            byte[] frame,
            IpcReceiptCorrelation.Envelope expectedEpoch,
            long expectedPhysicalGeneration,
            InboundReplayGate replayGate,
            byte[] expectedConnectionBinding,
            byte[] key) throws IOException {
        Objects.requireNonNull(expectedEpoch, "expectedEpoch");
        Objects.requireNonNull(replayGate, "replayGate");
        if (expectedPhysicalGeneration <= 0) {
            throw new IllegalArgumentException("physical generation must be positive");
        }
        byte[] authenticated = verifyAndStripMac(frame, key);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(authenticated))) {
            long physicalGeneration = requireHeader(
                    in, TYPE_DATA, expectedConnectionBinding);
            IpcReceiptCorrelation.Envelope envelope = readEnvelope(in);
            int length = in.readInt();
            if (length < 0 || length > MAX_PAYLOAD_BYTES || length > in.available()) {
                throw new IOException("invalid bounded IPC payload length");
            }
            byte[] payload = in.readNBytes(length);
            if (payload.length != length || in.available() != 0) {
                throw new IOException("truncated or trailing IPC DATA frame");
            }
            if (!sha256Hex(payload).equals(envelope.payloadSha256())) {
                throw new IOException("authenticated IPC payload digest mismatch");
            }
            if (physicalGeneration != expectedPhysicalGeneration
                    || !sameEpoch(expectedEpoch, envelope)) {
                throw new IOException("stale IPC DATA transport epoch/generation");
            }
            try {
                replayGate.accept(envelope);
            } catch (IllegalArgumentException replay) {
                throw new IOException("replayed/reordered authenticated IPC DATA", replay);
            }
            return new DecodedData(envelope, physicalGeneration, payload);
        }
    }

    static byte[] encodeReceiptFrame(
            IpcReceiptCorrelation.Envelope envelope,
            long physicalGeneration,
            IpcReceiptCorrelation.Stage stage,
            byte[] connectionBinding,
            byte[] key) throws IOException {
        Objects.requireNonNull(envelope, "envelope");
        requireRemoteReceiptStage(stage);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            writeHeader(out, TYPE_RECEIPT, physicalGeneration, connectionBinding);
            writeEnvelope(out, envelope);
            out.writeByte(remoteReceiptWireCode(stage));
        }
        return appendMac(raw.toByteArray(), key);
    }

    private static DecodedReceipt decodeReceiptFrame(
            byte[] frame, byte[] expectedConnectionBinding, byte[] key) throws IOException {
        byte[] authenticated = verifyAndStripMac(frame, key);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(authenticated))) {
            long physicalGeneration = requireHeader(
                    in, TYPE_RECEIPT, expectedConnectionBinding);
            IpcReceiptCorrelation.Envelope envelope = readEnvelope(in);
            int wireCode = in.readUnsignedByte();
            if (in.available() != 0) {
                throw new IOException("invalid IPC receipt frame length");
            }
            final IpcReceiptCorrelation.Stage stage;
            try {
                stage = remoteReceiptStage(wireCode);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("peer asserted invalid IPC receipt code", invalid);
            }
            return new DecodedReceipt(envelope, physicalGeneration, stage);
        }
    }

    static void writeWireFrame(OutputStream output, byte[] authenticatedFrame) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(authenticatedFrame, "authenticatedFrame");
        if (authenticatedFrame.length < MAC_BYTES
                || authenticatedFrame.length > MAX_DATA_FRAME_BYTES) {
            throw new IOException("invalid bounded IPC wire frame");
        }
        DataOutputStream out = new DataOutputStream(output);
        out.writeInt(authenticatedFrame.length);
        out.write(authenticatedFrame);
        out.flush();
    }

    static byte[] readDataWireFrame(InputStream input) throws IOException {
        return readWireFrame(input, MAX_DATA_FRAME_BYTES);
    }

    static byte[] readReceiptWireFrame(InputStream input) throws IOException {
        return readWireFrame(input, MAX_RECEIPT_FRAME_BYTES);
    }

    private static byte[] readWireFrame(InputStream input, int maximumBytes)
            throws IOException {
        Objects.requireNonNull(input, "input");
        if (maximumBytes < MAC_BYTES || maximumBytes > MAX_DATA_FRAME_BYTES) {
            throw new IllegalArgumentException("invalid IPC frame allocation cap");
        }
        DataInputStream in = new DataInputStream(input);
        final int length;
        try {
            length = in.readInt();
        } catch (EOFException eof) {
            throw eof;
        }
        if (length < MAC_BYTES || length > maximumBytes) {
            throw new IOException("invalid bounded IPC wire frame length");
        }
        byte[] frame = in.readNBytes(length);
        if (frame.length != length) {
            throw new EOFException("truncated IPC wire frame");
        }
        return frame;
    }

    private static void writeHeader(
            DataOutputStream out, int type, long physicalGeneration,
            byte[] connectionBinding) throws IOException {
        if (physicalGeneration <= 0) {
            throw new IllegalArgumentException("physical generation must be positive");
        }
        requireConnectionBinding(connectionBinding);
        out.writeInt(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(type);
        out.writeLong(physicalGeneration);
        out.write(connectionBinding);
    }

    private static long requireHeader(
            DataInputStream in, int type, byte[] expectedConnectionBinding)
            throws IOException {
        if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION
                || in.readUnsignedByte() != type) {
            throw new IOException("invalid IPC frame header");
        }
        long physicalGeneration = in.readLong();
        if (physicalGeneration <= 0) {
            throw new IOException("invalid IPC physical transport generation");
        }
        requireConnectionBinding(expectedConnectionBinding);
        byte[] actualBinding = in.readNBytes(CONNECTION_BINDING_BYTES);
        if (actualBinding.length != CONNECTION_BINDING_BYTES
                || !MessageDigest.isEqual(
                        expectedConnectionBinding, actualBinding)) {
            throw new IOException("stale IPC connection binding");
        }
        return physicalGeneration;
    }

    private static void requireConnectionBinding(byte[] binding) {
        if (binding == null || binding.length != CONNECTION_BINDING_BYTES) {
            throw new IllegalArgumentException(
                    "IPC connection binding must be exactly 16 bytes");
        }
        boolean nonzero = false;
        for (byte value : binding) nonzero |= value != 0;
        if (!nonzero) {
            throw new IllegalArgumentException(
                    "IPC connection binding must not be all zero");
        }
    }

    private static void writeEnvelope(
            DataOutputStream out,
            IpcReceiptCorrelation.Envelope envelope) throws IOException {
        writeToken(out, envelope.domain());
        writeToken(out, envelope.sender());
        writeToken(out, envelope.destination());
        writeToken(out, envelope.session());
        writeToken(out, envelope.payloadSha256());
        out.writeLong(envelope.senderIncarnation());
        out.writeLong(envelope.destinationIncarnation());
        out.writeLong(envelope.routeEpoch());
        out.writeLong(envelope.capabilityGeneration());
        out.writeLong(envelope.messageSequence());
        out.writeLong(envelope.frameSequence());
    }

    private static IpcReceiptCorrelation.Envelope readEnvelope(DataInputStream in)
            throws IOException {
        try {
            return new IpcReceiptCorrelation.Envelope(
                    readToken(in), readToken(in), readToken(in), readToken(in), readToken(in),
                    in.readLong(), in.readLong(), in.readLong(), in.readLong(),
                    in.readLong(), in.readLong());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid authenticated IPC envelope", invalid);
        }
    }

    private static void writeToken(DataOutputStream out, String token) throws IOException {
        byte[] bytes = Objects.requireNonNull(token, "token").getBytes(StandardCharsets.US_ASCII);
        if (bytes.length == 0 || bytes.length > MAX_TOKEN_BYTES) {
            throw new IOException("invalid IPC token length");
        }
        out.writeByte(bytes.length);
        out.write(bytes);
    }

    private static String readToken(DataInputStream in) throws IOException {
        int length = in.readUnsignedByte();
        if (length == 0 || length > MAX_TOKEN_BYTES) {
            throw new IOException("invalid IPC token length");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("truncated IPC token");
        for (byte b : bytes) {
            int c = b & 0xff;
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c == '-' || c == '_' || c == '.')) {
                throw new IOException("noncanonical IPC token");
            }
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static int remoteReceiptWireCode(IpcReceiptCorrelation.Stage stage) {
        requireRemoteReceiptStage(stage);
        return switch (stage) {
            case REMOTE_ACCEPTED -> RECEIPT_REMOTE_ACCEPTED;
            case HANDLER_COMMITTED -> RECEIPT_HANDLER_COMMITTED;
            case HANDLER_FAILED -> RECEIPT_HANDLER_FAILED;
            default -> throw new IllegalArgumentException(
                    "local-only/invalid peer receipt stage");
        };
    }

    private static IpcReceiptCorrelation.Stage remoteReceiptStage(int wireCode) {
        return switch (wireCode) {
            case RECEIPT_REMOTE_ACCEPTED -> IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED;
            case RECEIPT_HANDLER_COMMITTED -> IpcReceiptCorrelation.Stage.HANDLER_COMMITTED;
            case RECEIPT_HANDLER_FAILED -> IpcReceiptCorrelation.Stage.HANDLER_FAILED;
            default -> throw new IllegalArgumentException("invalid peer receipt wire code");
        };
    }

    private static void requireRemoteReceiptStage(IpcReceiptCorrelation.Stage stage) {
        Objects.requireNonNull(stage, "stage");
        if (stage != IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED
                && stage != IpcReceiptCorrelation.Stage.HANDLER_COMMITTED
                && stage != IpcReceiptCorrelation.Stage.HANDLER_FAILED) {
            throw new IllegalArgumentException("local-only/invalid peer receipt stage");
        }
    }

    private static byte[] appendMac(byte[] body, byte[] key) {
        byte[] tag = mac(body, key);
        byte[] frame = Arrays.copyOf(body, body.length + tag.length);
        System.arraycopy(tag, 0, frame, body.length, tag.length);
        return frame;
    }

    private static byte[] verifyAndStripMac(byte[] frame, byte[] key) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length < MAC_BYTES || frame.length > MAX_DATA_FRAME_BYTES) {
            throw new IOException("invalid IPC authenticated frame length");
        }
        byte[] body = Arrays.copyOf(frame, frame.length - MAC_BYTES);
        byte[] expected = mac(body, key);
        byte[] actual = Arrays.copyOfRange(frame, frame.length - MAC_BYTES, frame.length);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IOException("IPC frame authentication failed");
        }
        return body;
    }

    private static byte[] mac(byte[] body, byte[] key) {
        if (key == null || key.length < 32 || key.length > 128) {
            throw new IllegalArgumentException("IPC MAC key must be 32..128 bytes");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 unavailable", impossible);
        }
    }

    private static String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
