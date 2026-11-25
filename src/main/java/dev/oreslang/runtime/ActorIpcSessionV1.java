package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated same-host IPC session/framing contract.
 *
 * <p>This class is transport-only: it does not create a process, socket, pipe
 * or thread. A trusted launcher claims bootstrap material exactly once and
 * supplies it over an inherited bootstrap channel to the exact admitted
 * executable. DATA and CONTROL use independent MAC keys and frame sequences so
 * backpressure on message/output traffic cannot consume lifecycle sequencing.</p>
 */
public final class ActorIpcSessionV1 implements AutoCloseable {
    public static final int PROTOCOL_MAJOR = 1;
    public static final int PROTOCOL_MINOR = 0;
    public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    public static final int MAX_CONTROL_PAYLOAD_BYTES = 64 * 1024;

    private static final byte[] MAGIC = "ORESIPC1".getBytes(StandardCharsets.US_ASCII);
    private static final int SHA256_BYTES = 32;
    private static final int HMAC_BYTES = 32;
    private static final int HEADER_BYTES = 116;
    private static final int MIN_FRAME_BYTES = HEADER_BYTES + HMAC_BYTES;
    private static final int READY_PAYLOAD_BYTES = 32 * 6 + 16;
    private static final int LANE_OFFSET = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Lane {
        DATA(1),
        CONTROL(2);

        private final int code;
        Lane(int code) { this.code = code; }
        int code() { return code; }

        static Lane fromCode(int code) {
            return switch (code) {
                case 1 -> DATA;
                case 2 -> CONTROL;
                default -> throw new ProtocolException(
                        Reason.MALFORMED,
                        "unknown IPC lane " + code);
            };
        }
    }

    public enum FrameKind {
        READY(1),
        DATA(2),
        OUTPUT(3),
        COMPLETE(4),
        CONTROL(5),
        ERROR(6),
        CANCEL(7);

        private final int code;
        FrameKind(int code) { this.code = code; }
        int code() { return code; }

        static FrameKind fromCode(int code) {
            for (FrameKind kind : values()) if (kind.code == code) return kind;
            throw new ProtocolException(
                    Reason.MALFORMED,
                    "unknown IPC frame kind " + code);
        }
    }

    public enum Reason {
        MALFORMED,
        AUTHENTICATION,
        IDENTITY,
        ABI,
        SEQUENCE,
        STATE
    }

    public static final class ProtocolException extends SecurityException {
        private final Reason reason;

        private ProtocolException(Reason reason, String message) {
            super(message);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public Reason reason() { return reason; }
    }

    public static final class Frame {
        private final Lane lane;
        private final FrameKind kind;
        private final long frameSequence;
        private final long messageSequence;
        private final byte[] payload;

        private Frame(
                Lane lane,
                FrameKind kind,
                long frameSequence,
                long messageSequence,
                byte[] payload) {
            this.lane = lane;
            this.kind = kind;
            this.frameSequence = frameSequence;
            this.messageSequence = messageSequence;
            this.payload = payload.clone();
        }

        public Lane lane() { return lane; }
        public FrameKind kind() { return kind; }
        public long frameSequence() { return frameSequence; }
        public long messageSequence() { return messageSequence; }
        public byte[] payload() { return payload.clone(); }
    }

    /**
     * One-shot launcher secret. Package-private byte accessors deliberately
     * keep it out of guest/public API surfaces.
     */
    static final class BootstrapMaterial implements AutoCloseable {
        private final byte[] masterKey;
        private final byte[] challenge;
        private final UUID logicalActorId;
        private final UUID codeGenerationId;
        private final long incarnation;
        private final long routeEpoch;
        private final UUID sessionId;

        private BootstrapMaterial(
                byte[] masterKey,
                byte[] challenge,
                UUID logicalActorId,
                UUID codeGenerationId,
                long incarnation,
                long routeEpoch,
                UUID sessionId) {
            this.masterKey = masterKey.clone();
            this.challenge = challenge.clone();
            this.logicalActorId = logicalActorId;
            this.codeGenerationId =
                    Objects.requireNonNull(codeGenerationId, "codeGenerationId");
            this.incarnation = incarnation;
            this.routeEpoch = routeEpoch;
            this.sessionId = sessionId;
        }

        byte[] masterKey() { return masterKey.clone(); }
        byte[] challenge() { return challenge.clone(); }
        UUID logicalActorId() { return logicalActorId; }
        UUID codeGenerationId() { return codeGenerationId; }
        long incarnation() { return incarnation; }
        long routeEpoch() { return routeEpoch; }
        UUID sessionId() { return sessionId; }

