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
    private static final int VERSION = 1;
    private static final int TYPE_DATA = 1;
    private static final int TYPE_RECEIPT = 2;
    private static final int MAC_BYTES = 32;
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TOKEN_BYTES = 96;

    private final Socket socket;
    private final IpcReceiptCompletionBridge receipts;
    private final IpcTransportSessionLease sessionLease;
    private final IpcReceiptCorrelation.Envelope trustedEpoch;
    private final long physicalGeneration;
    private final byte[] macKey;
    private final InputStream input;
    private final OutputStream output;
    private final Object readGate = new Object();
    private final Object writeGate = new Object();

    IpcAuthenticatedSocketTransport(
            Socket socket,
            IpcReceiptCompletionBridge receipts,
            IpcReceiptCorrelation.Envelope trustedEpoch,
            long physicalGeneration,
            int maxOutstandingWrites,
            byte[] macKey,
            IpcTransportSessionLease.CleanupScheduler cleanupScheduler) throws IOException {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.trustedEpoch = Objects.requireNonNull(trustedEpoch, "trustedEpoch");
        if (physicalGeneration <= 0) {
            throw new IllegalArgumentException("physical generation must be positive");
        }
        if (macKey == null || macKey.length < 32 || macKey.length > 128) {
            throw new IllegalArgumentException("IPC MAC key must be 32..128 bytes");
        }
        this.macKey = macKey.clone();
        this.physicalGeneration = physicalGeneration;
        this.input = socket.getInputStream();
        this.output = socket.getOutputStream();
        this.sessionLease = new IpcTransportSessionLease(
                trustedEpoch,
                physicalGeneration,
                maxOutstandingWrites,
                receipts,
                socket::close,
                Objects.requireNonNull(cleanupScheduler, "cleanupScheduler"));
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

        CompletionStage<IpcReceiptCorrelation.Stage> outcome = receipts.admit(message);
        AtomicBoolean physicalWriterEntered = new AtomicBoolean();
        Throwable failure = null;
        try {
            sessionLease.writeFrame(physicalGeneration, message, ignored -> {
                // writeFrame invokes this callback only AFTER commitDispatch.
                physicalWriterEntered.set(true);
                byte[] frame = encodeDataFrame(message, body, physicalGeneration, macKey);
                synchronized (writeGate) {
                    writeWireFrame(output, frame);
                }
            });
        } catch (IOException | RuntimeException | Error sendFailure) {
            failure = sendFailure;
            if (!physicalWriterEntered.get()) {
                // The lease rejected admission before dispatch commitment, so
                // no physical write was possible and never-sent is provable.
                try {
                    receipts.rejectBeforeRemote(message);
                } catch (RuntimeException | Error cleanupFailure) {
                    sendFailure.addSuppressed(cleanupFailure);
                }
            }
            // Once the physical writer callback has been entered, the dispatch
            // commitment is irreversible. IpcTransportSessionLease requests
            // close on writer failure and the outcome becomes unknown.
        }
        return new SendSubmission(outcome, failure);
    }

    /**
     * Read one MAC-authenticated peer receipt and apply it to the host ledger.
     * Local-only verdicts are rejected both by codec policy and the ledger.
     */
    IpcReceiptCorrelation.Stage readVerifiedReceipt() throws IOException {
        try {
            final byte[] frame;
            synchronized (readGate) {
                frame = readWireFrame(input);
            }
            DecodedReceipt receipt = decodeReceiptFrame(frame, macKey);
            if (receipt.physicalGeneration() != physicalGeneration) {
                throw new IOException("stale IPC physical transport generation");
            }
            requireTrustedEpoch(receipt.envelope());
            receipts.acceptVerified(receipt.envelope(), receipt.stage());
            return receipt.stage();
        } catch (IOException | RuntimeException | Error failure) {
            // Authentication, framing, EOF and state-machine violations are
            // fatal to this physical session. Never continue parsing a
            // desynchronized or hostile stream after an invalid receipt.
            requestClose();
            throw failure;
        }
    }

    void requestClose() {
        // First fence new send admission. Then actively close the Java Socket
        // to interrupt a blocked physical read/write. The session lease still
        // defers receipt retirement and reusable-generation status until all
        // admitted write callbacks unwind and the authoritative close worker
        // confirms physical closure.
        sessionLease.requestClose();
        try {
            socket.close();
        } catch (IOException ignoredAbortFailure) {
            // Best-effort abort only. IpcTransportSessionLease owns the
            // authoritative close confirmation/quarantine decision and will
            // retry/record failure on its cleanup worker.
        }
    }

    /**
     * Explicit host-supervisor recovery after bounded cleanup scheduling or
     * physical-close confirmation failure. Never automatic and never guest
     * callable; a quarantined generation cannot reopen for new writes.
     */
    void retryQuarantinedClose() {
        sessionLease.retryQuarantinedClose();
    }

    IpcTransportSessionLease.Snapshot snapshot() {
        return sessionLease.snapshot();
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

    static byte[] encodeDataFrame(
            IpcReceiptCorrelation.Envelope envelope,
            byte[] payload,
            long physicalGeneration,
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
            writeHeader(out, TYPE_DATA, physicalGeneration);
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
            byte[] key) throws IOException {
        Objects.requireNonNull(expectedEpoch, "expectedEpoch");
        if (expectedPhysicalGeneration <= 0) {
            throw new IllegalArgumentException("physical generation must be positive");
        }
        byte[] authenticated = verifyAndStripMac(frame, key);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(authenticated))) {
            long physicalGeneration = requireHeader(in, TYPE_DATA);
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
            return new DecodedData(envelope, physicalGeneration, payload);
        }
    }

    static byte[] encodeReceiptFrame(
            IpcReceiptCorrelation.Envelope envelope,
            long physicalGeneration,
            IpcReceiptCorrelation.Stage stage,
            byte[] key) throws IOException {
        Objects.requireNonNull(envelope, "envelope");
        requireRemoteReceiptStage(stage);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            writeHeader(out, TYPE_RECEIPT, physicalGeneration);
            writeEnvelope(out, envelope);
            out.writeByte(stage.ordinal());
        }
        return appendMac(raw.toByteArray(), key);
    }

    private static DecodedReceipt decodeReceiptFrame(byte[] frame, byte[] key) throws IOException {
        byte[] authenticated = verifyAndStripMac(frame, key);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(authenticated))) {
            long physicalGeneration = requireHeader(in, TYPE_RECEIPT);
            IpcReceiptCorrelation.Envelope envelope = readEnvelope(in);
            int ordinal = in.readUnsignedByte();
            if (ordinal >= IpcReceiptCorrelation.Stage.values().length || in.available() != 0) {
                throw new IOException("invalid IPC receipt stage/frame length");
            }
            IpcReceiptCorrelation.Stage stage = IpcReceiptCorrelation.Stage.values()[ordinal];
            try {
                requireRemoteReceiptStage(stage);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("peer asserted local-only IPC verdict", invalid);
            }
            return new DecodedReceipt(envelope, physicalGeneration, stage);
        }
    }

    static void writeWireFrame(OutputStream output, byte[] authenticatedFrame) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(authenticatedFrame, "authenticatedFrame");
        if (authenticatedFrame.length < MAC_BYTES || authenticatedFrame.length > MAX_FRAME_BYTES) {
            throw new IOException("invalid bounded IPC wire frame");
        }
        DataOutputStream out = new DataOutputStream(output);
        out.writeInt(authenticatedFrame.length);
        out.write(authenticatedFrame);
        out.flush();
    }

    static byte[] readWireFrame(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream in = new DataInputStream(input);
        final int length;
        try {
            length = in.readInt();
        } catch (EOFException eof) {
            throw eof;
        }
        if (length < MAC_BYTES || length > MAX_FRAME_BYTES) {
            throw new IOException("invalid bounded IPC wire frame length");
        }
        byte[] frame = in.readNBytes(length);
        if (frame.length != length) {
            throw new EOFException("truncated IPC wire frame");
        }
        return frame;
    }

    private static void writeHeader(
            DataOutputStream out, int type, long physicalGeneration) throws IOException {
        if (physicalGeneration <= 0) {
            throw new IllegalArgumentException("physical generation must be positive");
        }
        out.writeInt(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(type);
        out.writeLong(physicalGeneration);
    }

    private static long requireHeader(DataInputStream in, int type) throws IOException {
        if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION
                || in.readUnsignedByte() != type) {
            throw new IOException("invalid IPC frame header");
        }
        long physicalGeneration = in.readLong();
        if (physicalGeneration <= 0) {
            throw new IOException("invalid IPC physical transport generation");
        }
        return physicalGeneration;
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
        if (frame.length < MAC_BYTES || frame.length > MAX_FRAME_BYTES) {
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
