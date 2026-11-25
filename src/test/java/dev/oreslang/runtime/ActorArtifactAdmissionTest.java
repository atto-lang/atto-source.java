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
import java.util.UUID;

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
    private static final ActorArtifactAdmission.TrustedBuildAttestationPolicy
            TRUST_ATTESTATION = ignored -> { };

    @Test
    void exactSignedArtifactMintsOpaquePathlessCapability() throws Exception {
        byte[] executable = "ELF-test-worker-v1".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);
        var attestation = attestation("ImageWorker", manifest);
        var target = targetPolicy();

        ActorArtifact<Object> artifact = ActorArtifactAdmission.admitIpcActor(java.util.UUID.randomUUID(), 
                "ImageWorker",
                Ast.ActorKind.PRIVATE,
                manifest,
                executable,
                attestation,
                TRUST_ATTESTATION,
                (builder, keyId) -> keys.getPublic(),
                target,
                supervisorPolicy(),
                privateWorkerPolicy());

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
                () -> ActorArtifactAdmission.admitIpcActor(java.util.UUID.randomUUID(), 
                        "ImageWorker",
                        Ast.ActorKind.PRIVATE,
                        manifest,
                        tampered,
                        attestation("ImageWorker", manifest),
                        TRUST_ATTESTATION,
                        (builder, keyId) -> keys.getPublic(),
                        targetPolicy(),
                        supervisorPolicy(),
                        privateWorkerPolicy()));
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
                () -> ActorArtifactAdmission.admitIpcActor(java.util.UUID.randomUUID(), 
                        "ImageWorker",
                        Ast.ActorKind.PRIVATE,
                        mutated,
                        executable,
                        attestation("ImageWorker", mutated),
                        TRUST_ATTESTATION,
                        (builder, keyId) -> keys.getPublic(),
                        targetPolicy(),
                        supervisorPolicy(),
                        privateWorkerPolicy()));
        assertTrue(failure.getMessage().contains("signature"), failure.getMessage());
    }

    @Test
    void attestationTrustPolicyIsMandatoryAndFailsClosed() throws Exception {
        byte[] executable = "ELF-attestation-policy".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> ActorArtifactAdmission.admitIpcActor(
                        java.util.UUID.randomUUID(),
                        "ImageWorker",
                        Ast.ActorKind.PRIVATE,
                        manifest,
                        executable,
                        attestation("ImageWorker", manifest),
                        ignored -> { throw new SecurityException("attestation not trusted"); },
                        (builder, keyId) -> keys.getPublic(),
                        targetPolicy(),
                        supervisorPolicy(),
                        privateWorkerPolicy()));
        assertTrue(failure.getMessage().contains("attestation not trusted"),
                failure.getMessage());
    }

    @Test
    void callerByteMutationDuringTrustCallbacksCannotChangeSealedExecutable()
            throws Exception {
        byte[] executable = "ELF-racy-input".getBytes(StandardCharsets.UTF_8);
        byte[] original = executable.clone();
        KeyPair keys = keyPair();
        var manifest = sign(manifest(original, List.of()), keys);

        ActorArtifact<Object> artifact = ActorArtifactAdmission.admitIpcActor(
                java.util.UUID.randomUUID(),
                "ImageWorker",
                Ast.ActorKind.PRIVATE,
                manifest,
                executable,
                attestation("ImageWorker", manifest),
                ignored -> executable[0] ^= 0x55,
                (builder, keyId) -> {
                    executable[1] ^= 0x22;
                    return keys.getPublic();
                },
                targetPolicy(),
                supervisorPolicy(),
                privateWorkerPolicy());

        assertFalse(java.util.Arrays.equals(original, executable),
                "test must actually mutate caller-owned storage during admission");
        assertArrayEquals(
                original,
                artifact.copyExactArtifactBytesForTrustedLauncher(),
                "admission must hash, verify and seal one entry snapshot");
        assertEquals(sha256(original), artifact.artifactSha256());
    }

    @Test
    void manifestCannotSelectItsOwnUntrustedKey() throws Exception {
        byte[] executable = "ELF-key-policy".getBytes(StandardCharsets.UTF_8);
        KeyPair signer = keyPair();
        var manifest = sign(manifest(executable, List.of()), signer);

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> ActorArtifactAdmission.admitIpcActor(java.util.UUID.randomUUID(), 
                        "ImageWorker",
                        Ast.ActorKind.PRIVATE,
                        manifest,
                        executable,
                        attestation("ImageWorker", manifest),
                        TRUST_ATTESTATION,
                        (builder, keyId) -> null,
                        targetPolicy(),
                        supervisorPolicy(),
                        privateWorkerPolicy()));
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
                () -> ActorArtifactAdmission.admitIpcActor(java.util.UUID.randomUUID(), 
                        "ImageWorker",
                        Ast.ActorKind.PRIVATE,
                        manifest,
                        executable,
                        substituted,
                        TRUST_ATTESTATION,
                        (builder, keyId) -> keys.getPublic(),
                        targetPolicy(),
                        supervisorPolicy(),
                        privateWorkerPolicy()));
        assertTrue(failure.getMessage().contains("actor type"), failure.getMessage());
    }

    @Test
    void unknownAndExcessCapabilitiesFailClosed() throws Exception {
        byte[] executable = "ELF-cap".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();

        var unknown = sign(manifest(executable, List.of("made.up.capability")), keys);
        assertThrows(SecurityException.class, () -> ActorArtifactAdmission.admitIpcActor(java.util.UUID.randomUUID(), 
                "ImageWorker", Ast.ActorKind.PRIVATE, unknown, executable,
                attestation("ImageWorker", unknown),
                TRUST_ATTESTATION,
                (builder, keyId) -> keys.getPublic(), targetPolicy(),
                supervisorPolicy(), privateWorkerPolicy()));

        var network = sign(manifest(executable, List.of("network")), keys);
        SecurityException excess = assertThrows(
                SecurityException.class,
                () -> ActorArtifactAdmission.admitIpcActor(java.util.UUID.randomUUID(), 
                        "ImageWorker", Ast.ActorKind.PRIVATE, network, executable,
                        attestation("ImageWorker", network),
                        TRUST_ATTESTATION,
                        (builder, keyId) -> keys.getPublic(), targetPolicy(),
                        supervisorPolicy(), privateWorkerPolicy()));
        assertTrue(
                excess.getMessage().contains("exactly match")
                        || excess.getMessage().contains("missing="),
                excess.getMessage());
    }

    @Test
    void signedCapabilitiesMustExactlyMatchWorkerAuthority() throws Exception {
        byte[] executable = "ELF-stdout".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of("stdout")), keys);
        IsolatePolicy stdoutOnly = new IsolatePolicy(
                Set.of(IsolatePolicy.Capability.STDOUT),
                64L * 1024 * 1024,
                128,
                java.time.Duration.ofSeconds(5),
                false);

        ActorArtifact<Object> artifact = ActorArtifactAdmission.admitIpcActor(
                java.util.UUID.randomUUID(),
                "ImageWorker",
                Ast.ActorKind.PRIVATE,
                manifest,
                executable,
                attestation("ImageWorker", manifest),
                TRUST_ATTESTATION,
                (builder, keyId) -> keys.getPublic(),
                targetPolicy(),
                supervisorPolicy(),
                stdoutOnly);
        assertEquals(Set.of(IsolatePolicy.Capability.STDOUT),
                artifact.requiredCapabilities());

        var noCaps = sign(manifest(executable, List.of()), keys);
        SecurityException undeclared = assertThrows(
                SecurityException.class,
                () -> ActorArtifactAdmission.admitIpcActor(
                        java.util.UUID.randomUUID(),
                        "ImageWorker",
                        Ast.ActorKind.PRIVATE,
                        noCaps,
                        executable,
                        attestation("ImageWorker", noCaps),
                        TRUST_ATTESTATION,
                        (builder, keyId) -> keys.getPublic(),
                        targetPolicy(),
                        supervisorPolicy(),
                        stdoutOnly));
        assertTrue(undeclared.getMessage().contains("undeclared="),
                undeclared.getMessage());
    }

    @Test
    void oversizedInputFailsBeforeTrustCallbacksOrSecondCopy() throws Exception {
        byte[] executable = "ELF-oversized".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);
        var trustCalled = new java.util.concurrent.atomic.AtomicBoolean();

        var tinyTarget = new ActorArtifactAdmission.TargetPolicy(
                Set.of(ActorArtifactAdmission.Backend.LLVM),
                TARGET,
                ABI,
                4);

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> ActorArtifactAdmission.admitIpcActor(
                        java.util.UUID.randomUUID(),
                        "ImageWorker",
                        Ast.ActorKind.PRIVATE,
                        manifest,
                        executable,
                        attestation("ImageWorker", manifest),
                        ignored -> trustCalled.set(true),
                        (builder, keyId) -> keys.getPublic(),
                        tinyTarget,
                        supervisorPolicy(),
                        privateWorkerPolicy()));
        assertTrue(failure.getMessage().contains("size limit"), failure.getMessage());
        assertFalse(trustCalled.get(),
                "oversized input must be rejected before trust callbacks run");
    }

    @Test
    void trustedAttestationCannotReclassifyActorKind() throws Exception {
        byte[] executable = "ELF-kind-bound".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> ActorArtifactAdmission.admitIpcActor(
                        java.util.UUID.randomUUID(),
                        "ImageWorker",
                        Ast.ActorKind.PRIVATE,
                        manifest,
                        executable,
                        attestation("ImageWorker", Ast.ActorKind.UNTRUSTED, manifest),
                        TRUST_ATTESTATION,
                        (builder, keyId) -> keys.getPublic(),
                        targetPolicy(),
                        supervisorPolicy(),
                        privateWorkerPolicy()));
        assertTrue(failure.getMessage().contains("actor kind"), failure.getMessage());
    }

    @Test
    void untrustedIpcActorRequiresAdversarialZeroAuthorityWorker() throws Exception {
        byte[] executable = "ELF-hostile".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);

        assertDoesNotThrow(() -> ActorArtifactAdmission.admitIpcActor(java.util.UUID.randomUUID(), 
                "HostileWorker",
                Ast.ActorKind.UNTRUSTED,
                manifest,
                executable,
                attestation("HostileWorker", Ast.ActorKind.UNTRUSTED, manifest),
                TRUST_ATTESTATION,
                (builder, keyId) -> keys.getPublic(),
                targetPolicy(),
                supervisorPolicy(),
                IsolatePolicy.untrustedActor()));

        SecurityException nonAdversarial = assertThrows(
                SecurityException.class,
                () -> ActorArtifactAdmission.admitIpcActor(java.util.UUID.randomUUID(), 
                        "HostileWorker",
                        Ast.ActorKind.UNTRUSTED,
                        manifest,
                        executable,
                        attestation("HostileWorker", Ast.ActorKind.UNTRUSTED, manifest),
                        TRUST_ATTESTATION,
                        (builder, keyId) -> keys.getPublic(),
                        targetPolicy(),
                        supervisorPolicy(),
                        privateWorkerPolicy().withoutCapabilities(
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
        assertThrows(SecurityException.class, () -> ActorArtifactAdmission.admitIpcActor(java.util.UUID.randomUUID(), 
                "ImageWorker", Ast.ActorKind.PRIVATE, manifest, executable,
                attestation("ImageWorker", manifest),
                TRUST_ATTESTATION,
                (builder, keyId) -> keys.getPublic(), wrongTarget,
                supervisorPolicy(), privateWorkerPolicy()));

        IsolatePolicy noLoad =
                supervisorPolicy().withoutCapabilities(
                        IsolatePolicy.Capability.HOT_CODE_LOAD);
        SecurityException authority = assertThrows(
                SecurityException.class,
                () -> ActorArtifactAdmission.admitIpcActor(java.util.UUID.randomUUID(), 
                        "ImageWorker", Ast.ActorKind.PRIVATE, manifest, executable,
                        attestation("ImageWorker", manifest),
                        TRUST_ATTESTATION,
                        (builder, keyId) -> keys.getPublic(), targetPolicy(),
                        noLoad, privateWorkerPolicy()));
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
                            TRUST_ATTESTATION,
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
                    TRUST_ATTESTATION,
                    (builder, keyId) -> keys.getPublic(),
                    targetPolicy(),
                    privateWorkerPolicy());
            assertTrue(runtime.ownsActorArtifact(artifact));
            assertEquals(
                    ActorArtifactAdmission.workerPolicyDigest(privateWorkerPolicy()),
                    artifact.workerPolicySha256());
            try (ActorRuntime other = new ActorRuntime(supervisor)) {
                assertFalse(other.ownsActorArtifact(artifact),
                        "artifact capability must not migrate across actor runtimes");
            }
        }
    }

    @Test
    void runtimeOwnedArtifactOpensOnlyRuntimeOwnedAuthenticatedSession()
            throws Exception {
        byte[] executable = "ELF-session-bound".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);
        var attestation = attestation("ImageWorker", manifest);
        IsolatePolicy supervisor = supervisorPolicy();
        UUID logicalActorId = UUID.randomUUID();

        try (ActorRuntime runtime = new ActorRuntime(supervisor);
             ActorRuntime other = new ActorRuntime(supervisor)) {
            ActorArtifact<Object> artifact = runtime.admitIpcActorArtifact(
                    "ImageWorker",
                    Ast.ActorKind.PRIVATE,
                    manifest,
                    executable,
                    attestation,
                    TRUST_ATTESTATION,
                    (builder, keyId) -> keys.getPublic(),
                    targetPolicy(),
                    privateWorkerPolicy());

            try (ActorIpcSessionV1 session =
                         runtime.openIpcSession(artifact, logicalActorId, 7, 11)) {
                assertEquals(logicalActorId, session.logicalActorId());
                assertEquals(7, session.incarnation());
                assertEquals(11, session.routeEpoch());
                assertEquals(artifact.codeGenerationId(), session.codeGenerationId());
                assertFalse(session.ready());
            }

            SecurityException foreign = assertThrows(
                    SecurityException.class,
                    () -> other.openIpcSession(
                            artifact,
                            logicalActorId,
                            7,
                            11));
            assertTrue(foreign.getMessage().contains("not admitted by this runtime"),
                    foreign.getMessage());
        }
    }

    @Test
    void boundIpcRoutesRequireRuntimeOwnedArtifactAndExactRetirement() throws Exception {
        byte[] executable = "ELF-bounded-route".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);
        var attestation = attestation("ImageWorker", manifest);
        UUID route = UUID.randomUUID();
        try (ActorRuntime runtime = new ActorRuntime(supervisorPolicy());
             ActorRuntime other = new ActorRuntime(supervisorPolicy())) {
            ActorArtifact<Object> artifact = runtime.admitIpcActorArtifact(
                    "ImageWorker", Ast.ActorKind.PRIVATE, manifest, executable,
                    attestation, TRUST_ATTESTATION, (builder, keyId) -> keys.getPublic(),
                    targetPolicy(), privateWorkerPolicy());
            assertThrows(SecurityException.class, () ->
                    other.openBoundIpcSession(artifact, route, 1, 1, 2, 4096));
            assertThrows(IllegalArgumentException.class, () ->
                    runtime.openBoundIpcSession(artifact, route, 1, 1, 0, 4096));
            var first = runtime.openBoundIpcSession(artifact, route, 1, 1, 2, 4096);
            assertThrows(IllegalStateException.class, () ->
                    runtime.openBoundIpcSession(artifact, route, 1, 2, 2, 4096));
            assertThrows(SecurityException.class, () -> other.retireBoundIpcSession(first));
            runtime.retireBoundIpcSession(first);
            assertThrows(IllegalStateException.class, first::claimBootstrapForTrustedLauncher);
            assertThrows(SecurityException.class, () ->
                    runtime.openBoundIpcSession(artifact, route, 1, 1, 2, 4096));
            assertThrows(SecurityException.class, () ->
                    runtime.openBoundIpcSession(artifact, route, 1, 3, 2, 4096));
            assertThrows(SecurityException.class, () ->
                    runtime.openBoundIpcSession(artifact, route, 3, 2, 2, 4096));
            assertThrows(SecurityException.class, () ->
                    runtime.openBoundIpcSession(artifact, UUID.randomUUID(), 2, 1, 2, 4096));
            var replacement = runtime.openBoundIpcSession(artifact, route, 1, 2, 2, 4096);
            assertThrows(SecurityException.class, () -> runtime.retireBoundIpcSession(first));
            try (var bootstrap = replacement.claimBootstrapForTrustedLauncher()) {
                assertEquals(2, bootstrap.routeEpoch());
            }
            runtime.close();
            assertThrows(IllegalStateException.class, replacement::pollWorkerData);
            assertThrows(IllegalStateException.class, () ->
                    runtime.openBoundIpcSession(artifact, UUID.randomUUID(), 1, 1, 2, 4096));
        }
    }

    @Test
    void codecBoundRouteRejectsLegacyAttestationAndCannotDowngradeAfterRetirement() throws Exception {
        byte[] executable = "ELF-codec-bound-route".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);
        var profile = new ActorIpcCodecProfileV1("messagepack", 2, "order.v1",
                "21c2efdbff08dd4e1320d8abe1ba0cc6aea8e4cf104b984be7b7079968fad3cd", MESSAGE_ABI);
        var shape = MessagePackWireV2.Shape.string();
        var limits = MessagePackWireV2.DecodeLimits.defaults();
        UUID route = UUID.randomUUID();
        try (ActorRuntime runtime = new ActorRuntime(supervisorPolicy())) {
            ActorArtifact<Object> legacy = runtime.admitIpcActorArtifact(
                    "ImageWorker", Ast.ActorKind.PRIVATE, manifest, executable,
                    attestation("ImageWorker", manifest), TRUST_ATTESTATION,
                    (builder, keyId) -> keys.getPublic(), targetPolicy(), privateWorkerPolicy());
            assertThrows(SecurityException.class, () -> runtime.openCodecBoundIpcSession(
                    legacy, route, 1, 1, 2, 4096, profile, 1, 1));
            var attested = attestation("ImageWorker", Ast.ActorKind.PRIVATE, manifest,
                    profile.commitment());
            ActorArtifact<Object> artifact = runtime.admitIpcActorArtifact(
                    "ImageWorker", Ast.ActorKind.PRIVATE, manifest, executable,
                    attested, TRUST_ATTESTATION, (builder, keyId) -> keys.getPublic(),
                    targetPolicy(), privateWorkerPolicy());
            assertThrows(SecurityException.class, () -> runtime.openCodecBoundIpcSession(
                    artifact, route, 1, 1, 2, 4096, profile, 1, 2));
            assertThrows(IllegalArgumentException.class, () -> runtime.openMessagePackBoundIpcSession(
                    artifact, route, 1, 1, 2, 4096, profile, 1, 1, MessagePackWireV2.Shape.i64(), limits));
            var first = runtime.openMessagePackBoundIpcSession(
                    artifact, route, 1, 1, 2, 4096, profile, 1, 1, shape, limits);
            assertEquals(profile, first.codecProfile());
            runtime.retireBoundIpcSession(first);
            assertThrows(SecurityException.class, () ->
                    runtime.openBoundIpcSession(legacy, route, 1, 2, 2, 4096));
            assertThrows(SecurityException.class, () -> runtime.openCodecBoundIpcSession(
                    artifact, route, 1, 2, 2, 4096, profile, 1, 1));
            var replacement = runtime.openMessagePackBoundIpcSession(
                    artifact, route, 1, 2, 2, 4096, profile, 1, 1, shape, limits);
            assertEquals(profile, replacement.codecProfile());
        }
    }

    @Test
    void authenticatedStringHandoffUsesRealMailboxAndRejectsRetiredOrConsumedDelivery() throws Exception {
        byte[] executable = "ELF-host-handoff".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);
        var profile = new ActorIpcCodecProfileV1("messagepack", 2, "order.v1",
                "21c2efdbff08dd4e1320d8abe1ba0cc6aea8e4cf104b984be7b7079968fad3cd", MESSAGE_ABI);
        var shape = MessagePackWireV2.Shape.string();
        var limits = new MessagePackWireV2.DecodeLimits(1024, 1024, 16, 2048, 8);
        var observed = new java.util.concurrent.LinkedBlockingQueue<String>();
        UUID route = UUID.randomUUID();
        try (ActorRuntime runtime = new ActorRuntime(supervisorPolicy());
             ActorRuntime other = new ActorRuntime(supervisorPolicy())) {
            var artifact = runtime.admitIpcActorArtifact(
                    "ImageWorker", Ast.ActorKind.PRIVATE, manifest, executable,
                    attestation("ImageWorker", Ast.ActorKind.PRIVATE, manifest, profile.commitment()),
                    TRUST_ATTESTATION, (builder, keyId) -> keys.getPublic(),
                    targetPolicy(), privateWorkerPolicy());
            ActorRuntime.ActorRef<String> receiver = runtime.spawnPrivateTrusted(
                    context -> (message, ctx) -> observed.add(message));
            ActorRuntime.ActorRef<String> foreign = other.spawnPrivateTrusted(
                    context -> (message, ctx) -> { });
            var session = runtime.openMessagePackBoundIpcSession(
                    artifact, route, 1, 1, 2, 4096, profile, 1, 1, shape, limits);
            try (var bootstrap = session.claimBootstrapForTrustedLauncher()) {
                session.acceptWorkerFrame(ActorIpcSessionV1.encodeWorkerFrame(bootstrap, artifact,
                        ActorIpcSessionV1.Lane.CONTROL, ActorIpcSessionV1.FrameKind.READY,
                        1, 0, new byte[0]));
                assertThrows(SecurityException.class, () -> runtime.bindIpcStringReceiver(session, foreign));
                var group = runtime.createActorGroup();
                ActorRuntime.ActorRef<String> managed = runtime.spawnPrivateTrusted(
                        context -> (message, ctx) -> { });
                group.manageWorker(managed);
                assertThrows(SecurityException.class, () -> runtime.bindIpcStringReceiver(session, managed));
                runtime.bindIpcStringReceiver(session, receiver);
                assertThrows(SecurityException.class, () -> group.manageWorker(receiver));
                assertThrows(IllegalStateException.class, () -> runtime.bindIpcStringReceiver(session, receiver));
                for (int i=1; i<=2; i++) {
                    byte[] wire = ActorIpcSessionV1.encodeWorkerFrame(bootstrap, artifact,
                            ActorIpcSessionV1.Lane.DATA, ActorIpcSessionV1.FrameKind.OUTPUT,
                            i, i, MessagePackWireV2.encode(profile.schema(), shape, "value-"+i));
                    assertTrue(session.tryQueueWorkerData(wire));
                    try (var delivery = session.pollWorkerData();
                         var decoded = session.tryDecodeWorkerData(delivery)) {
                        assertNotNull(decoded);
                        if (i==1) {
                            runtime.forwardIpcString(session, decoded);
                            assertEquals("value-1", observed.poll(5, java.util.concurrent.TimeUnit.SECONDS));
                            assertFalse(decoded.liveFor(session));
                            assertThrows(SecurityException.class, () -> runtime.forwardIpcString(session, decoded));
                        } else {
                            runtime.retireBoundIpcSession(session);
                            assertThrows(SecurityException.class, () -> group.manageWorker(receiver));
                            assertThrows(SecurityException.class, () -> runtime.forwardIpcString(session, decoded));
                            assertTrue(decoded.liveFor(session));
                        }
                    }
                }
                assertNull(observed.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            }
        }
    }

    @Test
    void concurrentIpcReceiverAndGroupAdoptionHaveExactlyOneWinner() throws Exception {
        byte[] executable = "ELF-host-claim-race".getBytes(StandardCharsets.UTF_8);
        KeyPair keys = keyPair();
        var manifest = sign(manifest(executable, List.of()), keys);
        var profile = new ActorIpcCodecProfileV1("messagepack", 2, "order.v1",
                "21c2efdbff08dd4e1320d8abe1ba0cc6aea8e4cf104b984be7b7079968fad3cd", MESSAGE_ABI);
        var shape = MessagePackWireV2.Shape.string();
        var limits = new MessagePackWireV2.DecodeLimits(1024, 1024, 16, 2048, 8);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        int ipcWins = 0, groupWins = 0;
        try (ActorRuntime runtime = new ActorRuntime(supervisorPolicy())) {
            var artifact = runtime.admitIpcActorArtifact(
                    "ImageWorker", Ast.ActorKind.PRIVATE, manifest, executable,
                    attestation("ImageWorker", Ast.ActorKind.PRIVATE, manifest, profile.commitment()),
                    TRUST_ATTESTATION, (builder, keyId) -> keys.getPublic(),
                    targetPolicy(), privateWorkerPolicy());
            for (int i=0; i<128; i++) {
                ActorRuntime.ActorRef<String> receiver = runtime.spawnPrivateTrusted(
                        context -> (message, ctx) -> { });
                var group = runtime.createActorGroup();
                var session = runtime.openMessagePackBoundIpcSession(
                        artifact, UUID.randomUUID(), 1, 1, 2, 4096, profile, 1, 1, shape, limits);
                try (var bootstrap = session.claimBootstrapForTrustedLauncher()) {
                    session.acceptWorkerFrame(ActorIpcSessionV1.encodeWorkerFrame(bootstrap, artifact,
                            ActorIpcSessionV1.Lane.CONTROL, ActorIpcSessionV1.FrameKind.READY,
                            1, 0, new byte[0]));
                    var start = new java.util.concurrent.CyclicBarrier(2);
                    var ipc = pool.submit(() -> {
                        start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        try { runtime.bindIpcStringReceiver(session, receiver); return true; }
                        catch (SecurityException denied) { return false; }
                    });
                    var managed = pool.submit(() -> {
                        start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        try { group.manageWorker(receiver); return true; }
                        catch (SecurityException denied) { return false; }
                    });
                    boolean ipcWon = ipc.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    boolean groupWon = managed.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    assertNotEquals(ipcWon, groupWon, "exactly one ownership claimant must win");
                    if (ipcWon) {
                        ipcWins++;
                        assertThrows(SecurityException.class, () -> group.manageWorker(receiver));
                    } else {
                        groupWins++;
                        assertThrows(SecurityException.class, () -> runtime.bindIpcStringReceiver(session, receiver));
                    }
                } finally {
                    runtime.retireBoundIpcSession(session);
                    receiver.stop();
                    assertTrue(receiver.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
                    group.close();
                }
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(128, ipcWins + groupWins);
        System.out.println("IPC_RECEIVER_CLAIM_RACE_PASS rounds=128 ipc=" + ipcWins + " group=" + groupWins);
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

    private static IsolatePolicy supervisorPolicy() {
        return IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.IPC,
                IsolatePolicy.Capability.CHILD_PROCESS);
    }

    private static IsolatePolicy privateWorkerPolicy() {
        return new IsolatePolicy(
                Set.of(),
                64L * 1024 * 1024,
                128,
                java.time.Duration.ofSeconds(5),
                false);
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
        return attestation(actorTypeName, actorKind, manifest, WIRE_CODEC);
    }

    private static ActorArtifactAdmission.TrustedBuildAttestation attestation(
            String actorTypeName,
            Ast.ActorKind actorKind,
            ActorArtifactAdmission.Manifest manifest,
            String codecCommitment) {
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
                codecCommitment);
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
