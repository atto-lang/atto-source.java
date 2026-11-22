package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OresBytesNioResp2Test {
    private static byte[] ascii(String v) {
        return v.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] frame(byte[]... args) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("*" + args.length + "\r\n"));
        for (byte[] arg : args) {
            out.write(ascii("$" + arg.length + "\r\n"));
            out.write(arg);
            out.write(ascii("\r\n"));
        }
        return out.toByteArray();
    }

    @Test
    void actualOptimizingTruffleProviderIsRequiredByPerformanceGate() {
        if ("1".equals(System.getenv("ATTO_REQUIRE_OPTIMIZING_TRUFFLE"))) {
            try (Engine engine = Engine.create()) {
                assertTrue(engine.supportsCompilation(),
                        "Atto performance gate requires a real optimizing Truffle engine");
            }
        }
    }

    @Test
    void bytesHaveImmutableContentIdentityAndNoBackingAlias() {
        byte[] raw = {0, (byte) 255, 13, 10};
        OresBytes value = OresBytes.copyOf(raw);
        OresBytes equivalent = OresBytes.copyOf(raw);
        int hash = value.hashCode();
        raw[0] = 42;
        value.toArrayCopy()[1] = 0;
        assertEquals(0, value.byteAt(0));
        assertEquals(255, value.byteAt(1));
        assertEquals(equivalent, value);
        assertEquals(hash, value.hashCode());
        assertEquals(equivalent.hashCode(), hash);
        assertThrows(IllegalArgumentException.class,
                () -> OresBytes.copyOf(new byte[OresBytes.MAX_LENGTH + 1]));
    }

    @Test
    void binaryParserPreservesNulInvalidUtf8CrLfAndEmptyValues() throws Exception {
        byte[] key = {0, (byte) 255, 13, 10};
        byte[] value = {(byte) 254, 0, 10, 13, 42};
        byte[] command = frame(ascii("SET"), key, value);
        ByteBuffer input = ByteBuffer.wrap(command);
        AsyncNioResp2.BinaryParsed parsed = AsyncNioResp2.parseBinary(input);
        assertNotNull(parsed);
        assertEquals("SET", parsed.opcode());
        assertEquals(command.length, parsed.consumed());
        assertEquals(0, input.position());
        assertArrayEquals(key, parsed.bulk().get(0).toArrayCopy());
        assertArrayEquals(value, parsed.bulk().get(1).toArrayCopy());
        assertArrayEquals(new byte[]{'$', '5', '\r', '\n',
                (byte) 254, 0, 10, 13, 42, '\r', '\n'},
                AsyncNioResp2.encodeBinary("VALUE", parsed.bulk().get(1)));
        AsyncNioResp2.BinaryParsed empty = AsyncNioResp2.parseBinary(
                ByteBuffer.wrap(frame(ascii("SET"), key, new byte[0])));
        assertNotNull(empty);
        assertEquals(0, empty.bulk().get(1).size());
        assertArrayEquals(ascii("$0\r\n\r\n"),
                AsyncNioResp2.encodeBinary("VALUE", empty.bulk().get(1)));
    }

    @Test
    void parserHandlesFragmentedAndPipelinedFramesWithDetachedOwnership()
            throws Exception {
        byte[] first = frame(ascii("GET"), new byte[]{0, (byte) 255});
        byte[] second = frame(ascii("PING"));
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        ByteBuffer partial = ByteBuffer.wrap(joined, 0, first.length - 1);
        assertNull(AsyncNioResp2.parseBinary(partial));
        assertEquals(0, partial.position());
        ByteBuffer full = ByteBuffer.wrap(joined);
        AsyncNioResp2.BinaryParsed one = AsyncNioResp2.parseBinary(full);
        assertNotNull(one);
        assertEquals(first.length, one.consumed());
        OresBytes key = one.bulk().get(0);
        full.position(one.consumed());
        AsyncNioResp2.BinaryParsed two = AsyncNioResp2.parseBinary(full);
        assertNotNull(two);
        assertEquals("PING", two.opcode());
        assertTrue(two.bulk().isEmpty());
        Arrays.fill(joined, (byte) 3);
        assertArrayEquals(new byte[]{0, (byte) 255}, key.toArrayCopy());
    }

    @Test
    void oversizedHeaderAndInvalidTrailersFailClosed() {
        assertThrows(IOException.class, () -> AsyncNioResp2.parseBinary(
                ByteBuffer.wrap(ascii("*2\r\n$3\r\nGET\r\n$4097\r\n"))));
        assertThrows(IOException.class, () -> AsyncNioResp2.parseBinary(
                ByteBuffer.wrap(ascii("*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1048577\r\n"))));
        assertThrows(IOException.class, () -> AsyncNioResp2.parseBinary(
                ByteBuffer.wrap(ascii("*2\r\n$3\r\nGET\r\n$1\r\nx\n\n"))));
        assertThrows(IOException.class, () -> AsyncNioResp2.parseBinary(
                ByteBuffer.wrap(ascii("*4\r\n"))));
        byte[] invalidOpcode = {'*', '1', '\r', '\n', '$', '2', '\r', '\n', 0, 'G', '\r', '\n'};
        assertThrows(IOException.class, () -> AsyncNioResp2.parseBinary(
                ByteBuffer.wrap(invalidOpcode)));
    }

    @Test
    void rejectsForgedRepliesAndNonValueBinaryPayload() throws Exception {
        assertArrayEquals(ascii("+OK\r\n"), AsyncNioResp2.encodeBinary("OK", null));
        assertArrayEquals(ascii("$-1\r\n"), AsyncNioResp2.encodeBinary("NIL", null));
        assertThrows(IOException.class, () -> AsyncNioResp2.encodeBinary("VALUE", null));
        assertThrows(IOException.class, () -> AsyncNioResp2.encodeBinary(
                "OK", OresBytes.copyOf(new byte[]{1})));
        assertThrows(IOException.class, () -> AsyncNioResp2.encodeBinary(
                "VALUE:00", OresBytes.copyOf(new byte[]{0})));
        assertThrows(IOException.class, () -> AsyncNioResp2.encodeBinary(
                "VALUE\r\n+OK", OresBytes.copyOf(new byte[]{0})));
    }
}
