package dev.oreslang.runtime;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * HKDF-SHA256 session derivation for one authenticated IPC actor generation.
 *
 * <p>The bootstrap secret is supervisor-generated and must be transferred to
 * the child over a trusted inherited bootstrap channel. It is not an
 * environment variable, command-line argument or guest-visible capability.</p>
 *
 * <p>Each lane/direction receives an independent key and session identifier.
 * Route epoch, incarnation, artifact receipt and compiler-owned ABI/state/policy
 * digests are part of the transcript, so a prior process generation cannot
 * reuse a valid session after replacement.</p>
 */
public final class ActorIpcSessionV1 {
    private ActorIpcSessionV1() { }

    private static final byte[] MAGIC =
            "ORES-IPC-SESSION-V1\0".getBytes(StandardCharsets.US_ASCII);
    private static final int SECRET_BYTES = 32;
    private static final int NONCE_BYTES = 32;
    private static final int DIGEST_BYTES = 32;
    private static final int KEY_BYTES = 32;
    private static final int SESSION_ID_BYTES = 16;
    private static final int MAX_TEXT_BYTES = 16 * 1024;

    public record Context(
            UUID logicalActorId,
            long incarnation,
            long routeEpoch,
            String actorTypeName,
            byte[] signedArtifactReceiptSha256,
            byte[] artifactSha256,
            byte[] messageAbiSha256,
            byte[] stateSchemaSha256,
            byte[] capabilityPolicySha256) {
        public Context {
            logicalActorId = Objects.requireNonNull(logicalActorId, "logicalActorId");
            if (incarnation <= 0) {
                throw new IllegalArgumentException("incarnation must be positive");
            }
            if (routeEpoch <= 0) {
                throw new IllegalArgumentException("routeEpoch must be positive");
            }
            actorTypeName = requireText(actorTypeName, "actorTypeName");
            signedArtifactReceiptSha256 =
                    requireBytes(
                            signedArtifactReceiptSha256,
                            DIGEST_BYTES,
                            "signedArtifactReceiptSha256");
            artifactSha256 =
                    requireBytes(artifactSha256, DIGEST_BYTES, "artifactSha256");
            messageAbiSha256 =
                    requireBytes(messageAbiSha256, DIGEST_BYTES, "messageAbiSha256");
            stateSchemaSha256 =
                    requireBytes(stateSchemaSha256, DIGEST_BYTES, "stateSchemaSha256");
            capabilityPolicySha256 =
                    requireBytes(
                            capabilityPolicySha256,
                            DIGEST_BYTES,
                            "capabilityPolicySha256");
        }

        @Override public byte[] signedArtifactReceiptSha256() {
            return signedArtifactReceiptSha256.clone();
        }
        @Override public byte[] artifactSha256() { return artifactSha256.clone(); }
        @Override public byte[] messageAbiSha256() { return messageAbiSha256.clone(); }
        @Override public byte[] stateSchemaSha256() { return stateSchemaSha256.clone(); }
        @Override public byte[] capabilityPolicySha256() {
            return capabilityPolicySha256.clone();
        }
    }

    public record ChannelSecrets(
            ActorIpcFrameV1.SessionBinding binding,
            byte[] authenticationKey) {
        public ChannelSecrets {
            binding = Objects.requireNonNull(binding, "binding");
            authenticationKey =
                    requireNonZeroBytes(
                            authenticationKey,
                            KEY_BYTES,
                            "authenticationKey");
        }

        @Override public byte[] authenticationKey() {
            return authenticationKey.clone();
        }

        public ActorIpcFrameV1.Encoder encoder() {
            return new ActorIpcFrameV1.Encoder(
                    binding,
                    authenticationKey);
        }

        public ActorIpcFrameV1.Decoder decoder() {
            return new ActorIpcFrameV1.Decoder(
                    binding,
                    authenticationKey);
        }
    }

    public record EstablishedSession(
            ChannelSecrets hostToWorkerData,
            ChannelSecrets workerToHostData,
            ChannelSecrets hostToWorkerControl,
            ChannelSecrets workerToHostControl,
            byte[] hostTranscriptProof,
            byte[] workerTranscriptProof,
            byte[] transcriptSha256) {
        public EstablishedSession {
            hostToWorkerData =
                    Objects.requireNonNull(hostToWorkerData, "hostToWorkerData");
            workerToHostData =
                    Objects.requireNonNull(workerToHostData, "workerToHostData");
            hostToWorkerControl =
                    Objects.requireNonNull(hostToWorkerControl, "hostToWorkerControl");
            workerToHostControl =
                    Objects.requireNonNull(workerToHostControl, "workerToHostControl");
            hostTranscriptProof =
                    requireBytes(
                            hostTranscriptProof,
                            DIGEST_BYTES,
                            "hostTranscriptProof");
            workerTranscriptProof =
                    requireBytes(
                            workerTranscriptProof,
                            DIGEST_BYTES,
                            "workerTranscriptProof");
            transcriptSha256 =
                    requireBytes(
                            transcriptSha256,
                            DIGEST_BYTES,
                            "transcriptSha256");
        }

        @Override public byte[] hostTranscriptProof() {
            return hostTranscriptProof.clone();
        }
        @Override public byte[] workerTranscriptProof() {
            return workerTranscriptProof.clone();
        }
        @Override public byte[] transcriptSha256() {
            return transcriptSha256.clone();
        }
    }

