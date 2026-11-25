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
    void authenticatedMessagePackUsesPinnedShapeAndRetainsDecodedCredits() {
        var shape = MessagePackWireV2.Shape.string();
        var profile = new ActorIpcCodecProfileV1("messagepack", 2, "demo.String.v1",
                "21c2efdbff08dd4e1320d8abe1ba0cc6aea8e4cf104b984be7b7079968fad3cd", MESSAGE_ABI);
        var artifact = artifactWithReadyCommitments("ImageWorker", MANIFEST, ARTIFACT,
                MESSAGE_ABI, STATE_SCHEMA, profile.commitment(), WORKER_POLICY);
        var limits = new MessagePackWireV2.DecodeLimits(1024, 1024, 16, 2048, 8);
        try (var session = ActorIpcSessionV1.create(artifact, UUID.randomUUID(), 1, 1)) {
            session.bindCodecProfile(profile, 1, 1);
            assertThrows(IllegalArgumentException.class, () ->
                    session.bindMessagePackDecoder(MessagePackWireV2.Shape.i64(), limits));
            session.bindMessagePackDecoder(shape, limits);
            var transientQuota = new ActorDataDecodeQuotaV1(65536, 1);
            var retained = new ActorDataDecodeQuotaV1(65536, 4);
            session.bindDataQueue(2, 4096, transientQuota, retained);
            try (var bootstrap = session.claimBootstrapForTrustedLauncher()) {
                ready(session, bootstrap, artifact);
                byte[] payload = MessagePackWireV2.encode(profile.schema(), shape, "hello");
                byte[] frame = worker(bootstrap, artifact, ActorIpcSessionV1.Lane.DATA,
                        ActorIpcSessionV1.FrameKind.OUTPUT, 1, 9, payload);
                assertTrue(session.tryQueueWorkerData(frame));
                try (var delivery = session.pollWorkerData()) {
                    assertNotNull(delivery);
                    assertThrows(SecurityException.class, delivery::copyPayload);
                    try (var held = transientQuota.tryReserve(1)) {
                        assertNotNull(held);
                        assertNull(session.tryDecodeWorkerData(delivery));
                        assertEquals(frame.length, retained.usedBytes());
                    }
                    try (var decoded = session.tryDecodeWorkerData(delivery)) {
                        assertNotNull(decoded);
                        assertEquals("hello", decoded.value());
                        assertEquals(9L, decoded.messageSequence());
                        assertEquals(5120L, retained.usedBytes());
                        assertEquals(0L, transientQuota.usedBytes());
                        assertThrows(SecurityException.class, () -> session.tryDecodeWorkerData(delivery));
                    }
                    assertEquals(0L, retained.usedBytes());
                }
                byte[] wrong = MessagePackWireV2.encode("wrong.schema", shape, "hello");
                assertTrue(session.tryQueueWorkerData(worker(bootstrap, artifact,
                        ActorIpcSessionV1.Lane.DATA, ActorIpcSessionV1.FrameKind.OUTPUT, 2, 10, wrong)));
                try (var delivery = session.pollWorkerData()) {
                    assertThrows(ActorIpcSessionV1.ProtocolException.class,
                            () -> session.tryDecodeWorkerData(delivery));
                    assertTrue(session.poisoned());
                    assertEquals(0L, retained.usedBytes());
                    assertEquals(0L, transientQuota.usedBytes());
                }
            }
        }
    }

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
    void queueRetainsTenantCreditsUntilDeliveryClosedAndBackpressurePreservesSequence() {
        ActorArtifact<Object> artifact = artifact("ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (var session = ActorIpcSessionV1.create(artifact, UUID.randomUUID(), 1, 1);
             var bootstrap = session.claimBootstrapForTrustedLauncher()) {
            ready(session, bootstrap, artifact);
            var transientBudget = new ActorDataDecodeQuotaV1(65536, 1);
            var retained = new ActorDataDecodeQuotaV1(65536, 1);
            session.bindDataQueue(1, 4096, transientBudget, retained);
            byte[] one = worker(bootstrap, artifact, ActorIpcSessionV1.Lane.DATA,
                    ActorIpcSessionV1.FrameKind.OUTPUT, 1, 9, new byte[]{7});
            byte[] two = worker(bootstrap, artifact, ActorIpcSessionV1.Lane.DATA,
                    ActorIpcSessionV1.FrameKind.OUTPUT, 2, 10, new byte[]{8});
            assertTrue(session.tryQueueWorkerData(one));
            assertEquals(0, transientBudget.active());
            assertFalse(session.tryQueueWorkerData(two));
            var delivered = session.pollWorkerData();
            assertNotNull(delivered);
            assertEquals(9L, delivered.messageSequence());
            byte[] copy = delivered.copyPayload(); copy[0] = 0;
            assertArrayEquals(new byte[]{7}, delivered.copyPayload());
            assertFalse(session.tryQueueWorkerData(two));
            delivered.close(); delivered.close();
            assertThrows(IllegalStateException.class, delivered::copyPayload);
            assertTrue(session.tryQueueWorkerData(two));
            assertEquals(two.length, retained.usedBytes());
            session.close();
            assertEquals(0, retained.usedBytes());
            assertEquals(0, retained.active());
            assertThrows(IllegalStateException.class, session::pollWorkerData);
        }
    }

    @Test
    void queuePoisonClearsRetainedCreditsAndRawDataCannotBypassBinding() {
        ActorArtifact<Object> artifact = artifact("ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (var session = ActorIpcSessionV1.create(artifact, UUID.randomUUID(), 1, 1);
             var bootstrap = session.claimBootstrapForTrustedLauncher()) {
            ready(session, bootstrap, artifact);
            var transientBudget = new ActorDataDecodeQuotaV1(65536, 2);
            var retained = new ActorDataDecodeQuotaV1(65536, 2);
            session.bindDataQueue(2, 4096, transientBudget, retained);
            byte[] one = worker(bootstrap, artifact, ActorIpcSessionV1.Lane.DATA,
                    ActorIpcSessionV1.FrameKind.OUTPUT, 1, 9, new byte[]{7});
            byte[] two = worker(bootstrap, artifact, ActorIpcSessionV1.Lane.DATA,
                    ActorIpcSessionV1.FrameKind.OUTPUT, 2, 10, new byte[]{8});
            assertTrue(session.tryQueueWorkerData(one));
            assertThrows(IllegalStateException.class,
                    () -> session.tryAcceptWorkerDataFrame(two, transientBudget));
            assertThrows(ActorIpcSessionV1.ProtocolException.class,
                    () -> session.acceptWorkerFrame(two));
            assertTrue(session.poisoned());
            assertEquals(0, retained.usedBytes());
            assertEquals(0, transientBudget.usedBytes());
        }
    }

    @Test
    void queueAuthenticationFailureRollsBackBothAccounts() {
        ActorArtifact<Object> artifact = artifact("ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (var session = ActorIpcSessionV1.create(artifact, UUID.randomUUID(), 1, 1);
             var bootstrap = session.claimBootstrapForTrustedLauncher()) {
            ready(session, bootstrap, artifact);
            var transientBudget = new ActorDataDecodeQuotaV1(65536, 2);
            var retained = new ActorDataDecodeQuotaV1(65536, 2);
            session.bindDataQueue(2, 4096, transientBudget, retained);
            byte[] one = worker(bootstrap, artifact, ActorIpcSessionV1.Lane.DATA,
                    ActorIpcSessionV1.FrameKind.OUTPUT, 1, 9, new byte[]{7});
            one[one.length-1] ^= 1;
            assertThrows(ActorIpcSessionV1.ProtocolException.class,
                    () -> session.tryQueueWorkerData(one));
            assertTrue(session.poisoned());
            assertEquals(0, retained.usedBytes());
            assertEquals(0, transientBudget.usedBytes());
        }
    }

    @Test
    void quotaBackpressureDoesNotConsumeSequenceAndAuthFailureReleasesCredit() {
        ActorArtifact<Object> artifact = artifact("ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            ready(session, bootstrap, artifact);
            ActorDataDecodeQuotaV1 quota = new ActorDataDecodeQuotaV1(64 * 1024, 1);
            byte[] output = worker(bootstrap, artifact, ActorIpcSessionV1.Lane.DATA,
                    ActorIpcSessionV1.FrameKind.OUTPUT, 1, 9, new byte[]{7});
            var held = quota.tryReserve(1);
            assertNotNull(held);
            assertNull(session.tryAcceptWorkerDataFrame(output, quota));
            assertFalse(session.poisoned());
            held.close();
            held.close();
            assertEquals(0, quota.active());
            assertEquals(0L, quota.usedBytes());
            var frame = session.tryAcceptWorkerDataFrame(output, quota);
            assertNotNull(frame);
            assertEquals(1L, frame.frameSequence());
            assertEquals(0L, quota.usedBytes());
            assertThrows(ActorIpcSessionV1.ProtocolException.class,
                    () -> session.tryAcceptWorkerDataFrame(output, quota));
            assertTrue(session.poisoned());
            assertEquals(0L, quota.usedBytes());
            assertEquals(0, quota.active());
        }
    }

    @Test
    void dataPathRejectsEvenAuthenticatedControlAndReleasesCredit() {
        ActorArtifact<Object> artifact = artifact("ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            ready(session, bootstrap, artifact);
            ActorDataDecodeQuotaV1 quota = new ActorDataDecodeQuotaV1(64 * 1024, 1);
            byte[] complete = worker(bootstrap, artifact, ActorIpcSessionV1.Lane.CONTROL,
                    ActorIpcSessionV1.FrameKind.COMPLETE, 2, 9, new byte[0]);
            assertThrows(ActorIpcSessionV1.ProtocolException.class,
                    () -> session.tryAcceptWorkerDataFrame(complete, quota));
            assertTrue(session.poisoned());
            assertEquals(0L, quota.usedBytes());
        }
    }

    @Test
    void sharedTenantQuotaCannotOverbookAcrossThreads() throws Exception {
        ActorDataDecodeQuotaV1 quota = new ActorDataDecodeQuotaV1(1024, 2);
        var held = quota.tryReserve(1024);
        assertNotNull(held);
        var other = new java.util.concurrent.FutureTask<>(() -> quota.tryReserve(1));
        Thread t = new Thread(other);
        t.start();
        assertNull(other.get(5, java.util.concurrent.TimeUnit.SECONDS));
        t.join();
        held.close();
        assertEquals(0L, quota.usedBytes());
        try (var a = quota.tryReserve(1); var b = quota.tryReserve(1)) {
            assertNotNull(a);
            assertNotNull(b);
            assertNull(quota.tryReserve(1));
        }
        assertEquals(0, quota.active());
        assertEquals(0L, quota.usedBytes());
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
    void readyDoesNotAskGuestToSelfAttestManifestOrSupervisorPolicy() {
        ActorArtifact<Object> expected = artifactWithReadyCommitments(
                "ImageWorker",
                MANIFEST,
                ARTIFACT,
                MESSAGE_ABI,
                STATE_SCHEMA,
                WIRE_CODEC,
                WORKER_POLICY);
        ActorArtifact<Object> worker = artifactWithReadyCommitments(
                "ImageWorker",
                "ab".repeat(32),
                ARTIFACT,
                MESSAGE_ABI,
                STATE_SCHEMA,
                WIRE_CODEC,
                "cd".repeat(32));

        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                expected, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            assertDoesNotThrow(() -> session.acceptWorkerFrame(worker(
                    bootstrap, worker,
                    ActorIpcSessionV1.Lane.CONTROL,
                    ActorIpcSessionV1.FrameKind.READY,
                    1, 0, new byte[0])));
            assertTrue(session.ready());
        }
    }

    @Test
    void readyRejectsWrongIntrinsicWorkerCommitments() {
        ActorArtifact<Object> expected = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        var wrongState = artifactWithReadyCommitments(
                "ImageWorker", MANIFEST, ARTIFACT, MESSAGE_ABI,
                "77".repeat(32), WIRE_CODEC, WORKER_POLICY);
        var wrongCodec = artifactWithReadyCommitments(
                "ImageWorker", MANIFEST, ARTIFACT, MESSAGE_ABI,
                STATE_SCHEMA, "88".repeat(32), WORKER_POLICY);
        var wrongType = artifactWithReadyCommitments(
                "OtherWorker", MANIFEST, ARTIFACT, MESSAGE_ABI,
                STATE_SCHEMA, WIRE_CODEC, WORKER_POLICY);

        for (ActorArtifact<?> wrong :
                java.util.List.of(wrongState, wrongCodec, wrongType)) {
            try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                    expected, UUID.randomUUID(), 1, 1);
                 ActorIpcSessionV1.BootstrapMaterial bootstrap =
                         session.claimBootstrapForTrustedLauncher()) {
                ActorIpcSessionV1.ProtocolException failure = assertThrows(
                        ActorIpcSessionV1.ProtocolException.class,
                        () -> session.acceptWorkerFrame(worker(
                                bootstrap, wrong,
                                ActorIpcSessionV1.Lane.CONTROL,
                                ActorIpcSessionV1.FrameKind.READY,
                                1, 0, new byte[0])));
                assertEquals(ActorIpcSessionV1.Reason.STATE, failure.reason());
                assertTrue(session.poisoned());
            }
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
    void inboundAndOutboundBuffersAreSnapshottedAcrossTrustBoundary() {
        ActorArtifact<Object> artifact = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            ready(session, bootstrap, artifact);

            byte[] workerPayload = new byte[]{10, 20, 30};
            byte[] wire = worker(
                    bootstrap, artifact,
                    ActorIpcSessionV1.Lane.DATA,
                    ActorIpcSessionV1.FrameKind.OUTPUT,
                    1, 9, workerPayload);
            ActorIpcSessionV1.Frame accepted = session.acceptWorkerFrame(wire);
            Arrays.fill(wire, (byte) 0);
            Arrays.fill(workerPayload, (byte) 0);
            assertArrayEquals(new byte[]{10, 20, 30}, accepted.payload(),
                    "accepted frame must own an immutable payload snapshot");

            byte[] supervisorPayload = new byte[]{1, 2, 3, 4};
            byte[] outbound = session.encodeSupervisorData(10, supervisorPayload);
            Arrays.fill(supervisorPayload, (byte) 0);
            int payloadLength = ByteBuffer.wrap(outbound)
                    .order(ByteOrder.BIG_ENDIAN)
                    .getInt(112);
            assertEquals(4, payloadLength);
            assertArrayEquals(
                    new byte[]{1, 2, 3, 4},
                    Arrays.copyOfRange(outbound, 116, 120),
                    "authenticated outbound frame must not retain caller payload storage");
        }
    }

    @Test
    void oversizedInboundFramePoisonsBeforeProtocolDecode() {
        ActorArtifact<Object> artifact = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            byte[] oversized = new byte[
                    116 + ActorIpcSessionV1.MAX_PAYLOAD_BYTES + 32 + 1];
            ActorIpcSessionV1.ProtocolException failure = assertThrows(
                    ActorIpcSessionV1.ProtocolException.class,
                    () -> session.acceptWorkerFrame(oversized));
            assertEquals(ActorIpcSessionV1.Reason.MALFORMED, failure.reason());
            assertTrue(session.poisoned());
        }
    }

    @Test
    void controlLaneHasIndependentSmallPayloadBudget() {
        ActorArtifact<Object> artifact = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);

        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            ready(session, bootstrap, artifact);

            byte[] oversizedControl =
                    new byte[ActorIpcSessionV1.MAX_CONTROL_PAYLOAD_BYTES + 1];
            byte[] workerFrame = worker(
                    bootstrap,
                    artifact,
                    ActorIpcSessionV1.Lane.CONTROL,
                    ActorIpcSessionV1.FrameKind.ERROR,
                    2,
                    0,
                    oversizedControl);

            ActorIpcSessionV1.ProtocolException inbound = assertThrows(
                    ActorIpcSessionV1.ProtocolException.class,
                    () -> session.acceptWorkerFrame(workerFrame));
            assertEquals(ActorIpcSessionV1.Reason.MALFORMED, inbound.reason());
            assertTrue(session.poisoned());
        }

        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            ready(session, bootstrap, artifact);
            byte[] oversizedControl =
                    new byte[ActorIpcSessionV1.MAX_CONTROL_PAYLOAD_BYTES + 1];
            assertThrows(
                    IllegalArgumentException.class,
                    () -> session.encodeSupervisorControl(
                            ActorIpcSessionV1.FrameKind.CONTROL,
                            0,
                            oversizedControl));
        }
    }

    @Test
    void completeFrameIsBodylessAndMessageBound() {
        ActorArtifact<Object> artifact = artifact(
                "ImageWorker", MESSAGE_ABI, ARTIFACT);
        try (ActorIpcSessionV1 session = ActorIpcSessionV1.create(
                artifact, UUID.randomUUID(), 1, 1);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            ready(session, bootstrap, artifact);

            ActorIpcSessionV1.ProtocolException payload = assertThrows(
                    ActorIpcSessionV1.ProtocolException.class,
                    () -> session.acceptWorkerFrame(worker(
                            bootstrap,
                            artifact,
                            ActorIpcSessionV1.Lane.CONTROL,
                            ActorIpcSessionV1.FrameKind.COMPLETE,
                            2,
                            5,
                            new byte[]{1})));
            assertEquals(ActorIpcSessionV1.Reason.STATE, payload.reason());
            assertTrue(session.poisoned());
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
        return artifactWithReadyCommitments(
                actorType,
                MANIFEST,
                artifactSha,
                messageAbi,
                STATE_SCHEMA,
                WIRE_CODEC,
                WORKER_POLICY);
    }

    private static ActorArtifact<Object> artifactWithReadyCommitments(
            String actorType,
            String manifestSha,
            String artifactSha,
            String messageAbi,
            String stateSchema,
            String wireCodec,
            String workerPolicy) {
        return ActorArtifact.mint(
                UUID.randomUUID(),
                UUID.randomUUID(),
                actorType,
                Ast.ActorKind.PRIVATE,
                manifestSha,
                artifactSha,
                messageAbi,
                stateSchema,
                wireCodec,
                workerPolicy,
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
