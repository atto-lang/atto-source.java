package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WebSocketFrameCodecTest {

    @Test
    void decodesMaskedTextWithoutLeakingMutablePayload() {
        byte[] wire = maskedFrame(true, WebSocketFrameCodec.OPCODE_TEXT,
                "hello".getBytes(StandardCharsets.UTF_8));
        WebSocketFrameCodec.Frame frame =
                WebSocketFrameCodec.decodeClientFrame(wire, 1024);

        assertTrue(frame.fin());
        assertEquals(WebSocketFrameCodec.OPCODE_TEXT, frame.opcode());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), frame.payload());

        byte[] leaked = frame.payload();
        leaked[0] = 'X';
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), frame.payload());
    }

    @Test
    void rejectsUnmaskedRsvReservedAndTrailingFrames() {
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketFrameCodec.decodeClientFrame(
                        new byte[] {(byte) 0x81, 0x00}, 16));

        byte[] rsv = maskedFrame(true, WebSocketFrameCodec.OPCODE_TEXT, new byte[0]);
        rsv[0] |= 0x40;
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketFrameCodec.decodeClientFrame(rsv, 16));

        byte[] reserved = maskedFrame(true, 0x3, new byte[0]);
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketFrameCodec.decodeClientFrame(reserved, 16));

        byte[] normal = maskedFrame(true, WebSocketFrameCodec.OPCODE_BINARY, new byte[] {1});
        byte[] trailing = Arrays.copyOf(normal, normal.length + 1);
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketFrameCodec.decodeClientFrame(trailing, 16));
    }

    @Test
    void rejectsControlFrameViolationsAndInvalidClosePayloads() {
        byte[] fragmentedPing =
                maskedFrame(false, WebSocketFrameCodec.OPCODE_PING, new byte[] {1});
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketFrameCodec.decodeClientFrame(fragmentedPing, 256));

        byte[] oversizedPing =
                maskedFrame(true, WebSocketFrameCodec.OPCODE_PING, new byte[126]);
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketFrameCodec.decodeClientFrame(oversizedPing, 256));

        byte[] oneByteClose =
                maskedFrame(true, WebSocketFrameCodec.OPCODE_CLOSE, new byte[] {1});
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketFrameCodec.decodeClientFrame(oneByteClose, 256));

        byte[] invalidCode =
                maskedFrame(true, WebSocketFrameCodec.OPCODE_CLOSE,
                        new byte[] {(byte) (1005 >>> 8), (byte) 1005});
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketFrameCodec.decodeClientFrame(invalidCode, 256));

        byte[] invalidReason =
                maskedFrame(true, WebSocketFrameCodec.OPCODE_CLOSE,
                        new byte[] {(byte) (1000 >>> 8), (byte) 1000, (byte) 0xc3, 0x28});
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketFrameCodec.decodeClientFrame(invalidReason, 256));
    }

    @Test
    void enforcesCanonicalLengthsAndConfiguredFrameLimitBeforePayloadUse() {
        byte[] nonCanonical = new byte[] {
                (byte) 0x82, (byte) (0x80 | 126), 0, 125,
                1, 2, 3, 4
        };
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketFrameCodec.decodeClientFrame(nonCanonical, 1024));

        byte[] payload = new byte[126];
        byte[] valid = maskedFrame(true, WebSocketFrameCodec.OPCODE_BINARY, payload);
        assertEquals(126,
                WebSocketFrameCodec.decodeClientFrame(valid, 126).payload().length);
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketFrameCodec.decodeClientFrame(valid, 125));

        byte[] nonCanonical64 = new byte[2 + 8 + 4];
        nonCanonical64[0] = (byte) 0x82;
        nonCanonical64[1] = (byte) (0x80 | 127);
        nonCanonical64[8] = 0x01;
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketFrameCodec.decodeClientFrame(nonCanonical64, 1_000_000));

        byte[] signedOverflow = new byte[2 + 8 + 4];
        signedOverflow[0] = (byte) 0x82;
        signedOverflow[1] = (byte) (0x80 | 127);
        signedOverflow[2] = (byte) 0x80;
        assertThrows(IllegalArgumentException.class, () ->
                WebSocketFrameCodec.decodeClientFrame(signedOverflow, 1_000_000));
    }

    @Test
    void assemblesFragmentedUtf8AcrossFrameBoundariesAndIgnoresControlFrames() {
        byte[] emoji = "🙂".getBytes(StandardCharsets.UTF_8);
        WebSocketFrameCodec.MessageAssembler assembler =
                new WebSocketFrameCodec.MessageAssembler(16);

        assertTrue(assembler.accept(new WebSocketFrameCodec.Frame(
                false, WebSocketFrameCodec.OPCODE_TEXT,
                Arrays.copyOfRange(emoji, 0, 2))).isEmpty());
        assertTrue(assembler.fragmentedMessagePending());

        assertTrue(assembler.accept(new WebSocketFrameCodec.Frame(
                true, WebSocketFrameCodec.OPCODE_PING,
                new byte[] {9})).isEmpty());
        assertTrue(assembler.fragmentedMessagePending());

        var completed = assembler.accept(new WebSocketFrameCodec.Frame(
                true, WebSocketFrameCodec.OPCODE_CONTINUATION,
                Arrays.copyOfRange(emoji, 2, emoji.length)));
        assertTrue(completed.isPresent());
        assertEquals(WebSocketFrameCodec.OPCODE_TEXT, completed.orElseThrow().opcode());
        assertArrayEquals(emoji, completed.orElseThrow().payload());
        assertFalse(assembler.fragmentedMessagePending());
    }

    @Test
    void fragmentedMessagesFailClosedOnOrderingUtf8AndSize() {
        WebSocketFrameCodec.MessageAssembler assembler =
                new WebSocketFrameCodec.MessageAssembler(4);

        assertThrows(IllegalArgumentException.class, () ->
                assembler.accept(new WebSocketFrameCodec.Frame(
                        true, WebSocketFrameCodec.OPCODE_CONTINUATION, new byte[] {1})));

        assembler.accept(new WebSocketFrameCodec.Frame(
                false, WebSocketFrameCodec.OPCODE_TEXT, new byte[] {(byte) 0xc3}));
        assertThrows(IllegalArgumentException.class, () ->
                assembler.accept(new WebSocketFrameCodec.Frame(
                        true, WebSocketFrameCodec.OPCODE_CONTINUATION, new byte[] {0x28})));
        assertFalse(assembler.fragmentedMessagePending());

        assembler.accept(new WebSocketFrameCodec.Frame(
                false, WebSocketFrameCodec.OPCODE_BINARY, new byte[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () ->
                assembler.accept(new WebSocketFrameCodec.Frame(
                        true, WebSocketFrameCodec.OPCODE_CONTINUATION, new byte[] {4, 5})));
        assertFalse(assembler.fragmentedMessagePending());

        assertThrows(IllegalArgumentException.class, () -> {
            assembler.accept(new WebSocketFrameCodec.Frame(
                    false, WebSocketFrameCodec.OPCODE_BINARY, new byte[] {1}));
            assembler.accept(new WebSocketFrameCodec.Frame(
                    true, WebSocketFrameCodec.OPCODE_TEXT, new byte[] {2}));
        });
        assembler.reset();
    }

    @Test
    void serverEncodingIsUnmaskedCanonicalAndBounded() {
        byte[] small = WebSocketFrameCodec.encodeServerFrame(
                true, WebSocketFrameCodec.OPCODE_TEXT,
                "ok".getBytes(StandardCharsets.UTF_8), 16);
        assertEquals(0, small[1] & 0x80);
        assertEquals(2, small[1] & 0x7f);

        byte[] medium = WebSocketFrameCodec.encodeServerFrame(
                true, WebSocketFrameCodec.OPCODE_BINARY, new byte[126], 126);
        assertEquals(126, medium[1] & 0x7f);
        assertEquals(0, medium[2]);
        assertEquals(126, Byte.toUnsignedInt(medium[3]));

        assertThrows(IllegalArgumentException.class, () ->
                WebSocketFrameCodec.encodeServerFrame(
                        true, WebSocketFrameCodec.OPCODE_BINARY, new byte[5], 4));
    }

    private static byte[] maskedFrame(boolean fin, int opcode, byte[] payload) {
        int lengthBytes = payload.length <= 125 ? 0 : payload.length <= 0xffff ? 2 : 8;
        int header = 2 + lengthBytes + 4;
        byte[] wire = new byte[header + payload.length];
        wire[0] = (byte) ((fin ? 0x80 : 0) | opcode);

        int cursor;
        if (payload.length <= 125) {
            wire[1] = (byte) (0x80 | payload.length);
            cursor = 2;
        } else if (payload.length <= 0xffff) {
            wire[1] = (byte) (0x80 | 126);
            wire[2] = (byte) (payload.length >>> 8);
            wire[3] = (byte) payload.length;
            cursor = 4;
        } else {
            wire[1] = (byte) (0x80 | 127);
            long length = payload.length;
            for (int i = 0; i < 8; i++) {
                wire[2 + i] = (byte) (length >>> (56 - 8 * i));
            }
            cursor = 10;
        }

        byte[] mask = new byte[] {0x11, 0x22, 0x33, 0x44};
        System.arraycopy(mask, 0, wire, cursor, mask.length);
        cursor += 4;
        for (int i = 0; i < payload.length; i++) {
            wire[cursor + i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        return wire;
    }
}
