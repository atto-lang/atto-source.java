package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ActorIpcSessionV1Test {
    private static final String MANIFEST = "11".repeat(32);
    private static final String ARTIFACT = "22".repeat(32);
    private static final String MESSAGE_ABI = "33".repeat(32);
    private static final String STATE_SCHEMA = "44".repeat(32);
    private static final String WIRE_CODEC = "55".repeat(32);
    private static final String WORKER_POLICY = "66".repeat(32);

    @Test
    void readyThenDataAndControlUseIndependentSequences() {
        ActorArtifact<Object> artifact = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 4, 9);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {

            ActorIpcSessionV1.Frame ready = session.acceptWorkerFrame(
                    worker(bootstrap, artifact,
                            ActorIpcSessionV1.Lane.CONTROL,
                            ActorIpcSessionV1.FrameKind.READY,
                            1, 0, new byte[0]));
            assertEquals(ActorIpcSessionV1.FrameKind.READY, ready.kind());
            assertTrue(session.ready());

            ActorIpcSessionV1.Frame output = session.acceptWorkerFrame(
                    worker(bootstrap, artifact,
                            ActorIpcSessionV1.Lane.DATA,
                            ActorIpcSessionV1.FrameKind.OUTPUT,
                            1, 100, "image".getBytes(StandardCharsets.UTF_8)));
            assertEquals(1, output.frameSequence());
            assertEquals(100, output.messageSequence());

            ActorIpcSessionV1.Frame complete = session.acceptWorkerFrame(
                    worker(bootstrap, artifact,
                            ActorIpcSessionV1.Lane.CONTROL,
                            ActorIpcSessionV1.FrameKind.COMPLETE,
                            2, 100, new byte[0]));
            assertEquals(2, complete.frameSequence());
            assertEquals(ActorIpcSessionV1.Lane.CONTROL, complete.lane());
        }
    }

    @Test
    void bootstrapMaterialIsSingleConsumerAndDestroyable() {
        ActorArtifact<Object> artifact = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1)) {
            ActorIpcSessionV1.BootstrapMaterial bootstrap =
                    session.claimBootstrapForTrustedLauncher();
            assertThrows(
                    IllegalStateException.class,
                    session::claimBootstrapForTrustedLauncher);
            byte[] before = bootstrap.masterKey();
            assertTrue(Arrays.stream(box(before)).anyMatch(value -> value != 0));
            bootstrap.close();
            byte[] after = bootstrap.masterKey();
            assertArrayEquals(new byte[32], after);
            Arrays.fill(before, (byte) 0);
            Arrays.fill(after, (byte) 0);
        }
    }

    @Test
    void replayedWorkerFramePoisonsSession() {
        ActorArtifact<Object> artifact = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            ready(session, bootstrap, artifact);
            byte[] output = worker(
                    bootstrap, artifact,
                    ActorIpcSessionV1.Lane.DATA,
                    ActorIpcSessionV1.FrameKind.OUTPUT,
                    1, 1, new byte[]{7});
            session.acceptWorkerFrame(output);

            ActorIpcSessionV1.ProtocolException replay = assertThrows(
                    ActorIpcSessionV1.ProtocolException.class,
                    () -> session.acceptWorkerFrame(output));
            assertEquals(ActorIpcSessionV1.Reason.SEQUENCE, replay.reason());
            assertTrue(session.poisoned());
        }
    }

    @Test
    void skippedFrameSequencePoisonsOnlyThatSession() {
        ActorArtifact<Object> artifact = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            ready(session, bootstrap, artifact);
            ActorIpcSessionV1.ProtocolException skipped = assertThrows(
                    ActorIpcSessionV1.ProtocolException.class,
                    () -> session.acceptWorkerFrame(worker(
                            bootstrap, artifact,
                            ActorIpcSessionV1.Lane.CONTROL,
                            ActorIpcSessionV1.FrameKind.COMPLETE,
                            3, 1, new byte[0])));
            assertEquals(ActorIpcSessionV1.Reason.SEQUENCE, skipped.reason());
            assertTrue(session.poisoned());
        }
    }

    @Test
    void staleSessionFrameCannotEnterReplacementSession() {
        ActorArtifact<Object> artifact = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        UUID logical = UUID.randomUUID();

        try (ActorIpcSessionV1 oldSession = ActorIpcSessionV1.create(
                    artifact, logical, 2, 7);
             ActorIpcSessionV1.BootstrapMaterial oldBootstrap =
                    oldSession.claimBootstrapForTrustedLauncher();
             ActorIpcSessionV1 replacement = ActorIpcSessionV1.create(
                    artifact, logical, 2, 8);
             ActorIpcSessionV1.BootstrapMaterial newBootstrap =
                    replacement.claimBootstrapForTrustedLauncher()) {
            ready(oldSession, oldBootstrap, artifact);

            byte[] stale = worker(
                    oldBootstrap, artifact,
                    ActorIpcSessionV1.Lane.DATA,
                    ActorIpcSessionV1.FrameKind.OUTPUT,
                    1, 9, new byte[]{1});

            ActorIpcSessionV1.ProtocolException failure = assertThrows(
                    ActorIpcSessionV1.ProtocolException.class,
                    () -> replacement.acceptWorkerFrame(stale));
            assertEquals(ActorIpcSessionV1.Reason.AUTHENTICATION, failure.reason());
            assertTrue(replacement.poisoned());

            // The old route remains independently valid until its owner retires it.
            assertDoesNotThrow(() -> oldSession.acceptWorkerFrame(stale));
        }
    }

    @Test
    void validMacCannotOverrideLogicalRouteIdentity() throws Exception {
        ActorArtifact<Object> artifact = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 3, 5);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            byte[] ready = worker(
                    bootstrap, artifact,
                    ActorIpcSessionV1.Lane.CONTROL,
                    ActorIpcSessionV1.FrameKind.READY,
                    1, 0, new byte[0]);

            // routeEpoch is the i64 at offset 40. Re-MAC with the legitimate
            // CONTROL key to prove identity validation is independent of MAC.
            ByteBuffer.wrap(ready)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putLong(40, 6L);
            retag(bootstrap, ActorIpcSessionV1.Lane.CONTROL, ready);

            ActorIpcSessionV1.ProtocolException failure = assertThrows(
                    ActorIpcSessionV1.ProtocolException.class,
                    () -> session.acceptWorkerFrame(ready));
            assertEquals(ActorIpcSessionV1.Reason.IDENTITY, failure.reason());
            assertTrue(session.poisoned());
        }
    }

    @Test
    void wrongWorkerMessageAbiFailsEvenWithValidBootstrapMac() {
        ActorArtifact<Object> expected = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        ActorArtifact<Object> wrongAbi = artifact(
                "ImageWorker", "99".repeat(32), ARTIFACT);

        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                expected, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            ActorIpcSessionV1.ProtocolException failure = assertThrows(
                    ActorIpcSessionV1.ProtocolException.class,
                    () -> session.acceptWorkerFrame(worker(
                            bootstrap, wrongAbi,
                            ActorIpcSessionV1.Lane.CONTROL,
                            ActorIpcSessionV1.FrameKind.READY,
                            1, 0, new byte[0])));
            assertEquals(ActorIpcSessionV1.Reason.ABI, failure.reason());
            assertTrue(session.poisoned());
        }
    }

    @Test
    void wrongArtifactCommitmentFailsReadyEvenWhenMessageAbiMatches() {
        ActorArtifact<Object> expected = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        ActorArtifact<Object> wrongArtifact = artifact(
                "ImageWorker", MESSAGE_ABI, "aa".repeat(32));

        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                expected, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            ActorIpcSessionV1.ProtocolException failure = assertThrows(
                    ActorIpcSessionV1.ProtocolException.class,
                    () -> session.acceptWorkerFrame(worker(
                            bootstrap, wrongArtifact,
                            ActorIpcSessionV1.Lane.CONTROL,
                            ActorIpcSessionV1.FrameKind.READY,
                            1, 0, new byte[0])));
            assertEquals(ActorIpcSessionV1.Reason.STATE, failure.reason());
            assertTrue(session.poisoned());
        }
    }

    @Test
    void nonReadyFirstFrameFailsClosed() {
        ActorArtifact<Object> artifact = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            ActorIpcSessionV1.ProtocolException failure = assertThrows(
                    ActorIpcSessionV1.ProtocolException.class,
                    () -> session.acceptWorkerFrame(worker(
                            bootstrap, artifact,
                            ActorIpcSessionV1.Lane.CONTROL,
                            ActorIpcSessionV1.FrameKind.CONTROL,
                            1, 0, new byte[0])));
            assertEquals(ActorIpcSessionV1.Reason.STATE, failure.reason());
            assertTrue(session.poisoned());
        }
    }

    @Test
    void outOfOrderLogicalCompletionsAreAllowedWithOrderedTransportFrames() {
        ActorArtifact<Object> artifact = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            ready(session, bootstrap, artifact);

            assertEquals(2, session.acceptWorkerFrame(worker(
                    bootstrap, artifact,
                    ActorIpcSessionV1.Lane.CONTROL,
                    ActorIpcSessionV1.FrameKind.COMPLETE,
                    2, 20, new byte[0])).frameSequence());

            assertEquals(10, session.acceptWorkerFrame(worker(
                    bootstrap, artifact,
                    ActorIpcSessionV1.Lane.CONTROL,
                    ActorIpcSessionV1.FrameKind.COMPLETE,
                    3, 10, new byte[0])).messageSequence());
        }
    }

    @Test
    void truncatedTrailingAndLaneTamperedFramesPoisonSession() {
        ActorArtifact<Object> artifact = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);

        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            byte[] ready = worker(
                    bootstrap, artifact,
                    ActorIpcSessionV1.Lane.CONTROL,
                    ActorIpcSessionV1.FrameKind.READY,
                    1, 0, new byte[0]);
            byte[] truncated = Arrays.copyOf(ready, ready.length - 1);
            assertThrows(
                    ActorIpcSessionV1.ProtocolException.class,
                    () -> session.acceptWorkerFrame(truncated));
            assertTrue(session.poisoned());
        }

        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            byte[] ready = worker(
                    bootstrap, artifact,
                    ActorIpcSessionV1.Lane.CONTROL,
                    ActorIpcSessionV1.FrameKind.READY,
                    1, 0, new byte[0]);
            byte[] trailing = Arrays.copyOf(ready, ready.length + 1);
            assertThrows(
                    ActorIpcSessionV1.ProtocolException.class,
                    () -> session.acceptWorkerFrame(trailing));
            assertTrue(session.poisoned());
        }

        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            byte[] ready = worker(
                    bootstrap, artifact,
                    ActorIpcSessionV1.Lane.CONTROL,
                    ActorIpcSessionV1.FrameKind.READY,
                    1, 0, new byte[0]);
            // Lane byte is authenticated. Flip CONTROL -> DATA without re-MAC.
            ready[12] = 1;
            ActorIpcSessionV1.ProtocolException failure = assertThrows(
                    ActorIpcSessionV1.ProtocolException.class,
                    () -> session.acceptWorkerFrame(ready));
            assertEquals(
                    ActorIpcSessionV1.Reason.AUTHENTICATION,
                    failure.reason());
        }
    }

    @Test
    void supervisorDataAndControlSequencesStartIndependentlyAtOne() {
        ActorArtifact<Object> artifact = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            ready(session, bootstrap, artifact);

            byte[] data = session.encodeSupervisorData(11, new byte[]{1});
            byte[] control = session.encodeSupervisorControl(
                    ActorIpcSessionV1.FrameKind.CONTROL,
                    0,
                    new byte[]{2});

            assertEquals(1L, frameSequence(data));
            assertEquals(1L, frameSequence(control));
            assertEquals(11L, messageSequence(data));
            assertEquals(0L, messageSequence(control));
        }
    }

    @Test
    void payloadSizeBoundIsCheckedBeforeFrameAllocation() {
        ActorArtifact<Object> artifact = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            ready(session, bootstrap, artifact);
            byte[] tooLarge = new byte[ActorIpcSessionV1.MAX_PAYLOAD_BYTES + 1];
            assertThrows(
                    IllegalArgumentException.class,
                    () -> session.encodeSupervisorData(1, tooLarge));
        }
    }

    private static void ready(
            ActorIpcSessionV1 session,
            ActorIpcSessionV1.BootstrapMaterial bootstrap,
            ActorArtifact<?> artifact) {
        session.acceptWorkerFrame(worker(
                bootstrap,
                artifact,
                ActorIpcSessionV1.Lane.CONTROL,
                ActorIpcSessionV1.FrameKind.READY,
                1,
                0,
                new byte[0]));
    }

    private static byte[] worker(
            ActorIpcSessionV1.BootstrapMaterial bootstrap,
            ActorArtifact<?> artifact,
            ActorIpcSessionV1.Lane lane,
            ActorIpcSessionV1.FrameKind kind,
            long frameSequence,
            long messageSequence,
            byte[] payload) {
        return ActorIpcSessionV1.encodeWorkerFrame(
                bootstrap,
                artifact,
                lane,
                kind,
                frameSequence,
                messageSequence,
                payload);
    }

    private static void retag(
            ActorIpcSessionV1.BootstrapMaterial bootstrap,
            ActorIpcSessionV1.Lane lane,
            byte[] frame) throws Exception {
        byte[] master = bootstrap.masterKey();
        byte[] label = ("ORESIPC1:"
                + (lane == ActorIpcSessionV1.Lane.DATA ? "data" : "control"))
                .getBytes(StandardCharsets.US_ASCII);
        javax.crypto.Mac derive = javax.crypto.Mac.getInstance("HmacSHA256");
        derive.init(new javax.crypto.spec.SecretKeySpec(master, "HmacSHA256"));
        byte[] laneKey = derive.doFinal(label);
        Arrays.fill(master, (byte) 0);

        int bodyLength = frame.length - 32;
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(laneKey, "HmacSHA256"));
        mac.update(frame, 0, bodyLength);
        byte[] tag = mac.doFinal();
        System.arraycopy(tag, 0, frame, bodyLength, tag.length);
        Arrays.fill(laneKey, (byte) 0);
        Arrays.fill(tag, (byte) 0);
    }

    private static ActorArtifact<Object> artifact(
            String actorType,
            String messageAbi,
            String artifactSha) {
        return ActorArtifact.mint(
                UUID.randomUUID(),
                UUID.randomUUID(),
                actorType,
                Ast.ActorKind.PRIVATE,
                MANIFEST,
                artifactSha,
                messageAbi,
                STATE_SCHEMA,
                WIRE_CODEC,
                WORKER_POLICY,
                "llvm",
                "0.1.0-test",
                "aa".repeat(20),
                "x86_64-unknown-linux-gnu",
                "sysv",
                "test-builder",
                "test-key",
                Set.of(),
                "ELF-test".getBytes(StandardCharsets.UTF_8));
    }

    private static long frameSequence(byte[] frame) {
        return ByteBuffer.wrap(frame)
                .order(ByteOrder.BIG_ENDIAN)
                .getLong(64);
    }

    private static long messageSequence(byte[] frame) {
        return ByteBuffer.wrap(frame)
                .order(ByteOrder.BIG_ENDIAN)
                .getLong(72);
    }

    private static Integer[] box(byte[] bytes) {
        Integer[] boxed = new Integer[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            boxed[i] = Byte.toUnsignedInt(bytes[i]);
        }
        return boxed;
    }
}