        void destroy() {
            Arrays.fill(masterKey, (byte) 0);
            Arrays.fill(challenge, (byte) 0);
        }

        @Override
        public void close() {
            destroy();
        }
    }

    private enum State {
        AWAITING_READY,
        READY,
        POISONED,
        CLOSED
    }

    private final ActorArtifact<?> artifact;
    private final UUID logicalActorId;
    private final long incarnation;
    private final long routeEpoch;
    private final UUID sessionId;
    private final byte[] dataKey;
    private final byte[] controlKey;
    private final byte[] expectedReadyPayload;

    private byte[] bootstrapMasterKey;
    private byte[] bootstrapChallenge;
    private boolean bootstrapClaimed;
    private State state = State.AWAITING_READY;
    // Host-only opt-in queue, protected by the session monitor. Fixed capacity
    // allocated at trusted setup; no allocation is required to publish.
    private DataDelivery[] dataQueue;
    private ActorIpcCodecProfileV1 codecProfile;
    private int dataHead, dataCount;
    private long queuedBytes, maxQueuedBytes;
    private ActorDataDecodeQuotaV1 transientQuota, retainedQuota;
    private long nextWorkerDataSequence = 1L;
    private long nextWorkerControlSequence = 1L;
    private long nextSupervisorDataSequence = 1L;
    private long nextSupervisorControlSequence = 1L;

    private ActorIpcSessionV1(
            ActorArtifact<?> artifact,
            UUID logicalActorId,
            long incarnation,
            long routeEpoch,
            UUID sessionId,
            byte[] masterKey,
            byte[] challenge) {
        this.artifact = Objects.requireNonNull(artifact, "artifact");
        this.logicalActorId = Objects.requireNonNull(logicalActorId, "logicalActorId");
        if (incarnation <= 0) {
            throw new IllegalArgumentException("actor incarnation must be positive");
        }
        if (routeEpoch <= 0) {
            throw new IllegalArgumentException("route epoch must be positive");
        }
        this.incarnation = incarnation;
        this.routeEpoch = routeEpoch;
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.bootstrapMasterKey = masterKey.clone();
        this.bootstrapChallenge = challenge.clone();
        this.dataKey = deriveLaneKey(masterKey, "data");
        this.controlKey = deriveLaneKey(masterKey, "control");
        this.expectedReadyPayload = readyPayload(
                challenge, artifact, artifact.codeGenerationId());
    }

    static ActorIpcSessionV1 create(
            ActorArtifact<?> artifact,
            UUID logicalActorId,
            long incarnation,
            long routeEpoch) {
        byte[] master = randomBytes(32);
        byte[] challenge = randomBytes(32);
        try {
            return new ActorIpcSessionV1(
                    artifact,
                    logicalActorId,
                    incarnation,
                    routeEpoch,
                    randomUuid(),
                    master,
                    challenge);
        } finally {
            Arrays.fill(master, (byte) 0);
            Arrays.fill(challenge, (byte) 0);
        }
    }

    public UUID logicalActorId() { return logicalActorId; }
    public long incarnation() { return incarnation; }
    public long routeEpoch() { return routeEpoch; }
    public UUID sessionId() { return sessionId; }
    public UUID codeGenerationId() { return artifact.codeGenerationId(); }

    public synchronized boolean ready() {
        return state == State.READY;
    }

    public synchronized boolean poisoned() {
        return state == State.POISONED;
    }

    /**
     * Single-claim bootstrap transfer from the session to the trusted launcher.
     * After this method returns, the session retains no bootstrap master/challenge
     * copy. The trusted launcher owns the returned secret material and must close
     * it after binding exactly one child bootstrap channel.
     */
    synchronized BootstrapMaterial claimBootstrapForTrustedLauncher() {
        requireNotTerminal("claim IPC bootstrap material");
        if (bootstrapClaimed) {
            throw new IllegalStateException(
                    "IPC bootstrap material has already been claimed");
        }
        bootstrapClaimed = true;
        BootstrapMaterial material = new BootstrapMaterial(
                bootstrapMasterKey,
                bootstrapChallenge,
                logicalActorId,
                artifact.codeGenerationId(),
                incarnation,
                routeEpoch,
                sessionId);
        Arrays.fill(bootstrapMasterKey, (byte) 0);
        Arrays.fill(bootstrapChallenge, (byte) 0);
        return material;
    }

