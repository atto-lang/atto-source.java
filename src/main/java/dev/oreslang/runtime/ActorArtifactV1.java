package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Opaque, host-only capability representing one cryptographically admitted
 * external actor artifact.
 *
 * <p>This is intentionally not a filesystem path. V1 retains the exact
 * authenticated artifact bytes so later launch code can execute/materialize
 * the admitted object rather than re-opening a mutable path after verification.
 * The class does not launch processes or load native code.</p>
 *
 * <p>The signed receipt is an internal binary admission receipt. It complements
 * the JSON/TypeSpec AOT manifest contract; it is not a replacement for strict
 * JSON parsing, RFC 8785 canonicalization or build-attestation verification.</p>
 */
public final class ActorArtifactV1<T> {
    private static final byte[] RECEIPT_MAGIC =
            "ORES-ACTOR-ARTIFACT-V1\0".getBytes(StandardCharsets.US_ASCII);
    private static final int SHA256_BYTES = 32;
    private static final int MAX_TEXT_BYTES = 16 * 1024;
    private static final int MAX_ARTIFACT_BYTES = 256 * 1024 * 1024;
    private static final int MAX_CAPABILITIES = 128;

    public enum ArtifactFormat {
        NATIVE_EXECUTABLE,
        STATIC_LIBRARY,
        WASM_MODULE
    }

    public enum CompilerBackend {
        JAVA_GRAALVM("java-graalvm"),
        LLVM("llvm");

        private final String manifestId;

        CompilerBackend(String manifestId) {
            this.manifestId = manifestId;
        }

        public String manifestId() {
            return manifestId;
        }
    }

