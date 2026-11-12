package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Compiler-runtime binary value security and NIO RESP2 correctness. */
final class OresBytesNioResp2Test {
    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] frame(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("*" + parts.length + "\r\n"));
        for (byte[] part : parts) {
            out.write(ascii("$" + part.length + "\r\n"));
            out.write(part);
            out.write(ascii("\r\n"));
        }
        return out.toByteArray();
    }

    @Test
    void immutableBinaryValueHasDeterministicContentIdentityWithoutBackingLeak() {
        byte[] source = {0, (byte) 255, 13, 10};
        OresBytes value = OresBytes.copyOf(source);
        OresBytes same = OresBytes.copyOf(source);
        int hash = value.hashCode();
        source[0] = 42;
        assertEquals(0, value.unsignedAt(0));
        byte[] outbound = value.copyToArray();
        outbound[1] = 0;
        assertEquals(255, value.unsignedAt(1));
        assertEquals(same, value);
        assertEquals(hash, value.hashCode());
        assertEquals(same.hashCode(), value.hashCode());
        assertEquals(4, value.size());
        assertFalse(value.toString().contains("255"));
        assertThrows(IllegalArgumentException.class,
                () -> OresBytes.copyOf(new byte[OresBytes.MAX_LENGTH + 1]));
    }

    @Test
    void parsesBinaryNulInvalidUtf8CrLfAndEmptyValueWithoutHex() throws Exception {
        byte[] key = {0, (byte) 0xff, 13, 10};
        byte[] value = {(byte) 0xfe, 0, 10, 13, 42};
        byte[] packed = frame(ascii("SET"), key, value);
        ByteBuffer input = ByteBuffer.wrap(packed);
        AsyncNioResp2.BinaryParsed parsed = AsyncNioResp2.parseBinary(input);
        assertNotNull(parsed);
        assertEquals("SET", parsed.opcode());
        assertEquals(packed.length, parsed.consumed());
        assertArrayEquals(key, parsed.arguments().get(0).copyToArray());
        assertArrayEquals(value, parsed.arguments().get(1).copyToArray());
        assertEquals(0, input.position(), "binary parser must not consume incomplete/pipelined input");

        byte[] empty = frame(ascii("SET"), key, new byte[0]);
        AsyncNioResp2.BinaryParsed decoded =
                AsyncNioResp2.parseBinary(ByteBuffer.wrap(empty));
        assertNotNull(decoded);
        assertEquals(0, decoded.arguments().get(1).size());
        assertArrayEquals(ascii("$0\r\n\r\n"),
                AsyncNioResp2.encodeBinary("VALUE", decoded.arguments().get(1)));
        assertArrayEquals(new byte[]{'$', '5', '\r', '\n',
                (byte) 0xfe, 0, 10, 13, 42, '\r', '\n'},
                AsyncNioResp2.encodeBinary("VALUE", parsed.arguments().get(1)));
    }

    @Test
    void handlesFragmentationAndPipelinedFramesWithoutCorruptingByteOwnership()
            throws Exception {
        byte[] first = frame(ascii("GET"), new byte[]{0, (byte) 255});
        byte[] second = frame(ascii("PING"));
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        ByteBuffer incomplete = ByteBuffer.wrap(joined, 0, first.length - 1);
        assertNull(AsyncNioResp2.parseBinary(incomplete));
        assertEquals(0, incomplete.position());
        ByteBuffer all = ByteBuffer.wrap(joined);
        AsyncNioResp2.BinaryParsed one = AsyncNioResp2.parseBinary(all);
        assertNotNull(one);
        assertEquals(first.length, one.consumed());
        OresBytes key = one.arguments().get(0);
        all.position(one.consumed());
        AsyncNioResp2.BinaryParsed two = AsyncNioResp2.parseBinary(all);
        assertNotNull(two);
        assertEquals("PING", two.opcode());
        assertTrue(two.arguments().isEmpty());
        Arrays.fill(joined, (byte) 3); // simulated reused incoming NIO buffer
        assertArrayEquals(new byte[]{0, (byte) 255}, key.copyToArray());
    }

    @Test
    void rejectsOversizedInvalidLengthsAndBadTrailersWithoutAllocatingPayload()
            throws Exception {
        assertThrows(IOException.class, () -> AsyncNioResp2.parseBinary(
                ByteBuffer.wrap(ascii("*2\r\n$3\r\nGET\r\n$4097\r\n"))));
        assertThrows(IOException.class, () -> AsyncNioResp2.parseBinary(
                ByteBuffer.wrap(ascii("*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1048577\r\n"))));
        assertThrows(IOException.class, () -> AsyncNioResp2.parseBinary(
                ByteBuffer.wrap(ascii("*2\r\n$3\r\nGET\r\n$1\r\nx\n\n"))));
        assertThrows(IOException.class, () -> AsyncNioResp2.parseBinary(
                ByteBuffer.wrap(ascii("*1\r\n$03\r\nP\nG\r\n"))));
        assertThrows(IOException.class, () -> AsyncNioResp2.parseBinary(
                ByteBuffer.wrap(ascii("*4\r\n"))));
        assertThrows(IOException.class, () -> AsyncNioResp2.parseBinary(
                ByteBuffer.wrap(ascii("*1\r\n$2\r\n\u0000G\r\n"))));
    }

    @Test
    void failsClosedOnSpoofedRepliesAndNonValuePayloads() throws Exception {
        assertArrayEquals(ascii("+OK\r\n"),
                AsyncNioResp2.encodeBinary("OK", null));
        assertArrayEquals(ascii("$-1\r\n"),
                AsyncNioResp2.encodeBinary("NIL", null));
        assertThrows(IOException.class,
                () -> AsyncNioResp2.encodeBinary("VALUE", null));
        assertThrows(IOException.class,
                () -> AsyncNioResp2.encodeBinary("OK", OresBytes.copyOf(new byte[]{1})));
        assertThrows(IOException.class,
                () -> AsyncNioResp2.encodeBinary("VALUE:00", OresBytes.copyOf(new byte[]{0})));
        assertThrows(IOException.class,
                () -> AsyncNioResp2.encodeBinary("VALUE\r\n+OK", OresBytes.copyOf(new byte[]{0})));
    }
}