    /**
     * Validate one worker->supervisor frame. Any protocol/authentication
     * violation poisons the whole session and destroys host-side traffic keys.
     */
    public synchronized Frame acceptWorkerFrame(byte[] wire) {
        // After DATA binding this is the trusted physical CONTROL path.
        // Reject an apparent wrong lane or oversize before cloning/MAC work;
        // the untrusted lane byte grants no authority, it can only reject.
        if (dataQueue != null) {
            requireNotTerminal("accept IPC CONTROL");
            Objects.requireNonNull(wire, "wire");
            if (wire.length < MIN_FRAME_BYTES ||
                    wire.length > HEADER_BYTES + MAX_CONTROL_PAYLOAD_BYTES + HMAC_BYTES ||
                    (wire[LANE_OFFSET] & 0xff) != Lane.CONTROL.code()) {
                poison();
                throw new ProtocolException(Reason.STATE, "invalid frame on bound CONTROL transport");
            }
        }
        return acceptWorkerFrameInternal(wire, false);
    }

    private Frame acceptWorkerFrameInternal(byte[] wire, boolean requireData) {
        if (!bootstrapClaimed) {
            throw new IllegalStateException(
                    "IPC worker frame arrived before bootstrap material was claimed");
        }
        requireNotTerminal("accept IPC worker frame");
        wire = Objects.requireNonNull(wire, "wire");
        if (wire.length < MIN_FRAME_BYTES
                || wire.length > HEADER_BYTES + MAX_PAYLOAD_BYTES + HMAC_BYTES) {
            poison();
            throw new ProtocolException(
                    Reason.MALFORMED,
                    "IPC frame size is outside protocol bounds");
        }
        // Authenticate and parse one immutable entry snapshot. A transport
        // buffer owner must not be able to race identity/payload mutation
        // between MAC verification and decode.
        byte[] admittedWire = wire.clone();
        try {
            DecodedFrame decoded = decodeAndAuthenticate(admittedWire);
            validateCommonIdentity(decoded);
            if (requireData && (decoded.lane() != Lane.DATA || decoded.kind() != FrameKind.OUTPUT))
                throw new ProtocolException(Reason.STATE, "non-DATA frame on DATA transport");
            if (!requireData && dataQueue != null && decoded.lane() == Lane.DATA)
                throw new ProtocolException(Reason.STATE, "bound DATA requires queue admission");
            validateWorkerSequence(decoded);
            Frame accepted = new Frame(
                    decoded.lane(),
                    decoded.kind(),
                    decoded.frameSequence(),
                    decoded.messageSequence(),
                    decoded.payload());
            validateWorkerState(decoded);
            advanceWorkerSequence(decoded.lane());
            return accepted;
        } catch (ProtocolException failure) {
            poison();
            throw failure;
        } catch (RuntimeException failure) {
            poison();
            throw new ProtocolException(
                    Reason.MALFORMED,
                    "malformed IPC frame: " + failure.getMessage());
        }
    }

    /**
     * Opt-in trusted launcher admission for the physical DATA lane. A null
     * result means backpressure before authentication/sequence consumption;
     * retry the identical owned frame, never skip it. The caller must use a
     * tenant-owned quota shared by all sessions and separately reserve
     * retained-frame/mailbox bytes before calling. Not a guest entrypoint.
     *
     * CONTROL uses its independently admitted transport path; never choose
     * this API or the CONTROL path based solely on attacker-provided bytes.
     */
    synchronized Frame tryAcceptWorkerDataFrame(byte[] wire, ActorDataDecodeQuotaV1 quota) {
        if (dataQueue != null) throw new IllegalStateException("bound DATA requires queue admission");
        return tryAcceptWorkerDataFrameInternal(wire, quota);
    }

    private Frame tryAcceptWorkerDataFrameInternal(byte[] wire, ActorDataDecodeQuotaV1 quota) {
        Objects.requireNonNull(wire, "wire");
        Objects.requireNonNull(quota, "quota");
        // Bounded before the snapshot and all parser/MAC allocations.
        if (wire.length < MIN_FRAME_BYTES ||
                wire.length > HEADER_BYTES + MAX_PAYLOAD_BYTES + HMAC_BYTES) {
            synchronized (this) {
                requireNotTerminal("accept IPC worker DATA frame");
                poison();
            }
            throw new ProtocolException(Reason.MALFORMED, "IPC DATA frame size outside bounds");
        }
        // Conservative logical transient charge for snapshot, payload copies
        // and MAC work. Not an assertion of exact physical heap consumption.
        long charge = 8L * wire.length + 4096L;
        ActorDataDecodeQuotaV1.Lease lease = quota.tryReserve(charge);
        if (lease == null) return null;
        try (lease) {
            synchronized (this) {
                return acceptWorkerFrameInternal(wire, true);
            }
        }
    }