    /** Values supplied by the authenticated build/manifest pipeline. */
    public record UnsignedReceipt(
            String actorTypeName,
            Ast.ActorKind actorKind,
            Ast.ActorBoundary actorBoundary,
            ArtifactFormat artifactFormat,
            CompilerBackend compilerBackend,
            String compilerVersion,
            String compilerGitCommit,
            String targetTriple,
            String abi,
            String builderId,
            long artifactSizeBytes,
            byte[] artifactSha256,
            byte[] messageAbiSha256,
            byte[] stateSchemaSha256,
            byte[] capabilityPolicySha256,
            Set<IsolatePolicy.Capability> requiredCapabilities) {

        public UnsignedReceipt {
            actorTypeName = requireText(actorTypeName, "actorTypeName");
            actorKind = Objects.requireNonNull(actorKind, "actorKind");
            actorBoundary = Objects.requireNonNull(actorBoundary, "actorBoundary");
            artifactFormat = Objects.requireNonNull(artifactFormat, "artifactFormat");
            compilerBackend =
                    Objects.requireNonNull(compilerBackend, "compilerBackend");
            compilerVersion = requireText(compilerVersion, "compilerVersion");
            compilerGitCommit = requireGitCommit(compilerGitCommit);
            targetTriple = requireText(targetTriple, "targetTriple");
            abi = requireText(abi, "abi");
            builderId = requireText(builderId, "builderId");
            if (artifactSizeBytes <= 0 || artifactSizeBytes > MAX_ARTIFACT_BYTES) {
                throw new IllegalArgumentException(
                        "actor artifact size must be in 1.." + MAX_ARTIFACT_BYTES);
            }
            artifactSha256 = digestCopy(artifactSha256, "artifactSha256");
            messageAbiSha256 = digestCopy(messageAbiSha256, "messageAbiSha256");
            stateSchemaSha256 = digestCopy(stateSchemaSha256, "stateSchemaSha256");
            capabilityPolicySha256 =
                    digestCopy(capabilityPolicySha256, "capabilityPolicySha256");
            requiredCapabilities = Set.copyOf(
                    Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
            if (requiredCapabilities.size() > MAX_CAPABILITIES) {
                throw new IllegalArgumentException("too many required capabilities");
            }

            if (actorKind == Ast.ActorKind.NONE) {
                throw new IllegalArgumentException(
                        "actor artifact receipt requires an actor kind");
            }
            if (actorBoundary == Ast.ActorBoundary.LOCAL) {
                throw new IllegalArgumentException(
                        "ActorArtifactV1 is only for IPC/FFI external boundaries");
            }
            if (actorBoundary == Ast.ActorBoundary.IPC
                    && artifactFormat != ArtifactFormat.NATIVE_EXECUTABLE) {
                throw new IllegalArgumentException(
                        "ipc actor artifact v1 must be a native executable");
            }
            if (actorBoundary == Ast.ActorBoundary.FFI
                    && artifactFormat != ArtifactFormat.STATIC_LIBRARY) {
                throw new IllegalArgumentException(
                        "ffi actor artifact v1 must be a static library");
            }
            if (actorBoundary == Ast.ActorBoundary.FFI
                    && actorKind == Ast.ActorKind.UNTRUSTED) {
                throw new IllegalArgumentException(
                        "untrusted ffi actor artifacts are forbidden");
            }
            if (actorKind == Ast.ActorKind.SHARED) {
                throw new IllegalArgumentException(
                        "external actor artifacts cannot use shared actor state");
            }
            byte[] computedCapabilityDigest =
                    capabilityDigest(requiredCapabilities);
            if (!MessageDigest.isEqual(
                    capabilityPolicySha256, computedCapabilityDigest)) {
                throw new IllegalArgumentException(
                        "capability policy digest does not match required capabilities");
            }
        }

        @Override public byte[] artifactSha256() { return artifactSha256.clone(); }
        @Override public byte[] messageAbiSha256() { return messageAbiSha256.clone(); }
        @Override public byte[] stateSchemaSha256() { return stateSchemaSha256.clone(); }
        @Override public byte[] capabilityPolicySha256() {
            return capabilityPolicySha256.clone();
        }
    }

    public record TrustedReleaseKey(
            String keyId,
            PublicKey publicKey) {
        public TrustedReleaseKey {
            keyId = requireText(keyId, "keyId");
            publicKey = Objects.requireNonNull(publicKey, "publicKey");
        }
    }

    /**
     * Supervisor/deployment expectation for the exact artifact being admitted.
     * A valid signature for another actor or ABI is not sufficient.
     */
    public record ExpectedActorContract(
            String actorTypeName,
            Ast.ActorKind actorKind,
            Ast.ActorBoundary actorBoundary,
            ArtifactFormat artifactFormat,
            CompilerBackend compilerBackend,
            String compilerVersion,
            String compilerGitCommit,
            String targetTriple,
            String abi,
            byte[] artifactSha256,
            byte[] messageAbiSha256,
            byte[] stateSchemaSha256,
            byte[] capabilityPolicySha256) {
        public ExpectedActorContract {
            actorTypeName = requireText(actorTypeName, "actorTypeName");
            actorKind = Objects.requireNonNull(actorKind, "actorKind");
            actorBoundary = Objects.requireNonNull(actorBoundary, "actorBoundary");
            artifactFormat = Objects.requireNonNull(artifactFormat, "artifactFormat");
            compilerBackend =
                    Objects.requireNonNull(compilerBackend, "compilerBackend");
            compilerVersion = requireText(compilerVersion, "compilerVersion");
            compilerGitCommit = requireGitCommit(compilerGitCommit);
            targetTriple = requireText(targetTriple, "targetTriple");
            abi = requireText(abi, "abi");
            artifactSha256 = digestCopy(artifactSha256, "artifactSha256");
            messageAbiSha256 = digestCopy(messageAbiSha256, "messageAbiSha256");
            stateSchemaSha256 = digestCopy(stateSchemaSha256, "stateSchemaSha256");
            capabilityPolicySha256 =
                    digestCopy(capabilityPolicySha256, "capabilityPolicySha256");
        }

        @Override public byte[] artifactSha256() {
            return artifactSha256.clone();
        }

        @Override public byte[] messageAbiSha256() {
            return messageAbiSha256.clone();
        }

        @Override public byte[] stateSchemaSha256() {
            return stateSchemaSha256.clone();
        }

        @Override public byte[] capabilityPolicySha256() {
            return capabilityPolicySha256.clone();
        }
    }

    public record SignedReceipt(
            UnsignedReceipt receipt,
            String keyId,
            byte[] signatureEd25519) {
        public SignedReceipt {
            receipt = Objects.requireNonNull(receipt, "receipt");
            keyId = requireText(keyId, "keyId");
            signatureEd25519 =
                    Objects.requireNonNull(signatureEd25519, "signatureEd25519").clone();
            if (signatureEd25519.length != 64) {
                throw new IllegalArgumentException(
                        "Ed25519 signature must be exactly 64 bytes");
            }
        }

        @Override public byte[] signatureEd25519() {
            return signatureEd25519.clone();
        }
    }

    private final UnsignedReceipt receipt;
    private final String keyId;
    private final byte[] artifactBytes;
    private final byte[] signedReceiptSha256;

    private ActorArtifactV1(
            UnsignedReceipt receipt,
            String keyId,
            byte[] artifactBytes,
            byte[] signedReceiptSha256) {
        this.receipt = receipt;
        this.keyId = keyId;
        this.artifactBytes = artifactBytes;
        this.signedReceiptSha256 = signedReceiptSha256;
    }

    public UnsignedReceipt receipt() { return receipt; }
    public String keyId() { return keyId; }

    /** Exact admitted bytes. Caller receives a copy, never the retained authority. */
    public byte[] artifactBytes() { return artifactBytes.clone(); }

    public byte[] signedReceiptSha256() { return signedReceiptSha256.clone(); }

    /**
     * Mint the opaque capability only after signature, artifact-byte and
     * supervisor-policy admission all succeed.
     */
    public static <T> ActorArtifactV1<T> admit(
            SignedReceipt signed,
            byte[] artifactBytes,
            TrustedReleaseKey trustedReleaseKey,
            ExpectedActorContract expected,
            IsolatePolicy supervisorPolicy) {
        Objects.requireNonNull(signed, "signed");
        Objects.requireNonNull(artifactBytes, "artifactBytes");
        Objects.requireNonNull(trustedReleaseKey, "trustedReleaseKey");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(supervisorPolicy, "supervisorPolicy");
        if (!signed.keyId().equals(trustedReleaseKey.keyId())) {
            throw new SecurityException(
                    "actor artifact signing key identity mismatch");
        }

        UnsignedReceipt receipt = signed.receipt();
        verifyExpectedContract(receipt, expected);
        if (artifactBytes.length != receipt.artifactSizeBytes()) {
            throw new SecurityException("actor artifact byte length mismatch");
        }
        byte[] actualArtifactDigest = sha256(artifactBytes);
        if (!MessageDigest.isEqual(
                actualArtifactDigest, receipt.artifactSha256())) {
            throw new SecurityException("actor artifact SHA-256 mismatch");
        }

        verifySupervisorAuthority(receipt, supervisorPolicy);
        byte[] signedMessage = signingBytes(receipt, signed.keyId());
        verifySignature(
                trustedReleaseKey.publicKey(),
                signedMessage,
                signed.signatureEd25519());

        byte[] retained = artifactBytes.clone();
        byte[] signedDigest = sha256(concat(
                signedMessage,
                signed.signatureEd25519()));
        return new ActorArtifactV1<>(
                receipt,
                signed.keyId(),
                retained,
                signedDigest);
    }

    /**
     * Stable canonical bytes signed by an external trusted release key.
     * The externally resolved key identity is itself signed to prevent
     * key-id relabeling/substitution.
     * Length-prefixed UTF-8 strings prevent concatenation ambiguity.
     */
    public static byte[] signingBytes(
            UnsignedReceipt receipt,
            String keyId) {
        Objects.requireNonNull(receipt, "receipt");
        keyId = requireText(keyId, "keyId");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(RECEIPT_MAGIC);
            writeText(out, keyId);
            writeText(out, receipt.actorTypeName());
            out.writeInt(receipt.actorKind().ordinal());
            out.writeInt(receipt.actorBoundary().ordinal());
            out.writeInt(receipt.artifactFormat().ordinal());
            writeText(out, receipt.compilerBackend().manifestId());
            writeText(out, receipt.compilerVersion());
            writeText(out, receipt.compilerGitCommit());
            writeText(out, receipt.targetTriple());
            writeText(out, receipt.abi());
            writeText(out, receipt.builderId());
            out.writeLong(receipt.artifactSizeBytes());
            out.write(receipt.artifactSha256());
            out.write(receipt.messageAbiSha256());
            out.write(receipt.stateSchemaSha256());
            out.write(receipt.capabilityPolicySha256());

            List<IsolatePolicy.Capability> capabilities =
                    receipt.requiredCapabilities().stream()
                            .sorted(Comparator.comparing(Enum::name))
                            .toList();
            out.writeInt(capabilities.size());
            for (IsolatePolicy.Capability capability : capabilities) {
                writeText(out, capability.name());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("in-memory receipt encoding failed", impossible);
        }
    }

    public static byte[] capabilityDigest(
            Set<IsolatePolicy.Capability> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.size() > MAX_CAPABILITIES) {
            throw new IllegalArgumentException("too many capabilities");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(capabilities.size());
            capabilities.stream()
                    .sorted(Comparator.comparing(Enum::name))
                    .forEach(capability -> {
                        try {
                            writeText(out, capability.name());
                        } catch (IOException impossible) {
                            throw new AssertionError(impossible);
                        }
                    });
            out.flush();
            return sha256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new AssertionError("in-memory capability encoding failed", impossible);
        }
    }

