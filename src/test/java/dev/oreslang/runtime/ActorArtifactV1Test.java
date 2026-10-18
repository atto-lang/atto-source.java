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
        var signed = signed(pair, unsigned, "release-key-2026q4");

        IsolatePolicy supervisor = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.IPC,
                IsolatePolicy.Capability.CHILD_PROCESS);

        ActorArtifactV1<Object> admitted = ActorArtifactV1.admit(
                signed,
                artifact,
                trusted(pair, "release-key-2026q4"),
                expected(unsigned),
                supervisor);

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
        var signed = signed(pair, unsigned, "release");

        byte[] tampered = artifact.clone();
        tampered[tampered.length - 1] ^= 1;

        IsolatePolicy supervisor = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.IPC,
                IsolatePolicy.Capability.CHILD_PROCESS);
        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> ActorArtifactV1.admit(
                        signed,
                        tampered,
                        trusted(pair, "release"),
                        expected(unsigned),
                        supervisor));
        assertTrue(failure.getMessage().contains("SHA-256 mismatch"),
                failure.getMessage());
    }

    @Test
    void rejectsReceiptMutationWrongKeySignatureAndKeyId() throws Exception {
        byte[] artifact = "trusted-worker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var original = receipt(
                artifact,
                Ast.ActorKind.PRIVATE,
                Ast.ActorBoundary.IPC,
                Set.of());
        var signed = signed(signer, original, "release");

        IsolatePolicy supervisor = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.IPC,
                IsolatePolicy.Capability.CHILD_PROCESS);

        assertThrows(
                SecurityException.class,
                () -> ActorArtifactV1.admit(
                        signed,
                        artifact,
                        trusted(attacker, "release"),
                        expected(original),
                        supervisor));

        byte[] forgedSignature = signed.signatureEd25519();
        forgedSignature[0] ^= 1;
        assertThrows(
                SecurityException.class,
                () -> ActorArtifactV1.admit(
                        new ActorArtifactV1.SignedReceipt(
                                original, "release", forgedSignature),
                        artifact,
                        trusted(signer, "release"),
                        expected(original),
                        supervisor));

        assertThrows(
                SecurityException.class,
                () -> ActorArtifactV1.admit(
                        new ActorArtifactV1.SignedReceipt(
                                original, "other-key", signed.signatureEd25519()),
                        artifact,
                        trusted(signer, "release"),
                        expected(original),
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
                                altered, "release", signed.signatureEd25519()),
                        artifact,
                        trusted(signer, "release"),
                        expected(original),
                        supervisor));
    }

    @Test
    void rejectsSignedArtifactForDifferentExpectedActorOrAbi() throws Exception {
        byte[] artifact = "trusted-worker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var unsigned = receipt(
                artifact,
                Ast.ActorKind.PRIVATE,
                Ast.ActorBoundary.IPC,
                Set.of());
        var signed = signed(pair, unsigned, "release");
        IsolatePolicy supervisor = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.IPC,
                IsolatePolicy.Capability.CHILD_PROCESS);

        var wrongActor = new ActorArtifactV1.ExpectedActorContract(
                "OtherWorker",
                unsigned.actorKind(),
                unsigned.actorBoundary(),
                unsigned.artifactFormat(),
                unsigned.compilerBackend(),
                unsigned.compilerVersion(),
                unsigned.compilerGitCommit(),
                unsigned.targetTriple(),
                unsigned.abi(),
                unsigned.artifactSha256(),
                unsigned.messageAbiSha256(),
                unsigned.stateSchemaSha256(),
                unsigned.capabilityPolicySha256());
        assertThrows(
                SecurityException.class,
                () -> ActorArtifactV1.admit(
                        signed,
                        artifact,
                        trusted(pair, "release"),
                        wrongActor,
                        supervisor));

        byte[] wrongMessageAbi = unsigned.messageAbiSha256();
        wrongMessageAbi[0] ^= 1;
        var wrongAbi = new ActorArtifactV1.ExpectedActorContract(
                unsigned.actorTypeName(),
                unsigned.actorKind(),
                unsigned.actorBoundary(),
                unsigned.artifactFormat(),
                unsigned.compilerBackend(),
                unsigned.compilerVersion(),
                unsigned.compilerGitCommit(),
                unsigned.targetTriple(),
                unsigned.abi(),
                unsigned.artifactSha256(),
                wrongMessageAbi,
                unsigned.stateSchemaSha256(),
                unsigned.capabilityPolicySha256());
        assertThrows(
                SecurityException.class,
                () -> ActorArtifactV1.admit(
                        signed,
                        artifact,
                        trusted(pair, "release"),
                        wrongAbi,
                        supervisor));

        byte[] wrongArtifact = unsigned.artifactSha256();
        wrongArtifact[0] ^= 1;
        var wrongArtifactContract = new ActorArtifactV1.ExpectedActorContract(
                unsigned.actorTypeName(),
                unsigned.actorKind(),
                unsigned.actorBoundary(),
                unsigned.artifactFormat(),
                unsigned.compilerBackend(),
                unsigned.compilerVersion(),
                unsigned.compilerGitCommit(),
                unsigned.targetTriple(),
                unsigned.abi(),
                wrongArtifact,
                unsigned.messageAbiSha256(),
                unsigned.stateSchemaSha256(),
                unsigned.capabilityPolicySha256());
        assertThrows(
                SecurityException.class,
                () -> ActorArtifactV1.admit(
                        signed,
                        artifact,
                        trusted(pair, "release"),
                        wrongArtifactContract,
                        supervisor));
    }

    @Test
    void canonicalReceiptIgnoresCapabilitySetIterationOrder() {
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
                ActorArtifactV1.signingBytes(a, "release"),
                ActorArtifactV1.signingBytes(b, "release"));
        assertArrayEquals(
                ActorArtifactV1.capabilityDigest(first),
                ActorArtifactV1.capabilityDigest(second));
        assertFalse(java.util.Arrays.equals(
                ActorArtifactV1.signingBytes(a, "release"),
                ActorArtifactV1.signingBytes(a, "other-release")));
    }

    @Test
    void deniesMissingSupervisorAuthorityAndUntrustedAmbientCapabilities()
            throws Exception {
        byte[] artifact = "worker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        var trustedReceipt = receipt(
                artifact,
                Ast.ActorKind.PRIVATE,
                Ast.ActorBoundary.IPC,
                Set.of());
        var trustedSigned = signed(pair, trustedReceipt, "release");

        SecurityException noIpc = assertThrows(
                SecurityException.class,
                () -> ActorArtifactV1.admit(
                        trustedSigned,
                        artifact,
                        trusted(pair, "release"),
                        expected(trustedReceipt),
                        IsolatePolicy.developer()));
        assertTrue(noIpc.getMessage().contains("IPC"), noIpc.getMessage());

        var untrusted = receipt(
                artifact,
                Ast.ActorKind.UNTRUSTED,
                Ast.ActorBoundary.IPC,
                Set.of(IsolatePolicy.Capability.STDOUT));
        var untrustedSigned = signed(pair, untrusted, "release");

        IsolatePolicy supervisor = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.IPC,
                IsolatePolicy.Capability.CHILD_PROCESS);
        SecurityException ambient = assertThrows(
                SecurityException.class,
                () -> ActorArtifactV1.admit(
                        untrustedSigned,
                        artifact,
                        trusted(pair, "release"),
                        expected(untrusted),
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
                        ActorArtifactV1.CompilerBackend.JAVA_GRAALVM,
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
                        Ast.ActorKind.PRIVATE,
                        Ast.ActorBoundary.IPC,
                        ActorArtifactV1.ArtifactFormat.STATIC_LIBRARY,
                        ActorArtifactV1.CompilerBackend.JAVA_GRAALVM,
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
                        Ast.ActorKind.PRIVATE,
                        Ast.ActorBoundary.FFI,
                        ActorArtifactV1.ArtifactFormat.NATIVE_EXECUTABLE,
                        ActorArtifactV1.CompilerBackend.JAVA_GRAALVM,
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
                        Ast.ActorKind.UNTRUSTED,
                        Ast.ActorBoundary.FFI,
                        ActorArtifactV1.ArtifactFormat.STATIC_LIBRARY,
                        ActorArtifactV1.CompilerBackend.JAVA_GRAALVM,
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
                        ActorArtifactV1.CompilerBackend.JAVA_GRAALVM,
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
                ActorArtifactV1.CompilerBackend.JAVA_GRAALVM,
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

    private static ActorArtifactV1.ExpectedActorContract expected(
            ActorArtifactV1.UnsignedReceipt receipt) {
        return new ActorArtifactV1.ExpectedActorContract(
                receipt.actorTypeName(),
                receipt.actorKind(),
                receipt.actorBoundary(),
                receipt.artifactFormat(),
                receipt.compilerBackend(),
                receipt.compilerVersion(),
                receipt.compilerGitCommit(),
                receipt.targetTriple(),
                receipt.abi(),
                receipt.artifactSha256(),
                receipt.messageAbiSha256(),
                receipt.stateSchemaSha256(),
                receipt.capabilityPolicySha256());
    }

    private static ActorArtifactV1.SignedReceipt signed(
            KeyPair pair,
            ActorArtifactV1.UnsignedReceipt receipt,
            String keyId)
            throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(ActorArtifactV1.signingBytes(receipt, keyId));
        return new ActorArtifactV1.SignedReceipt(
                receipt,
                keyId,
                signer.sign());
    }

    private static ActorArtifactV1.TrustedReleaseKey trusted(
            KeyPair pair,
            String keyId) {
        return new ActorArtifactV1.TrustedReleaseKey(
                keyId,
                pair.getPublic());
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