    /**
     * Bind once from a trusted tenant registry, before reading DATA. Separate
     * transient and retained accounts must be shared across tenant sessions.
     * This queue is a transport handoff, NOT an ActorRuntime mailbox.
     */
    synchronized void bindCodecProfile(ActorIpcCodecProfileV1 profile, int allowed, int supported) {
        requireNotTerminal("bind IPC codec profile");
        if (codecProfile != null || dataQueue != null || bootstrapClaimed)
            throw new IllegalStateException("codec profile must bind before bootstrap and queue");
        Objects.requireNonNull(profile, "codec profile").requireAdmitted(allowed, supported,
                artifact.wireCodecSha256(), artifact.messageAbiSha256());
        codecProfile = profile;
    }

    synchronized ActorIpcCodecProfileV1 codecProfile() {
        return codecProfile;
    }

    synchronized void bindDataQueue(int slots, long bytes,
            ActorDataDecodeQuotaV1 transientBudget, ActorDataDecodeQuotaV1 retainedBudget) {
        requireNotTerminal("bind IPC DATA queue");
        if (dataQueue != null || nextWorkerDataSequence != 1L || slots <= 0 || slots > 4096 || bytes <= 0 ||
                bytes > 16L * MAX_PAYLOAD_BYTES || transientBudget == null ||
                retainedBudget == null || transientBudget == retainedBudget)
            throw new IllegalArgumentException("invalid DATA queue binding");
        dataQueue = new DataDelivery[slots];
        maxQueuedBytes = bytes;
        transientQuota = transientBudget;
        retainedQuota = retainedBudget;
    }

    // Retained credit follows a delivery after it leaves the transport queue.
    // Copying payload needs independent receiver-heap accounting.
    static final class DataDelivery implements AutoCloseable {
        private final ActorDataDecodeQuotaV1.Lease credit;
        private final int charge;
        private final ActorIpcCodecProfileV1 codecProfile;
        private Frame frame;
        private DataDelivery(ActorDataDecodeQuotaV1.Lease credit, int charge, ActorIpcCodecProfileV1 codecProfile) {
            this.credit = credit; this.charge = charge; this.codecProfile = codecProfile;
        }
        ActorIpcCodecProfileV1 codecProfile() { return codecProfile; }
        synchronized long messageSequence() {
            if (frame == null) throw new IllegalStateException("closed DATA delivery");
            return frame.messageSequence();
        }
        synchronized byte[] copyPayload() {
            if (frame == null) throw new IllegalStateException("closed DATA delivery");
            return frame.payload();
        }
        @Override public synchronized void close() {
            if (frame != null) Arrays.fill(frame.payload, (byte)0);
            frame = null;
            credit.close();
        }
    }

    /**
     * Trusted physical DATA lane. False is pre-authentication backpressure;
     * caller retries the identical owned frame, in order. No guest authority.
     * Session monitor fences close/poison and serializes queue publication.
     */
    synchronized boolean tryQueueWorkerData(byte[] wire) {
        requireReady("queue IPC DATA");
        if (dataQueue == null) throw new IllegalStateException("DATA queue not bound");
        Objects.requireNonNull(wire, "wire");
        if (wire.length < MIN_FRAME_BYTES ||
                wire.length > HEADER_BYTES + MAX_PAYLOAD_BYTES + HMAC_BYTES) {
            poison();
            throw new ProtocolException(Reason.MALFORMED, "IPC DATA frame size outside bounds");
        }
        // Charge the whole wire length, a conservative retained frame bound.
        int charge = wire.length;
        if (dataCount == dataQueue.length || charge > maxQueuedBytes - queuedBytes) return false;
        var credit = retainedQuota.tryReserve(charge);
        if (credit == null) return false;
        DataDelivery delivery = null;
        boolean published = false;
        try {
            // Allocate the delivery before sequence consumption.
            delivery = new DataDelivery(credit, charge, codecProfile);
            Frame frame = tryAcceptWorkerDataFrameInternal(wire, transientQuota);
            if (frame == null) return false;
            delivery.frame = frame;
            int tail = (dataHead + dataCount) % dataQueue.length;
            dataQueue[tail] = delivery;
            dataCount++;
            queuedBytes += charge;
            published = true;
            return true;
        } finally {
            if (!published) {
                if (delivery != null) delivery.close(); else credit.close();
            }
        }
    }

