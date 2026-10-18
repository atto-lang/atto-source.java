package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ActorIpcSessionV1Test {

    @Test
    void derivesDistinctDirectionAndLaneSecretsAndRoundTripsFrames() {
        var context = context(3L, 7L, "ImageWorker");
        byte[] bootstrap = filled(32, (byte) 0x41);
        byte[] hostNonce = filled(32, (byte) 0x51);
        byte[] workerNonce = filled(32, (byte) 0x61);

        var session = ActorIpcSessionV1.establish(
                context,
                bootstrap,
                hostNonce,
                workerNonce);

        Set<String> keys = new HashSet<>();
        Set<String> sessions = new HashSet<>();
        for (ActorIpcSessionV1.ChannelSecrets channel : java.util.List.of(
                session.hostToWorkerData(),
                session.workerToHostData(),
                session.hostToWorkerControl(),
                session.workerToHostControl())) {
            keys.add(java.util.HexFormat.of().formatHex(channel.authenticationKey()));
            sessions.add(java.util.HexFormat.of().formatHex(channel.binding().sessionId()));
        }
        assertEquals(4, keys.size());
        assertEquals(4, sessions.size());

        byte[] wire = session.hostToWorkerData().encoder().encode(
                ActorIpcFrameV1.Kind.DATA,
                9L,
                "hello".getBytes(StandardCharsets.UTF_8));
        var decoded = session.hostToWorkerData().decoder().decode(wire);
        assertEquals(9L, decoded.messageSequence());
        assertEquals("hello", new String(decoded.payload(), StandardCharsets.UTF_8));

        byte[] control = session.workerToHostControl().encoder().encode(
                ActorIpcFrameV1.Kind.COMPLETE,
                9L,
                new byte[] {1});
        assertEquals(
                9L,
                session.workerToHostControl()
                        .decoder()
                        .decode(control)
                        .messageSequence());
    }

    @Test
    void derivationIsDeterministicButRouteAndNonceChangesInvalidateSession() {
        var context = context(2L, 5L, "ImageWorker");
        byte[] bootstrap = filled(32, (byte) 0x11);
        byte[] host = filled(32, (byte) 0x12);
        byte[] worker = filled(32, (byte) 0x13);

        var a = ActorIpcSessionV1.establish(context, bootstrap, host, worker);
        var b = ActorIpcSessionV1.establish(context, bootstrap, host, worker);

        assertArrayEquals(a.transcriptSha256(), b.transcriptSha256());
        assertArrayEquals(
                a.hostToWorkerData().authenticationKey(),
                b.hostToWorkerData().authenticationKey());
        assertArrayEquals(
                a.workerToHostControl().binding().sessionId(),
                b.workerToHostControl().binding().sessionId());

        var newerRoute = ActorIpcSessionV1.establish(
                context(2L, 6L, "ImageWorker"),
                bootstrap,
                host,
                worker);
        assertFalse(Arrays.equals(
                a.transcriptSha256(),
                newerRoute.transcriptSha256()));
        assertFalse(Arrays.equals(
                a.hostToWorkerData().authenticationKey(),
                newerRoute.hostToWorkerData().authenticationKey()));

        byte[] newWorkerNonce = worker.clone();
        newWorkerNonce[0] ^= 1;
        var newNonce = ActorIpcSessionV1.establish(
                context,
                bootstrap,
                host,
                newWorkerNonce);
        assertFalse(Arrays.equals(
                a.hostToWorkerData().authenticationKey(),
                newNonce.hostToWorkerData().authenticationKey()));
    }

    @Test
    void artifactActorAndAbiChangesAreTranscriptBound() {
        byte[] bootstrap = filled(32, (byte) 0x21);
        byte[] host = filled(32, (byte) 0x22);
        byte[] worker = filled(32, (byte) 0x23);
        var base = context(4L, 8L, "ImageWorker");
        var original = ActorIpcSessionV1.establish(
                base, bootstrap, host, worker);

        var actorNameChanged = ActorIpcSessionV1.establish(
                context(4L, 8L, "OtherWorker"),
                bootstrap,
                host,
                worker);
        assertFalse(Arrays.equals(
                original.transcriptSha256(),
                actorNameChanged.transcriptSha256()));

        byte[] receipt = base.signedArtifactReceiptSha256();
        receipt[0] ^= 1;
        var artifactChanged = new ActorIpcSessionV1.Context(
                base.logicalActorId(),
                base.incarnation(),
                base.routeEpoch(),
                base.actorTypeName(),
                receipt,
                base.artifactSha256(),
                base.messageAbiSha256(),
                base.stateSchemaSha256(),
                base.capabilityPolicySha256());
        var changed = ActorIpcSessionV1.establish(
                artifactChanged, bootstrap, host, worker);
        assertFalse(Arrays.equals(
                original.hostToWorkerControl().authenticationKey(),
                changed.hostToWorkerControl().authenticationKey()));
    }

    @Test
    void transcriptProofsRejectMutationAndCrossRoleUse() {
        var session = ActorIpcSessionV1.establish(
                context(1L, 1L, "Worker"),
                filled(32, (byte) 0x31),
                filled(32, (byte) 0x32),
                filled(32, (byte) 0x33));

        assertTrue(ActorIpcSessionV1.verifyHostProof(
                session, session.hostTranscriptProof()));
        assertTrue(ActorIpcSessionV1.verifyWorkerProof(
                session, session.workerTranscriptProof()));
        assertFalse(ActorIpcSessionV1.verifyHostProof(
                session, session.workerTranscriptProof()));
        assertFalse(ActorIpcSessionV1.verifyWorkerProof(
                session, session.hostTranscriptProof()));

        byte[] mutated = session.hostTranscriptProof();
        mutated[0] ^= 1;
        assertFalse(ActorIpcSessionV1.verifyHostProof(session, mutated));
    }

    @Test
    void zeroOrReflectedBootstrapMaterialFailsClosed() {
        var context = context(1L, 1L, "Worker");
        byte[] nonzero = filled(32, (byte) 0x44);

        assertThrows(
                IllegalArgumentException.class,
                () -> ActorIpcSessionV1.establish(
                        context,
                        new byte[32],
                        filled(32, (byte) 0x45),
                        filled(32, (byte) 0x46)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ActorIpcSessionV1.establish(
                        context,
                        nonzero,
                        new byte[32],
                        filled(32, (byte) 0x46)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ActorIpcSessionV1.establish(
                        context,
                        nonzero,
                        filled(32, (byte) 0x45),
                        new byte[32]));

        byte[] same = filled(32, (byte) 0x55);
        assertThrows(
                IllegalArgumentException.class,
                () -> ActorIpcSessionV1.establish(
                        context,
                        nonzero,
                        same,
                        same.clone()));
    }

    @Test
    void derivedSecretsAreDefensivelyCopied() {
        var session = ActorIpcSessionV1.establish(
                context(1L, 2L, "Worker"),
                filled(32, (byte) 0x61),
                filled(32, (byte) 0x62),
                filled(32, (byte) 0x63));

        byte[] key = session.hostToWorkerData().authenticationKey();
        byte original = session.hostToWorkerData().authenticationKey()[0];
        key[0] ^= 1;
        assertEquals(
                original,
                session.hostToWorkerData().authenticationKey()[0]);

        byte[] proof = session.workerTranscriptProof();
        byte proofOriginal = session.workerTranscriptProof()[0];
        proof[0] ^= 1;
        assertEquals(proofOriginal, session.workerTranscriptProof()[0]);
    }

    private static ActorIpcSessionV1.Context context(
            long incarnation,
            long routeEpoch,
            String actorType) {
        return new ActorIpcSessionV1.Context(
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                incarnation,
                routeEpoch,
                actorType,
                sha256("receipt".getBytes(StandardCharsets.UTF_8)),
                sha256("artifact".getBytes(StandardCharsets.UTF_8)),
                sha256("message".getBytes(StandardCharsets.UTF_8)),
                sha256("state".getBytes(StandardCharsets.UTF_8)),
                sha256("caps".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] filled(int size, byte value) {
        byte[] result = new byte[size];
        Arrays.fill(result, value);
        return result;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
