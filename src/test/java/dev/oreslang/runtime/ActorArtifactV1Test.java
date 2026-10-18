package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ActorArtifactV1Test {

    @Test
    void admitsExactSignedBytesAndRetainsImmutableCopy() throws Exception {
        byte[] artifact = "worker-native-image-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        var unsigned = receipt(
                artifact,
                Ast.ActorKind.PRIVATE,
                Ast.ActorBoundary.IPC,
                Set.of(IsolatePolicy.Capability.STDOUT));
        byte[] signature = sign(pair, unsigned);
        var signed = new ActorArtifactV1.SignedReceipt(
                unsigned, "release-key-2026q4", signature);

        IsolatePolicy supervisor = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.IPC,
                IsolatePolicy.Capability.CHILD_PROCESS);

        ActorArtifactV1<Object> admitted =
                ActorArtifactV1.admit(
                        signed, artifact, pair.getPublic(), supervisor);

        artifact[0] ^= 0x55;
        assertEquals(
                "worker-native-image-v1",
                new String(
                        admitted.artifactBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("ImageWorker", admitted.receipt().actorTypeName());
        assertEquals(Ast.ActorBoundary.IPC, admitted.receipt().actorBoundary());
        assertEquals("release-key-2026q4", admitted.keyId());
        assertEquals(32, admitted.signedReceiptSha256().length);

        byte[] exported = admitted.artifactBytes();
        exported[0] ^= 0x7f;
        assertNotEquals(exported[0], admitted.artifactBytes()[0]);
    }

    @Test
    void rejectsArtifactMutationAfterSigning() throws Exception {
        byte[] artifact = "trusted-worker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var unsigned = receipt(
                artifact,
                Ast.ActorKind.PRIVATE,
                Ast.ActorBoundary.IPC,
                Set.of());
        var signed = new ActorArtifactV1.SignedReceipt(
                unsigned, "release", sign(pair, unsigned));

        byte[] tampered = artifact.clone();
        tampered[tampered.length - 1] ^= 1;

        IsolatePolicy supervisor = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.IPC,
                IsolatePolicy.Capability.CHILD_PROCESS);
        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> ActorArtifactV1.admit(
                        signed, tampered, pair.getPublic(), supervisor));
        assertTrue(failure.getMessage().contains("SHA-256 mismatch"),
                failure.getMessage());
    }

    @Test
    void rejectsReceiptMutationWrongKeyAndSignatureMutation() throws Exception {
        byte[] artifact = "trusted-worker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var original = receipt(
                artifact,
                Ast.ActorKind.PRIVATE,
                Ast.ActorBoundary.IPC,
                Set.of());
        byte[] signature = sign(signer, original);
        IsolatePolicy supervisor = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.IPC,
                IsolatePolicy.Capability.CHILD_PROCESS);

        assertThrows(
                SecurityException.class,
                () -> ActorArtifactV1.admit(
                        new ActorArtifactV1.SignedReceipt(
                                original, "release", signature),
                        artifact,
                        attacker.getPublic(),
                        supervisor));

        byte[] forgedSignature = signature.clone();
        forgedSignature[0] ^= 1;
        assertThrows(
                SecurityException.class,
                () -> ActorArtifactV1.admit(
                        new ActorArtifactV1.SignedReceipt(
                                original, "release", forgedSignature),
                        artifact,
                        signer.getPublic(),
                        supervisor));

        var altered = new ActorArtifactV1.UnsignedReceipt(
                "OtherWorker",
                original.actorKind(),
                original.actorBoundary(),
                original.artifactFormat(),
                original.compilerBackend(),
                original.compilerVersion(),
                original.compilerGitCommit(),
                original.targetTriple(),
                original.abi(),
                original.builderId(),
                original.artifactSizeBytes(),
                original.artifactSha256(),
                original.messageAbiSha256(),
                original.stateSchemaSha256(),
                original.capabilityPolicySha256(),
                original.requiredCapabilities());

        assertThrows(
                SecurityException.class,
                () -> ActorArtifactV1.admit(
                        new ActorArtifactV1.SignedReceipt(
                                altered, "release", signature),
                        artifact,
                        signer.getPublic(),
                        supervisor));
    }

    @Test
    void canonicalReceiptIgnoresCapabilitySetIterationOrder() throws Exception {
        byte[] artifact = "worker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Set<IsolatePolicy.Capability> first =
                new java.util.LinkedHashSet<>(java.util.List.of(
                        IsolatePolicy.Capability.STDOUT,
                        IsolatePolicy.Capability.NETWORK));
        Set<IsolatePolicy.Capability> second =
                new java.util.LinkedHashSet<>(java.util.List.of(
                        IsolatePolicy.Capability.NETWORK,
                        IsolatePolicy.Capability.STDOUT));

        var a = receipt(
                artifact,
                Ast.ActorKind.PRIVATE,
                Ast.ActorBoundary.IPC,
                first);
        var b = receipt(
                artifact,
                Ast.ActorKind.PRIVATE,
                Ast.ActorBoundary.IPC,
                second);

        assertArrayEquals(
                ActorArtifactV1.signingBytes(a),
                ActorArtifactV1.signingBytes(b));
        assertArrayEquals(
                ActorArtifactV1.capabilityDigest(first),
                ActorArtifactV1.capabilityDigest(second));
    }

    @Test
    void deniesMissingSupervisorAuthorityAndUntrustedAmbientCapabilities()
            throws Exception {
        byte[] artifact = "worker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        var trusted = receipt(
                artifact,
                Ast.ActorKind.PRIVATE,
                Ast.ActorBoundary.IPC,
                Set.of());
        var trustedSigned = new ActorArtifactV1.SignedReceipt(
                trusted, "release", sign(pair, trusted));

        SecurityException noIpc = assertThrows(
                SecurityException.class,
                () -> ActorArtifactV1.admit(
                        trustedSigned,
                        artifact,
                        pair.getPublic(),
                        IsolatePolicy.developer()));
        assertTrue(noIpc.getMessage().contains("IPC"), noIpc.getMessage());

        var untrusted = receipt(
                artifact,
                Ast.ActorKind.UNTRUSTED,
                Ast.ActorBoundary.IPC,
                Set.of(IsolatePolicy.Capability.STDOUT));
        var untrustedSigned = new ActorArtifactV1.SignedReceipt(
                untrusted, "release", sign(pair, untrusted));

        IsolatePolicy supervisor = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.IPC,
                IsolatePolicy.Capability.CHILD_PROCESS);
        SecurityException ambient = assertThrows(
                SecurityException.class,
                () -> ActorArtifactV1.admit(
                        untrustedSigned,
                        artifact,
                        pair.getPublic(),
                        supervisor));
        assertTrue(
                ambient.getMessage().contains("zero ambient guest capabilities"),
                ambient.getMessage());
    }

    @Test
    void rejectsIllegalBoundaryAndCapabilityDigestForgery() {
        byte[] artifact = "worker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] digest = sha256(artifact);
        byte[] zero = new byte[32];

        assertThrows(
                IllegalArgumentException.class,
                () -> new ActorArtifactV1.UnsignedReceipt(
                        "Worker",
                        Ast.ActorKind.PRIVATE,
                        Ast.ActorBoundary.LOCAL,
                        ActorArtifactV1.ArtifactFormat.NATIVE_EXECUTABLE,
                        "java-graalvm",
                        "0.1",
                        "a".repeat(40),
                        "x86_64-unknown-linux-gnu",
                        "ores-ipc-v1",
                        "builder",
                        artifact.length,
                        digest,
                        digest,
                        digest,
                        ActorArtifactV1.capabilityDigest(Set.of()),
                        Set.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ActorArtifactV1.UnsignedReceipt(
                        "Worker",
                        Ast.ActorKind.UNTRUSTED,
                        Ast.ActorBoundary.FFI,
                        ActorArtifactV1.ArtifactFormat.STATIC_LIBRARY,
                        "java-graalvm",
                        "0.1",
                        "a".repeat(40),
                        "x86_64-unknown-linux-gnu",
                        "ores-ffi-v1",
                        "builder",
                        artifact.length,
                        digest,
                        digest,
                        digest,
                        ActorArtifactV1.capabilityDigest(Set.of()),
                        Set.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ActorArtifactV1.UnsignedReceipt(
                        "Worker",
                        Ast.ActorKind.PRIVATE,
                        Ast.ActorBoundary.IPC,
                        ActorArtifactV1.ArtifactFormat.NATIVE_EXECUTABLE,
                        "java-graalvm",
                        "0.1",
                        "a".repeat(40),
                        "x86_64-unknown-linux-gnu",
                        "ores-ipc-v1",
                        "builder",
                        artifact.length,
                        digest,
                        digest,
                        digest,
                        zero,
                        Set.of(IsolatePolicy.Capability.STDOUT)));
    }

    private static ActorArtifactV1.UnsignedReceipt receipt(
            byte[] artifact,
            Ast.ActorKind kind,
            Ast.ActorBoundary boundary,
            Set<IsolatePolicy.Capability> required) {
        byte[] digest = sha256(artifact);
        return new ActorArtifactV1.UnsignedReceipt(
                "ImageWorker",
                kind,
                boundary,
                boundary == Ast.ActorBoundary.FFI
                        ? ActorArtifactV1.ArtifactFormat.STATIC_LIBRARY
                        : ActorArtifactV1.ArtifactFormat.NATIVE_EXECUTABLE,
                "java-graalvm",
                "0.1.0",
                "a".repeat(40),
                "x86_64-unknown-linux-gnu",
                boundary == Ast.ActorBoundary.FFI
                        ? "ores-ffi-v1"
                        : "ores-ipc-v1",
                "test-builder",
                artifact.length,
                digest,
                sha256("message-abi".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                sha256("state-schema".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                ActorArtifactV1.capabilityDigest(required),
                required);
    }

    private static byte[] sign(
            KeyPair pair,
            ActorArtifactV1.UnsignedReceipt receipt)
            throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(ActorArtifactV1.signingBytes(receipt));
        return signer.sign();
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