    // Trusted handoff; downstream must revalidate active actor route/generation
    // at its own mailbox lock. A dequeued delivery remains tenant-charged.
    synchronized DataDelivery pollWorkerData() {
        requireReady("poll IPC DATA");
        if (dataQueue == null || dataCount == 0) return null;
        DataDelivery delivery = dataQueue[dataHead];
        dataQueue[dataHead] = null;
        dataHead = (dataHead + 1) % dataQueue.length;
        dataCount--;
        queuedBytes -= delivery.charge;
        return delivery;
    }

    private void clearDataQueue() {
        if (dataQueue == null) return;
        for (int i = 0; i < dataQueue.length; i++) {
            if (dataQueue[i] != null) {
                dataQueue[i].close();
                dataQueue[i] = null;
            }
        }
        dataHead = 0;
        dataCount = 0;
        queuedBytes = 0;
    }

    /**
     * Encode a supervisor DATA frame after READY. This is a pure byte-frame
     * producer; a later transport layer owns actual write/backpressure.
     */
    synchronized byte[] encodeSupervisorData(long messageSequence, byte[] payload) {
        requireReady("encode supervisor DATA frame");
        if (messageSequence <= 0) {
            throw new IllegalArgumentException("DATA message sequence must be positive");
        }
        long sequence = nextSupervisorDataSequence;
        long next = increment(sequence, "supervisor DATA");
        byte[] frame = encodeFrame(
                dataKey,
                Lane.DATA,
                FrameKind.DATA,
                sequence,
                messageSequence,
                payload);
        nextSupervisorDataSequence = next;
        return frame;
    }

