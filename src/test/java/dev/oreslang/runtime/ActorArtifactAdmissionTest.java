package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ActorArtifactAdmissionTest {
    private static final String TARGET = "x86_64-unknown-linux-gnu";
    private static final String ABI = "sysv";
    private static final String SOURCE = "11".repeat(32);
    private static final String EXPANDED = "22".repeat(32);
    private static final String AST = "33".repeat(32);
    private static final String MESSAGE_ABI = "55".repeat(32);
    private static final String STATE_SCHEMA = "66".repeat(32);
    private static final String WIRE_CODEC = "77".repeat(32);

    @Test
    void exactSignedArtifactMintsOpaquePathlessCapability() throws Exception {
        byte[] executable = "ELF-test-worker-v1".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);
        var attestation = attestation("ImageWorker", manifest);
        var target = targetPolicy();

        ActorArtifact<Object> artifact = ActorArtifactAdmission.admitIpcActor(
                "ImageWorker",
                Ast.ActorKind.PRIVATE,
                manifest,
                executable,
                attestation,
                (builder, keyId) -> keys.getPublic(),
                target,
                IsolatePolicy.developer(),
                IsolatePolicy.developer());

        assertEquals("ImageWorker", artifact.actorTypeName());
        assertEquals(Ast.ActorKind.PRIVATE, artifact.actorKind());
        assertEquals(Ast.ActorBoundary.IPC, artifact.actorBoundary());
        assertEquals(sha256(executable), artifact.artifactSha256());
        assertEquals(MESSAGE_ABI, artifact.messageAbiSha256());
        assertEquals(STATE_SCHEMA, artifact.stateSchemaSha256());
        assertEquals(WIRE_CODEC, artifact.wireCodecSha256());
        assertEquals(executable.length, artifact.artifactSizeBytes());
        assertFalse(artifact.toString().contains("/") || artifact.toString().contains("\\"),
                "opaque artifact rendering must not disclose or imply a filesystem path");

        byte[] first = artifact.copyExactArtifactBytesForTrustedLauncher();
        assertArrayEquals(executable, first);
        executable[0] ^= 0x7f;
        assertNotEquals(executable[0], artifact.copyExactArtifactBytesForTrustedLauncher()[0],
                "admission must snapshot bytes rather than retain caller-owned mutable storage");
        first[0] ^= 0x22;
        assertNotEquals(first[0], artifact.copyExactArtifactBytesForTrustedLauncher()[0],
                "launcher copies must not mutate the sealed artifact capability");
    }

    @Test
    void tamperedExecutableBytesAreRejectedBeforeMint() throws Exception {
        byte[] executable = "ELF-original".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);
        byte[] tampered = executable.clone();
        tampered[tampered.length - 1] ^= 1;

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> ActorArtifactAdmission.admitIpcActor(
                        "ImageWorker",
                        Ast.ActorKind.PRIVATE,
                        manifest,
                        tampered,
                        attestation("ImageWorker", manifest),
                        (builder, keyId) -> keys.getPublic(),
                        targetPolicy(),
                        IsolatePolicy.developer(),
                        IsolatePolicy.developer()));
        assertTrue(failure.getMessage().contains("sha256")
                        || failure.getMessage().contains("size"),
                failure.getMessage());
    }

    @Test
    void signatureCoversCompilerAndPolicyFields() throws Exception {
        byte[] executable = "ELF-signed".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var signed = sign(manifest(executable, List.of()), keys);

        var mutated = new ActorArtifactAdmission.Manifest(
                signed.schemaVersion(),
                new ActorArtifactAdmission.Compiler(
                        signed.compiler().backend(),
                        signed.compiler().version() + "-tampered",
                        signed.compiler().gitCommit()),
                signed.sourceSha256(),
                signed.expandedSha256(),
                signed.generatedAstSha256(),
                signed.artifact(),
                signed.policy(),
                signed.provenance());

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> ActorArtifactAdmission.admitIpcActor(
                        "ImageWorker",
                        Ast.ActorKind.PRIVATE,
                        mutated,
                        executable,
                        attestation("ImageWorker", mutated),
                        (builder, keyId) -> keys.getPublic(),
                        targetPolicy(),
                        IsolatePolicy.developer(),
                        IsolatePolicy.developer()));
        assertTrue(failure.getMessage().contains("signature"), failure.getMessage());
    }

    @Test
    void manifestCannotSelectItsOwnUntrustedKey() throws Exception {
        byte[] executable = "ELF-key-policy".getBytes(StandardCharsets.UTF_8);
        KeyPair signer = keyPair();
        var manifest = sign(manifest(executable, List.of()), signer);

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> ActorArtifactAdmission.admitIpcActor(
                        "ImageWorker",
                        Ast.ActorKind.PRIVATE,
                        manifest,
                        executable,
                        attestation("ImageWorker", manifest),
                        (builder, keyId) -> null,
                        targetPolicy(),
                        IsolatePolicy.developer(),
                        IsolatePolicy.developer()));
        assertTrue(failure.getMessage().contains("not trusted"), failure.getMessage());
    }

    @Test
    void trustedBuildAttestationMustMatchEverySecurityCriticalManifestField()
            throws Exception {
        byte[] executable = "ELF-attested".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);
        var good = attestation("ImageWorker", manifest);
        var substituted = new ActorArtifactAdmission.TrustedBuildAttestation(
                "OtherWorker",
                good.actorKind(),
                good.actorBoundary(),
                good.backend(),
                good.compilerVersion(),
                good.compilerGitCommit(),
                good.sourceSha256(),
                good.expandedSha256(),
                good.generatedAstSha256(),
                good.artifactSha256(),
                good.artifactSizeBytes(),
                good.artifactFormat(),
                good.targetTriple(),
                good.abi(),
                good.builderId(),
                good.messageAbiSha256(),
                good.stateSchemaSha256(),
                good.wireCodecSha256());

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> ActorArtifactAdmission.admitIpcActor(
                        "ImageWorker",
                        Ast.ActorKind.PRIVATE,
                        manifest,
                        executable,
                        substituted,
                        (builder, keyId) -> keys.getPublic(),
                        targetPolicy(),
                        IsolatePolicy.developer(),
                        IsolatePolicy.developer()));
        assertTrue(failure.getMessage().contains("actor type"), failure.getMessage());
    }

    @Test
    void unknownAndExcessCapabilitiesFailClosed() throws Exception {
        byte[] executable = "ELF-cap".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();

        var unknown = sign(manifest(executable, List.of("made.up.capability")), keys);
        assertThrows(SecurityException.class, () -> ActorArtifactAdmission.admitIpcActor(
                "ImageWorker", Ast.ActorKind.PRIVATE, unknown, executable,
                attestation("ImageWorker", unknown),
                (builder, keyId) -> keys.getPublic(), targetPolicy(),
                IsolatePolicy.developer(), IsolatePolicy.developer()));

        var network = sign(manifest(executable, List.of("network")), keys);
        SecurityException excess = assertThrows(
                SecurityException.class,
                () -> ActorArtifactAdmission.admitIpcActor(
                        "ImageWorker", Ast.ActorKind.PRIVATE, network, executable,
                        attestation("ImageWorker", network),
                        (builder, keyId) -> keys.getPublic(), targetPolicy(),
                        IsolatePolicy.developer(), IsolatePolicy.developer()));
        assertTrue(excess.getMessage().contains("outside worker policy"), excess.getMessage());
    }

    @Test
    void untrustedIpcActorRequiresAdversarialZeroAuthorityWorker() throws Exception {
        byte[] executable = "ELF-hostile".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);

        assertDoesNotThrow(() -> ActorArtifactAdmission.admitIpcActor(
                "HostileWorker",
                Ast.ActorKind.UNTRUSTED,
                manifest,
                executable,
                attestation("HostileWorker", manifest),
                (builder, keyId) -> keys.getPublic(),
                targetPolicy(),
                IsolatePolicy.developer(),
                IsolatePolicy.untrustedActor()));

        SecurityException nonAdversarial = assertThrows(
                SecurityException.class,
                () -> ActorArtifactAdmission.admitIpcActor(
                        "HostileWorker",
                        Ast.ActorKind.UNTRUSTED,
                        manifest,
                        executable,
                        attestation("HostileWorker", manifest),
                        (builder, keyId) -> keys.getPublic(),
                        targetPolicy(),
                        IsolatePolicy.developer(),
                        IsolatePolicy.developer().withoutCapabilities(
                                IsolatePolicy.Capability.STDIN,
                                IsolatePolicy.Capability.STDOUT,
                                IsolatePolicy.Capability.PROCESS_INFO,
                                IsolatePolicy.Capability.PROCESS_EXIT,
                                IsolatePolicy.Capability.GC_CONTROL,
                                IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
                                IsolatePolicy.Capability.ACTOR_SPAWN,
                                IsolatePolicy.Capability.SHARED_MEMORY,
                                IsolatePolicy.Capability.HOT_CODE_LOAD)));
        assertTrue(nonAdversarial.getMessage().contains("adversarial"),
                nonAdversarial.getMessage());
    }

    @Test
    void targetFormatAndSupervisorAuthorityAreIndependentAdmissionGates()
            throws Exception {
        byte[] executable = "ELF-target".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);

        var wrongTarget = new ActorArtifactAdmission.TargetPolicy(
                Set.of(ActorArtifactAdmission.Backend.LLVM),
                "aarch64-unknown-linux-gnu",
                ABI,
                1024 * 1024);
        assertThrows(SecurityException.class, () -> ActorArtifactAdmission.admitIpcActor(
                "ImageWorker", Ast.ActorKind.PRIVATE, manifest, executable,
                attestation("ImageWorker", manifest),
                (builder, keyId) -> keys.getPublic(), wrongTarget,
                IsolatePolicy.developer(), IsolatePolicy.developer()));

        IsolatePolicy noLoad =
                IsolatePolicy.developer().withoutCapabilities(
                        IsolatePolicy.Capability.HOT_CODE_LOAD);
        SecurityException authority = assertThrows(
                SecurityException.class,
                () -> ActorArtifactAdmission.admitIpcActor(
                        "ImageWorker", Ast.ActorKind.PRIVATE, manifest, executable,
                        attestation("ImageWorker", manifest),
                        (builder, keyId) -> keys.getPublic(), targetPolicy(),
                        noLoad, IsolatePolicy.developer()));
        assertTrue(authority.getMessage().contains("HOT_CODE_LOAD"), authority.getMessage());
    }

    @Test
    void runtimeAdmissionUsesActualSupervisorAuthorityAndBindsRuntime()
            throws Exception {
        byte[] executable = "ELF-runtime-bound".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);
        var attestation = attestation("ImageWorker", manifest);

        try (ActorRuntime insufficient = new ActorRuntime(IsolatePolicy.developer())) {
            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> insufficient.admitIpcActorArtifact(
                            "ImageWorker",
                            Ast.ActorKind.PRIVATE,
                            manifest,
                            executable,
                            attestation,
                            (builder, keyId) -> keys.getPublic(),
                            targetPolicy(),
                            IsolatePolicy.developer()));
            assertTrue(
                    denied.getMessage().contains("IPC")
                            || denied.getMessage().contains("CHILD_PROCESS"),
                    denied.getMessage());
        }

        IsolatePolicy supervisor = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.IPC,
                IsolatePolicy.Capability.CHILD_PROCESS);
        try (ActorRuntime runtime = new ActorRuntime(supervisor)) {
            ActorArtifact<Object> artifact = runtime.admitIpcActorArtifact(
                    "ImageWorker",
                    Ast.ActorKind.PRIVATE,
                    manifest,
                    executable,
                    attestation,
                    (builder, keyId) -> keys.getPublic(),
                    targetPolicy(),
                    IsolatePolicy.developer());
            assertTrue(artifact.admittedForRuntime(runtimeInstanceId(runtime)));
        }
    }

    @Test
    void canonicalUnsignedManifestHasDeterministicSortedObjectKeys() throws Exception {
        byte[] executable = "ELF-canonical".getBytes(StandardCharsets.UTF_8);
        var manifest = manifest(executable, List.of("filesystem.read", "stdout"));
        String canonical = new String(
                ActorArtifactAdmission.canonicalUnsignedManifest(manifest),
                StandardCharsets.UTF_8);

        assertTrue(canonical.startsWith("{\"artifact\":{\"abi\":"));
        assertTrue(canonical.contains("\"compiler\":{\"backend\":"));
        assertTrue(canonical.contains("\"policy\":{\"required_capabilities\":[\"filesystem.read\",\"stdout\"]"));
        assertTrue(canonical.contains("\"provenance\":{\"builder_id\":\"trusted-builder-1\"}"));
        assertFalse(canonical.contains("\"signature\""),
                "signature member must be omitted from signed payload");
    }

    private static ActorArtifactAdmission.TargetPolicy targetPolicy() {
        return new ActorArtifactAdmission.TargetPolicy(
                Set.of(ActorArtifactAdmission.Backend.LLVM),
                TARGET,
                ABI,
                1024 * 1024);
    }

    private static ActorArtifactAdmission.Manifest manifest(
            byte[] executable,
            List<String> capabilities) {
        return new ActorArtifactAdmission.Manifest(
                ActorArtifactAdmission.SCHEMA_VERSION,
                new ActorArtifactAdmission.Compiler(
                        ActorArtifactAdmission.Backend.LLVM,
                        "0.1.0-dev",
                        "aa".repeat(20)),
                SOURCE,
                EXPANDED,
                AST,
                new ActorArtifactAdmission.Artifact(
                        sha256(executable),
                        executable.length,
                        ActorArtifactAdmission.Format.NATIVE_EXECUTABLE,
                        TARGET,
                        ABI),
                new ActorArtifactAdmission.Policy(
                        false,
                        false,
                        false,
                        capabilities),
                new ActorArtifactAdmission.Provenance(
                        "trusted-builder-1",
                        new ActorArtifactAdmission.ManifestSignature(
                                "ed25519",
                                "release-key-1",
                                "pending")));
    }

    private static ActorArtifactAdmission.Manifest sign(
            ActorArtifactAdmission.Manifest unsigned,
            KeyPair keys) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(ActorArtifactAdmission.canonicalUnsignedManifest(unsigned));
        String encoded = Base64.getEncoder().encodeToString(signer.sign());

        return new ActorArtifactAdmission.Manifest(
                unsigned.schemaVersion(),
                unsigned.compiler(),
                unsigned.sourceSha256(),
                unsigned.expandedSha256(),
                unsigned.generatedAstSha256(),
                unsigned.artifact(),
                unsigned.policy(),
                new ActorArtifactAdmission.Provenance(
                        unsigned.provenance().builderId(),
                        new ActorArtifactAdmission.ManifestSignature(
                                "ed25519",
                                unsigned.provenance().signature().keyId(),
                                encoded)));
    }

    private static ActorArtifactAdmission.TrustedBuildAttestation attestation(
            String actorTypeName,
            ActorArtifactAdmission.Manifest manifest) {
        return attestation(actorTypeName, Ast.ActorKind.PRIVATE, manifest);
    }

    private static ActorArtifactAdmission.TrustedBuildAttestation attestation(
            String actorTypeName,
            Ast.ActorKind actorKind,
            ActorArtifactAdmission.Manifest manifest) {
        return new ActorArtifactAdmission.TrustedBuildAttestation(
                actorTypeName,
                actorKind,
                Ast.ActorBoundary.IPC,
                manifest.compiler().backend(),
                manifest.compiler().version(),
                manifest.compiler().gitCommit(),
                manifest.sourceSha256(),
                manifest.expandedSha256(),
                manifest.generatedAstSha256(),
                manifest.artifact().fileSha256(),
                manifest.artifact().sizeBytes(),
                manifest.artifact().format(),
                manifest.artifact().targetTriple(),
                manifest.artifact().abi(),
                manifest.provenance().builderId(),
                MESSAGE_ABI,
                STATE_SCHEMA,
                WIRE_CODEC);
    }

    private static java.util.UUID runtimeInstanceId(ActorRuntime runtime) {
        try {
            var field = ActorRuntime.class.getDeclaredField("runtimeInstanceId");
            field.setAccessible(true);
            return (java.util.UUID) field.get(runtime);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