    public static EstablishedSession establish(
            Context context,
            byte[] bootstrapSecret,
            byte[] hostNonce,
            byte[] workerNonce) {
        Objects.requireNonNull(context, "context");
        byte[] secret =
                requireNonZeroBytes(
                        bootstrapSecret,
                        SECRET_BYTES,
                        "bootstrapSecret");
        byte[] host =
                requireNonZeroBytes(hostNonce, NONCE_BYTES, "hostNonce");
        byte[] worker =
                requireNonZeroBytes(workerNonce, NONCE_BYTES, "workerNonce");
        byte[] transcript = null;
        byte[] transcriptDigest = null;
        byte[] saltInput = null;
        byte[] salt = null;
        byte[] prk = null;
        byte[] proofInfo = null;
        byte[] proofKey = null;
        byte[] hostProofInput = null;
        byte[] workerProofInput = null;
        byte[] hostProof = null;
        byte[] workerProof = null;
        try {
            if (MessageDigest.isEqual(host, worker)) {
                throw new IllegalArgumentException(
                        "host and worker nonces must be independently generated");
            }

            transcript = transcript(context, host, worker);
            transcriptDigest = sha256(transcript);

            // HKDF-Extract: nonces/transcript-derived salt, bootstrap secret IKM.
            saltInput = concat(
                    "ORES-IPC-HKDF-SALT-V1\0".getBytes(StandardCharsets.US_ASCII),
                    host,
                    worker,
                    transcriptDigest);
            salt = sha256(saltInput);
            prk = hmac(salt, secret);

            ChannelSecrets h2wData =
                    channel(
                            context,
                            prk,
                            transcriptDigest,
                            "host-to-worker/data",
                            ActorIpcFrameV1.Lane.DATA);
            ChannelSecrets w2hData =
                    channel(
                            context,
                            prk,
                            transcriptDigest,
                            "worker-to-host/data",
                            ActorIpcFrameV1.Lane.DATA);
            ChannelSecrets h2wControl =
                    channel(
                            context,
                            prk,
                            transcriptDigest,
                            "host-to-worker/control",
                            ActorIpcFrameV1.Lane.CONTROL);
            ChannelSecrets w2hControl =
                    channel(
                            context,
                            prk,
                            transcriptDigest,
                            "worker-to-host/control",
                            ActorIpcFrameV1.Lane.CONTROL);

            proofInfo = info(transcriptDigest, "handshake-proof");
            proofKey = hkdfExpand(prk, proofInfo, KEY_BYTES);
            hostProofInput = concat(
                    "host-ready\0".getBytes(StandardCharsets.US_ASCII),
                    transcriptDigest);
            workerProofInput = concat(
                    "worker-ready\0".getBytes(StandardCharsets.US_ASCII),
                    transcriptDigest);
            hostProof = hmac(proofKey, hostProofInput);
            workerProof = hmac(proofKey, workerProofInput);

            // EstablishedSession and ChannelSecrets defensively copy every
            // retained secret below, so all derivation scratch can be wiped.
            return new EstablishedSession(
                    h2wData,
                    w2hData,
                    h2wControl,
                    w2hControl,
                    hostProof,
                    workerProof,
                    transcriptDigest);
        } finally {
            wipe(
                    secret,
                    host,
                    worker,
                    transcript,
                    transcriptDigest,
                    saltInput,
                    salt,
                    prk,
                    proofInfo,
                    proofKey,
                    hostProofInput,
                    workerProofInput,
                    hostProof,
                    workerProof);
        }
    }