    private static void verifyExpectedContract(
            UnsignedReceipt receipt,
            ExpectedActorContract expected) {
        if (!receipt.actorTypeName().equals(expected.actorTypeName())
                || receipt.actorKind() != expected.actorKind()
                || receipt.actorBoundary() != expected.actorBoundary()
                || receipt.artifactFormat() != expected.artifactFormat()
                || receipt.compilerBackend() != expected.compilerBackend()
                || !receipt.compilerVersion().equals(expected.compilerVersion())
                || !receipt.compilerGitCommit().equals(expected.compilerGitCommit())
                || !receipt.targetTriple().equals(expected.targetTriple())
                || !receipt.abi().equals(expected.abi())
                || !MessageDigest.isEqual(
                        receipt.artifactSha256(), expected.artifactSha256())
                || !MessageDigest.isEqual(
                        receipt.messageAbiSha256(), expected.messageAbiSha256())
                || !MessageDigest.isEqual(
                        receipt.stateSchemaSha256(), expected.stateSchemaSha256())
                || !MessageDigest.isEqual(
                        receipt.capabilityPolicySha256(),
                        expected.capabilityPolicySha256())) {
            throw new SecurityException(
                    "actor artifact receipt does not match expected actor/ABI contract");
        }
    }

    private static void verifySupervisorAuthority(
            UnsignedReceipt receipt,
            IsolatePolicy supervisorPolicy) {
        supervisorPolicy.require(
                IsolatePolicy.Capability.ACTOR_SPAWN,
                "external actor artifact admission");

        if (receipt.actorBoundary() == Ast.ActorBoundary.IPC) {
            supervisorPolicy.require(
                    IsolatePolicy.Capability.IPC,
                    "ipc actor transport");
            supervisorPolicy.require(
                    IsolatePolicy.Capability.CHILD_PROCESS,
                    "ipc actor process launch");
        } else if (receipt.actorBoundary() == Ast.ActorBoundary.FFI) {
            supervisorPolicy.require(
                    IsolatePolicy.Capability.FFI,
                    "ffi actor boundary");
        }

        if (receipt.actorKind() == Ast.ActorKind.UNTRUSTED
                && !receipt.requiredCapabilities().isEmpty()) {
            throw new SecurityException(
                    "untrusted ipc actor artifact must require zero ambient guest capabilities");
        }

        for (IsolatePolicy.Capability capability : receipt.requiredCapabilities()) {
            if (!supervisorPolicy.allows(capability)) {
                throw new SecurityException(
                        "actor artifact requires capability denied by supervisor: "
                                + capability);
            }
        }
    }

