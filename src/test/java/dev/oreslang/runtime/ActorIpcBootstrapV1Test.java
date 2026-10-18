package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ActorIpcBootstrapV1Test {

    @Test
    void bootstrapCarriesOnlyOneShotRouteMaterialAndCodeGenerationIdentity() {
        UUID runtimeId = UUID.randomUUID();
        UUID codeGenerationId = UUID.randomUUID();
        UUID logicalActorId = UUID.randomUUID();
        ActorArtifact<Object> artifact = ActorArtifact.mint(
                runtimeId,
                codeGenerationId,
                "Worker",
                Ast.ActorKind.PRIVATE,
                "11".repeat(32),
                "22".repeat(32),
                "33".repeat(32),
                "44".repeat(32),
                "55".repeat(32),
                "66".repeat(32),
                "llvm",
                "1.0",
                "a".repeat(40),
                "x86_64-linux-gnu",
                "ores-ipc-v1",
                "builder",
                "release",
                Set.of(),
                new byte[] {1, 2, 3});

        try (ActorIpcSessionV1 session =
                     ActorIpcSessionV1.create(
                             artifact,
                             logicalActorId,
                             7,
                             13);
             ActorIpcSessionV1.BootstrapMaterial bootstrap =
                     session.claimBootstrapForTrustedLauncher()) {
            byte[] expectedMaster = bootstrap.masterKey();
            byte[] expectedChallenge = bootstrap.challenge();
            byte[] wire = ActorIpcBootstrapV1.encode(bootstrap);
            try {
                assertEquals(140, ActorIpcBootstrapV1.WIRE_BYTES);
                assertEquals(
                        ActorIpcBootstrapV1.WIRE_BYTES,
                        wire.length);

                ByteBuffer in = ByteBuffer.wrap(wire)
                        .order(ByteOrder.BIG_ENDIAN);
                byte[] magic =
                        new byte[ActorIpcBootstrapV1.MAGIC.length];
                in.get(magic);
                assertArrayEquals(ActorIpcBootstrapV1.MAGIC, magic);
                assertEquals(
                        ActorIpcSessionV1.PROTOCOL_MAJOR,
                        Short.toUnsignedInt(in.getShort()));
                assertEquals(
                        ActorIpcSessionV1.PROTOCOL_MINOR,
                        Short.toUnsignedInt(in.getShort()));

                byte[] master = new byte[32];
                byte[] challenge = new byte[32];
                in.get(master);
                in.get(challenge);
                assertArrayEquals(expectedMaster, master);
                assertArrayEquals(expectedChallenge, challenge);
                assertEquals(logicalActorId, getUuid(in));
                assertEquals(codeGenerationId, getUuid(in));
                assertEquals(7L, in.getLong());
                assertEquals(13L, in.getLong());
                assertEquals(session.sessionId(), getUuid(in));
                assertFalse(in.hasRemaining());

                // Host expectations belong to authenticated READY, not the
                // bootstrap channel. In particular the admitted artifact
                // digest is not copied into worker bootstrap material.
                assertFalse(
                        contains(
                                wire,
                                hex("22".repeat(32))));
                Arrays.fill(master, (byte) 0);
                Arrays.fill(challenge, (byte) 0);
            } finally {
                Arrays.fill(expectedMaster, (byte) 0);
                Arrays.fill(expectedChallenge, (byte) 0);
                Arrays.fill(wire, (byte) 0);
            }
        }
    }

    private static UUID getUuid(ByteBuffer in) {
        return new UUID(in.getLong(), in.getLong());
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(
                    value.substring(i * 2, i * 2 + 2),
                    16);
        }
        return result;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
