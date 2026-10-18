package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * One-shot host-to-worker bootstrap record for IPC actor process v1.
 *
 * <p>This record intentionally contains only route/session keying material.
 * Artifact/ABI/policy commitments are not copied from the host expectation:
 * the worker must prove its own embedded metadata in the authenticated READY
 * frame.</p>
 */
final class ActorIpcBootstrapV1 {
    private ActorIpcBootstrapV1() { }

    static final byte[] MAGIC =
            "ORESIPCB1".getBytes(StandardCharsets.US_ASCII);
    static final int WIRE_BYTES =
            MAGIC.length
                    + Short.BYTES * 2
                    + 32
                    + 32
                    + Long.BYTES * 2
                    + Long.BYTES * 2
                    + Long.BYTES
                    + Long.BYTES
                    + Long.BYTES * 2;

    static byte[] encode(ActorIpcSessionV1.BootstrapMaterial bootstrap) {
        Objects.requireNonNull(bootstrap, "bootstrap");
        byte[] master = bootstrap.masterKey();
        byte[] challenge = bootstrap.challenge();
        try {
            ByteBuffer out = ByteBuffer.allocate(WIRE_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);
            out.put(MAGIC);
            out.putShort((short) ActorIpcSessionV1.PROTOCOL_MAJOR);
            out.putShort((short) ActorIpcSessionV1.PROTOCOL_MINOR);
            out.put(master);
            out.put(challenge);
            putUuid(out, bootstrap.logicalActorId());
            putUuid(out, bootstrap.codeGenerationId());
            out.putLong(bootstrap.incarnation());
            out.putLong(bootstrap.routeEpoch());
            putUuid(out, bootstrap.sessionId());
            if (out.hasRemaining()) {
                throw new AssertionError("IPC bootstrap wire size drift");
            }
            return out.array();
        } finally {
            Arrays.fill(master, (byte) 0);
            Arrays.fill(challenge, (byte) 0);
        }
    }

    private static void putUuid(ByteBuffer out, java.util.UUID value) {
        out.putLong(value.getMostSignificantBits());
        out.putLong(value.getLeastSignificantBits());
    }
}
