package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Small owner-checked persistence envelope for Java host facades implemented as
 * PRIVATE actors.
 *
 * <p>The only object retained by a private behavior is the ActorRuntime-owned
 * {@link ActorRuntime.PrivateMemoryBlock}. Mutable Java collections/resources
 * are decoded into turn-local values and written back as bytes before the turn
 * completes.</p>
 */
final class WorkflowPrivateActorStateV1 {

    private static final int LENGTH_BYTES = Integer.BYTES;

    private WorkflowPrivateActorStateV1() { }

    static byte[] read(ActorRuntime.PrivateMemoryBlock block) {
        Objects.requireNonNull(block, "block");
        byte[] bytes = block.copyOut();
        if (bytes.length < LENGTH_BYTES) {
            throw new IllegalStateException(
                    "private workflow state block is too small");
        }
        try {
            int length = ByteBuffer.wrap(bytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getInt();
            if (length < 0 || length > bytes.length - LENGTH_BYTES) {
                throw new IllegalStateException(
                        "private workflow state length is corrupt");
            }
            return Arrays.copyOfRange(
                    bytes, LENGTH_BYTES, LENGTH_BYTES + length);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static void write(
            ActorRuntime.PrivateMemoryBlock block,
            byte[] payload) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(payload, "payload");
        if (payload.length > block.capacity() - LENGTH_BYTES) {
            throw new IllegalStateException(
                    "private workflow actor state exceeds "
                            + block.capacity()
                            + " byte block");
        }
        byte[] bytes = new byte[block.capacity()];
        try {
            ByteBuffer.wrap(bytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(payload.length)
                    .put(payload);
            block.copyIn(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static final class ScrubbableOutput
            extends java.io.ByteArrayOutputStream {

        byte[] snapshotAndWipe() {
            try {
                return toByteArray();
            } finally {
                wipe();
            }
        }

        void wipe() {
            Arrays.fill(buf, (byte) 0);
            reset();
        }
    }

    static byte[] encodeUtf8(String value) {
        Objects.requireNonNull(value, "value");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(
                    "private workflow state string is not valid Unicode",
                    failure);
        }
    }

    static String decodeUtf8(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalStateException(
                    "private workflow state contains malformed UTF-8",
                    failure);
        }
    }

    static boolean empty(ActorRuntime.PrivateMemoryBlock block) {
        byte[] payload = read(block);
        try {
            return payload.length == 0;
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }
}