    private static void verifySignature(
            PublicKey trustedReleaseKey,
            byte[] message,
            byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(trustedReleaseKey);
            verifier.update(message);
            if (!verifier.verify(signature)) {
                throw new SecurityException(
                        "actor artifact receipt signature verification failed");
            }
        } catch (SecurityException failure) {
            throw failure;
        } catch (java.security.GeneralSecurityException failure) {
            throw new SecurityException(
                    "cannot verify actor artifact Ed25519 receipt", failure);
        }
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (value.isBlank() || encoded.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException(
                    label + " must be nonblank and at most "
                            + MAX_TEXT_BYTES + " UTF-8 bytes");
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " cannot contain NUL");
        }
        return value;
    }

    private static String requireGitCommit(String value) {
        value = requireText(value, "compilerGitCommit");
        if (!(value.length() == 40 || value.length() == 64)
                || !value.chars().allMatch(ch ->
                        ch >= '0' && ch <= '9'
                                || ch >= 'a' && ch <= 'f')) {
            throw new IllegalArgumentException(
                    "compilerGitCommit must be lowercase 40- or 64-digit hex");
        }
        return value;
    }

    private static byte[] digestCopy(byte[] digest, String label) {
        Objects.requireNonNull(digest, label);
        if (digest.length != SHA256_BYTES) {
            throw new IllegalArgumentException(label + " must be 32 bytes");
        }
        return digest.clone();
    }

    private static void writeText(DataOutputStream out, String value)
            throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("invalid canonical text field");
        }
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("JDK must provide SHA-256", impossible);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) size = Math.addExact(size, part.length);
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    @Override
    public String toString() {
        return "ActorArtifactV1["
                + receipt.actorTypeName() + ","
                + receipt.actorKind() + "/"
                + receipt.actorBoundary() + ","
                + receipt.targetTriple() + "]";
    }
}