    synchronized byte[] encodeSupervisorControl(
            FrameKind kind,
            long messageSequence,
            byte[] payload) {
        requireReady("encode supervisor CONTROL frame");
        if (kind != FrameKind.CONTROL && kind != FrameKind.CANCEL) {
            throw new IllegalArgumentException(
                    "supervisor CONTROL lane only admits CONTROL/CANCEL frames");
        }
        if (messageSequence < 0) {
            throw new IllegalArgumentException("CONTROL message sequence cannot be negative");
        }
        payload = Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_CONTROL_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "IPC CONTROL payload exceeds maximum size");
        }
        long sequence = nextSupervisorControlSequence;
        long next = increment(sequence, "supervisor CONTROL");
        byte[] frame = encodeFrame(
                controlKey,
                Lane.CONTROL,
                kind,
                sequence,
                messageSequence,
                payload);
        nextSupervisorControlSequence = next;
        return frame;
    }

    /**
     * Worker-side reference encoder used by native parity tests and future
     * launcher conformance. It intentionally requires worker artifact metadata
     * rather than copying host expected commitments into BootstrapMaterial.
     */
    static byte[] encodeWorkerFrame(
            BootstrapMaterial bootstrap,
            ActorArtifact<?> workerArtifact,
            Lane lane,
            FrameKind kind,
            long frameSequence,
            long messageSequence,
            byte[] payload) {
        Objects.requireNonNull(bootstrap, "bootstrap");
        Objects.requireNonNull(workerArtifact, "workerArtifact");
        byte[] master = bootstrap.masterKey();
        byte[] challenge = null;
        byte[] key;
        try {
            key = deriveLaneKey(
                    master,
                    lane == Lane.DATA ? "data" : "control");
        } finally {
            Arrays.fill(master, (byte) 0);
        }
        try {
            byte[] actualPayload;
            if (kind == FrameKind.READY) {
                challenge = bootstrap.challenge();
                actualPayload = readyPayload(
                        challenge,
                        workerArtifact,
                        bootstrap.codeGenerationId());
            } else {
                actualPayload = Objects.requireNonNull(payload, "payload");
            }
            return encodeFrame(
                    key,
                    lane,
                    kind,
                    frameSequence,
                    messageSequence,
                    bootstrap.logicalActorId(),
                    bootstrap.incarnation(),
                    bootstrap.routeEpoch(),
                    bootstrap.sessionId(),
                    workerArtifact.messageAbiSha256(),
                    actualPayload);
        } finally {
            Arrays.fill(key, (byte) 0);
            if (challenge != null) Arrays.fill(challenge, (byte) 0);
        }
    }

    private DecodedFrame decodeAndAuthenticate(byte[] wire) {
        Lane lane = Lane.fromCode(Byte.toUnsignedInt(wire[LANE_OFFSET]));
        byte[] key = lane == Lane.DATA ? dataKey : controlKey;
        int bodyLength = wire.length - HMAC_BYTES;
        byte[] actualTag = Arrays.copyOfRange(wire, bodyLength, wire.length);
        byte[] expectedTag = hmac(key, wire, 0, bodyLength);
        if (!MessageDigest.isEqual(expectedTag, actualTag)) {
            throw new ProtocolException(
                    Reason.AUTHENTICATION,
                    "IPC frame authentication tag mismatch");
        }

        ByteBuffer in = ByteBuffer.wrap(wire, 0, bodyLength)
                .order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        in.get(magic);
        if (!Arrays.equals(MAGIC, magic)) {
            throw new ProtocolException(Reason.MALFORMED, "IPC magic mismatch");
        }
        int major = Short.toUnsignedInt(in.getShort());
        int minor = Short.toUnsignedInt(in.getShort());
        if (major != PROTOCOL_MAJOR || minor != PROTOCOL_MINOR) {
            throw new ProtocolException(
                    Reason.MALFORMED,
                    "unsupported IPC protocol version " + major + "." + minor);
        }
        Lane parsedLane = Lane.fromCode(Byte.toUnsignedInt(in.get()));
        FrameKind kind = FrameKind.fromCode(Byte.toUnsignedInt(in.get()));
        if (in.getShort() != 0) {
            throw new ProtocolException(
                    Reason.MALFORMED,
                    "reserved IPC frame bits must be zero");
        }
        UUID actorId = readUuid(in);
        long frameIncarnation = in.getLong();
        long frameRouteEpoch = in.getLong();
        UUID frameSessionId = readUuid(in);
        long frameSequence = in.getLong();
        long messageSequence = in.getLong();
        byte[] messageAbi = new byte[SHA256_BYTES];
        in.get(messageAbi);
        int payloadLength = in.getInt();
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES
                || payloadLength != in.remaining()) {
            throw new ProtocolException(
                    Reason.MALFORMED,
                    "IPC payload length/trailing-byte mismatch");
        }
        if (parsedLane == Lane.CONTROL
                && payloadLength > MAX_CONTROL_PAYLOAD_BYTES) {
            throw new ProtocolException(
                    Reason.MALFORMED,
                    "IPC CONTROL payload exceeds maximum size");
        }
        byte[] payload = new byte[payloadLength];
        in.get(payload);
        if (parsedLane != lane) {
            throw new ProtocolException(
                    Reason.MALFORMED,
                    "IPC lane changed during authenticated decode");
        }
        return new DecodedFrame(
                lane,
                kind,
                actorId,
                frameIncarnation,
                frameRouteEpoch,
                frameSessionId,
                frameSequence,
                messageSequence,
                messageAbi,
                payload);
    }

    private void validateCommonIdentity(DecodedFrame frame) {
        if (!logicalActorId.equals(frame.logicalActorId())) {
            throw new ProtocolException(
                    Reason.IDENTITY,
                    "IPC logical actor identity mismatch");
        }
        if (frame.incarnation() != incarnation) {
            throw new ProtocolException(
                    Reason.IDENTITY,
                    "IPC actor incarnation mismatch");
        }
        if (frame.routeEpoch() != routeEpoch) {
            throw new ProtocolException(
                    Reason.IDENTITY,
                    "IPC route epoch mismatch");
        }
        if (!sessionId.equals(frame.sessionId())) {
            throw new ProtocolException(
                    Reason.IDENTITY,
                    "IPC transport session mismatch");
        }
        if (!MessageDigest.isEqual(
                digestBytes(artifact.messageAbiSha256()),
                frame.messageAbiSha256())) {
            throw new ProtocolException(
                    Reason.ABI,
                    "IPC message ABI digest mismatch");
        }
    }

    private void validateWorkerSequence(DecodedFrame frame) {
        long expected = frame.lane() == Lane.DATA
                ? nextWorkerDataSequence
                : nextWorkerControlSequence;
        if (frame.frameSequence() != expected) {
            throw new ProtocolException(
                    Reason.SEQUENCE,
                    "IPC " + frame.lane()
                            + " frame sequence mismatch: expected "
                            + expected + " but got " + frame.frameSequence());
        }
        if (frame.messageSequence() < 0) {
            throw new ProtocolException(
                    Reason.SEQUENCE,
                    "IPC message sequence cannot be negative");
        }
    }

    private void validateWorkerState(DecodedFrame frame) {
        if (state == State.AWAITING_READY) {
            if (frame.lane() != Lane.CONTROL
                    || frame.kind() != FrameKind.READY
                    || frame.messageSequence() != 0
                    || !MessageDigest.isEqual(
                            expectedReadyPayload,
                            frame.payload())) {
                throw new ProtocolException(
                        Reason.STATE,
                        "first authenticated worker frame must be exact CONTROL/READY");
            }
            Arrays.fill(expectedReadyPayload, (byte) 0);
            state = State.READY;
            return;
        }
        if (state != State.READY) {
            throw new ProtocolException(
                    Reason.STATE,
                    "IPC session is not ready");
        }
        if (frame.kind() == FrameKind.READY) {
            throw new ProtocolException(
                    Reason.STATE,
                    "duplicate IPC READY frame");
        }

        if (frame.lane() == Lane.DATA) {
            if (frame.kind() != FrameKind.OUTPUT
                    || frame.messageSequence() <= 0) {
                throw new ProtocolException(
                        Reason.STATE,
                        "worker DATA lane admits only message-bound OUTPUT frames");
            }
            return;
        }

        switch (frame.kind()) {
            case COMPLETE -> {
                if (frame.messageSequence() <= 0) {
                    throw new ProtocolException(
                            Reason.STATE,
                            "COMPLETE must identify a positive message sequence");
                }
                if (frame.payload().length != 0) {
                    throw new ProtocolException(
                            Reason.STATE,
                            "COMPLETE frame must not carry a payload");
                }
            }
            case CONTROL -> {
                if (frame.messageSequence() != 0) {
                    throw new ProtocolException(
                            Reason.STATE,
                            "generic CONTROL frame must use message sequence zero");
                }
            }
            case ERROR -> {
                // Zero means actor/session-level failure; positive means one
                // admitted logical message. Both are intentional.
            }
            default -> throw new ProtocolException(
                    Reason.STATE,
                    "frame kind " + frame.kind()
                            + " is not valid on worker CONTROL lane");
        }
    }

    private void advanceWorkerSequence(Lane lane) {
        if (lane == Lane.DATA) {
            nextWorkerDataSequence =
                    increment(nextWorkerDataSequence, "worker DATA");
        } else {
            nextWorkerControlSequence =
                    increment(nextWorkerControlSequence, "worker CONTROL");
        }
    }

    private synchronized void requireReady(String operation) {
        if (state != State.READY) {
            throw new IllegalStateException(
                    operation + " requires READY IPC session; current state=" + state);
        }
    }

    private synchronized void requireNotTerminal(String operation) {
        if (state == State.POISONED || state == State.CLOSED) {
            throw new IllegalStateException(
                    operation + " rejected for terminal IPC session state " + state);
        }
    }

    private void poison() {
        if (state == State.CLOSED || state == State.POISONED) return;
        state = State.POISONED;
        clearDataQueue();
        destroySecrets();
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) return;
        state = State.CLOSED;
        clearDataQueue();
        destroySecrets();
    }

    private void destroySecrets() {
        Arrays.fill(dataKey, (byte) 0);
        Arrays.fill(controlKey, (byte) 0);
        Arrays.fill(bootstrapMasterKey, (byte) 0);
        Arrays.fill(bootstrapChallenge, (byte) 0);
        Arrays.fill(expectedReadyPayload, (byte) 0);
    }

    private byte[] encodeFrame(
            byte[] laneKey,
            Lane lane,
            FrameKind kind,
            long frameSequence,
            long messageSequence,
            byte[] payload) {
        return encodeFrame(
                laneKey,
                lane,
                kind,
                frameSequence,
                messageSequence,
                logicalActorId,
                incarnation,
                routeEpoch,
                sessionId,
                artifact.messageAbiSha256(),
                payload);
    }

    private static byte[] encodeFrame(
            byte[] laneKey,
            Lane lane,
            FrameKind kind,
            long frameSequence,
            long messageSequence,
            UUID logicalActorId,
            long incarnation,
            long routeEpoch,
            UUID sessionId,
            String messageAbiSha256,
            byte[] payload) {
        Objects.requireNonNull(laneKey, "lane key");
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(logicalActorId, "logical actor id");
        Objects.requireNonNull(sessionId, "session id");
        payload = Objects.requireNonNull(payload, "payload");
        if (laneKey.length != 32) {
            throw new IllegalArgumentException("IPC lane key must be 32 bytes");
        }
        if (frameSequence <= 0) {
            throw new IllegalArgumentException("IPC frame sequence must be positive");
        }
        if (messageSequence < 0) {
            throw new IllegalArgumentException("IPC message sequence cannot be negative");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("IPC frame payload exceeds maximum size");
        }
        byte[] admittedPayload = payload.clone();

        ByteBuffer out = ByteBuffer.allocate(
                HEADER_BYTES + admittedPayload.length + HMAC_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        out.put(MAGIC);
        out.putShort((short) PROTOCOL_MAJOR);
        out.putShort((short) PROTOCOL_MINOR);
        out.put((byte) lane.code());
        out.put((byte) kind.code());
        out.putShort((short) 0);
        putUuid(out, logicalActorId);
        out.putLong(incarnation);
        out.putLong(routeEpoch);
        putUuid(out, sessionId);
        out.putLong(frameSequence);
        out.putLong(messageSequence);
        out.put(digestBytes(messageAbiSha256));
        out.putInt(admittedPayload.length);
        out.put(admittedPayload);

        int bodyLength = out.position();
        byte[] body = out.array();
        out.put(hmac(laneKey, body, 0, bodyLength));
        return out.array();
    }

    private static byte[] readyPayload(
            byte[] challenge,
            ActorArtifact<?> artifact,
            UUID codeGenerationId) {
        Objects.requireNonNull(challenge, "challenge");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(codeGenerationId, "codeGenerationId");
        if (challenge.length != 32) {
            throw new IllegalArgumentException("IPC READY challenge must be 32 bytes");
        }
        ByteBuffer out = ByteBuffer.allocate(READY_PAYLOAD_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        /*
         * READY contains only commitments the running worker can independently
         * know or measure without trusting host expectations. In particular:
         *
         * - manifestSha256 is excluded because the signed manifest commits the
         *   final artifact SHA; embedding that manifest digest back into the
         *   same executable would create a circular fixed-point requirement.
         * - workerPolicySha256 is excluded because OS/sandbox policy is
         *   supervisor-enforced launch state, not a fact the guest can prove.
         *
         * The launcher executes a sealed exact object, so the worker can hash
         * that object directly for artifactSha256. Compiler-owned ABI/schema/
         * codec/type commitments are intrinsic build metadata, while the route
         * code-generation UUID is supplied in the one-shot bootstrap.
         */
        out.put(challenge);
        out.put(digestBytes(artifact.artifactSha256()));
        out.put(digestBytes(artifact.messageAbiSha256()));
        out.put(digestBytes(artifact.stateSchemaSha256()));
        out.put(digestBytes(artifact.wireCodecSha256()));
        out.put(sha256(artifact.actorTypeName().getBytes(StandardCharsets.UTF_8)));
        putUuid(out, codeGenerationId);
        return out.array();
    }

    private static byte[] deriveLaneKey(byte[] master, String lane) {
        return hmac(
                master,
                ("ORESIPC1:" + lane).getBytes(StandardCharsets.US_ASCII),
                0,
                ("ORESIPC1:" + lane).length());
    }

    private static byte[] hmac(
            byte[] key,
            byte[] bytes,
            int offset,
            int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(bytes, offset, length);
            return mac.doFinal();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 unavailable", impossible);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static byte[] digestBytes(String lowerHex) {
        if (lowerHex == null || lowerHex.length() != 64) {
            throw new IllegalArgumentException("digest must be 64 lowercase hex characters");
        }
        byte[] result = new byte[32];
        for (int i = 0; i < result.length; i++) {
            int hi = Character.digit(lowerHex.charAt(i * 2), 16);
            int lo = Character.digit(lowerHex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0
                    || Character.isUpperCase(lowerHex.charAt(i * 2))
                    || Character.isUpperCase(lowerHex.charAt(i * 2 + 1))) {
                throw new IllegalArgumentException(
                        "digest must be lowercase hexadecimal");
            }
            result[i] = (byte) ((hi << 4) | lo);
        }
        return result;
    }

    private static long increment(long current, String label) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException overflow) {
            throw new ProtocolException(
                    Reason.SEQUENCE,
                    label + " frame sequence exhausted");
        }
    }

    private static UUID randomUuid() {
        byte[] bytes = randomBytes(16);
        try {
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            long most = b.getLong();
            long least = b.getLong();
            // RFC 4122 v4 marker bits make diagnostics/tools friendlier; all
            // entropy still originates from SecureRandom.
            most = (most & 0xffffffffffff0fffL) | 0x0000000000004000L;
            least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
            return new UUID(most, least);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static void putUuid(ByteBuffer out, UUID value) {
        out.putLong(value.getMostSignificantBits());
        out.putLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuffer in) {
        return new UUID(in.getLong(), in.getLong());
    }

    private record DecodedFrame(
            Lane lane,
            FrameKind kind,
            UUID logicalActorId,
            long incarnation,
            long routeEpoch,
            UUID sessionId,
            long frameSequence,
            long messageSequence,
            byte[] messageAbiSha256,
            byte[] payload) {
        private DecodedFrame {
            messageAbiSha256 = messageAbiSha256.clone();
            payload = payload.clone();
        }

        public byte[] messageAbiSha256() { return messageAbiSha256.clone(); }

        public byte[] payload() { return payload.clone(); }
    }
}