    public static boolean verifyHostProof(
            EstablishedSession session,
            byte[] suppliedProof) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(suppliedProof, "suppliedProof");
        return MessageDigest.isEqual(
                session.hostTranscriptProof(),
                suppliedProof);
    }

    public static boolean verifyWorkerProof(
            EstablishedSession session,
            byte[] suppliedProof) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(suppliedProof, "suppliedProof");
        return MessageDigest.isEqual(
                session.workerTranscriptProof(),
                suppliedProof);
    }

    private static ChannelSecrets channel(
            Context context,
            byte[] prk,
            byte[] transcriptDigest,
            String label,
            ActorIpcFrameV1.Lane lane) {
        byte[] keyInfo = null;
        byte[] key = null;
        byte[] sessionInfo = null;
        byte[] sessionMaterial = null;
        byte[] sessionId = null;
        byte[] messageAbi = null;
        try {
            keyInfo = info(transcriptDigest, label + "/key");
            key = hkdfExpand(prk, keyInfo, KEY_BYTES);
            sessionInfo = info(transcriptDigest, label + "/session");
            sessionMaterial = hkdfExpand(prk, sessionInfo, KEY_BYTES);
            sessionId = Arrays.copyOf(sessionMaterial, SESSION_ID_BYTES);
            messageAbi = context.messageAbiSha256();
            return new ChannelSecrets(
                    new ActorIpcFrameV1.SessionBinding(
                            context.logicalActorId(),
                            context.incarnation(),
                            context.routeEpoch(),
                            lane,
                            sessionId,
                            messageAbi),
                    key);
        } finally {
            wipe(
                    keyInfo,
                    key,
                    sessionInfo,
                    sessionMaterial,
                    sessionId,
                    messageAbi);
        }
    }

    private static byte[] transcript(
            Context context,
            byte[] hostNonce,
            byte[] workerNonce) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(MAGIC);
            out.writeLong(context.logicalActorId().getMostSignificantBits());
            out.writeLong(context.logicalActorId().getLeastSignificantBits());
            out.writeLong(context.incarnation());
            out.writeLong(context.routeEpoch());
            writeText(out, context.actorTypeName());
            out.write(context.signedArtifactReceiptSha256());
            out.write(context.artifactSha256());
            out.write(context.messageAbiSha256());
            out.write(context.stateSchemaSha256());
            out.write(context.capabilityPolicySha256());
            out.write(hostNonce);
            out.write(workerNonce);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("in-memory IPC session transcript failed", impossible);
        }
    }

    private static byte[] info(
            byte[] transcriptDigest,
            String label) {
        return concat(
                "ORES-IPC-HKDF-INFO-V1\0".getBytes(StandardCharsets.US_ASCII),
                transcriptDigest,
                label.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hkdfExpand(
            byte[] prk,
            byte[] info,
            int length) {
        if (length <= 0 || length > 255 * DIGEST_BYTES) {
            throw new IllegalArgumentException("invalid HKDF output length");
        }
        byte[] result = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        int counter = 1;
        try {
            while (offset < length) {
                byte[] input = concat(
                        previous,
                        info,
                        new byte[] {(byte) counter});
                byte[] block;
                try {
                    block = hmac(prk, input);
                } finally {
                    wipe(input);
                }
                wipe(previous);
                int take = Math.min(block.length, length - offset);
                System.arraycopy(block, 0, result, offset, take);
                offset += take;
                previous = block;
                counter++;
            }
            return result;
        } finally {
            wipe(previous);
        }
    }

    private static byte[] hmac(
            byte[] key,
            byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException impossible) {
            throw new AssertionError("JDK must provide HmacSHA256", impossible);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("JDK must provide SHA-256", impossible);
        }
    }

    private static String requireText(
            String value,
            String label) {
        Objects.requireNonNull(value, label);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (value.isBlank() || bytes.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException(
                    label + " must be nonblank and bounded");
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " cannot contain NUL");
        }
        return value;
    }

    private static byte[] requireBytes(
            byte[] value,
            int expectedLength,
            String label) {
        Objects.requireNonNull(value, label);
        if (value.length != expectedLength) {
            throw new IllegalArgumentException(
                    label + " must be exactly " + expectedLength + " bytes");
        }
        return value.clone();
    }

    private static byte[] requireNonZeroBytes(
            byte[] value,
            int expectedLength,
            String label) {
        byte[] copy = requireBytes(value, expectedLength, label);
        int nonZero = 0;
        for (byte b : copy) nonZero |= b;
        if (nonZero == 0) {
            throw new IllegalArgumentException(label + " cannot be all zero");
        }
        return copy;
    }

    private static void writeText(
            DataOutputStream out,
            String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static void wipe(byte[]... values) {
        for (byte[] value : values) {
            if (value != null) {
                Arrays.fill(value, (byte) 0);
            }
        }
    }

    private static byte[] concat(byte[]... values) {
        int size = 0;
        for (byte[] value : values) {
            size = Math.addExact(size, value.length);
        }
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }
}
