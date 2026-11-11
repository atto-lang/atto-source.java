package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class AsyncNioResp2Test {
    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    @Test void incrementalFramingPreservesBinaryAndPipelining() throws Exception {
        byte[] bytes = new byte[] {
                '*','3','\r','\n',
                '$','3','\r','\n','S','E','T','\r','\n',
                '$','1','\r','\n', (byte) 0xff,'\r','\n',
                '$','2','\r','\n',0,(byte)0xf0,'\r','\n'};
        byte[] second = ascii("*1\r\n$4\r\nPING\r\n");
        byte[] combined = new byte[bytes.length + second.length];
        System.arraycopy(bytes,0,combined,0,bytes.length);
        System.arraycopy(second,0,combined,bytes.length,second.length);
        for (int n=0; n<bytes.length; n++) {
            assertNull(AsyncNioResp2.parse(ByteBuffer.wrap(combined,0,n)),
                    "partial request must not be interpreted as valid complete command " + n);
        }
        ByteBuffer source = ByteBuffer.wrap(combined);
        var parsed = AsyncNioResp2.parse(source);
        assertNotNull(parsed);
        assertEquals(java.util.List.of("SET","ff","00f0"),parsed.parts());
        assertEquals(bytes.length,parsed.consumed());
        source.position(parsed.consumed());
        var pipelined = AsyncNioResp2.parse(source);
        assertEquals(java.util.List.of("PING"),pipelined.parts());
        assertEquals(combined.length,pipelined.consumed());
    }

    @Test void rejectsOversizedKeyBeforeReadingBody() {
        for (String malformed : new String[] {
                "*3\r\n$3\r\nSET\r\n$4097\r\n",
                "*3\r\n$3\r\nSET\r\n$-1\r\n",
                "*2\r\n$3\r\nGET\r\n$999999999999999999999\r\n",
                "*4\r\n",
                "*9\r\n",
                "*2147483648\r\n",
                "*1\r\n$17\r\n",
                "*2\r\n$3\r\nGET\r\n$4097\r\n",
                "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1048577\r\n",
                "*1\n",
                "*1\r\n$0\r\n\r\n",
                "*1\r\n$4\r\nPI\nG\r\n",
                "*1\r\n$3\r\nB\177!\r\n",
                "*1\r\n$3\r\n\0AA\r\n"
        }) {
            assertThrows(IOException.class, () ->
                    AsyncNioResp2.parse(ByteBuffer.wrap(ascii(malformed))),
                    "must reject malformed input: " + malformed);
        }
    }

    @Test void rejectsBadTrailerAndAllowsEmptyValues() throws Exception {
        assertThrows(IOException.class, () -> AsyncNioResp2.parse(
                ByteBuffer.wrap(ascii("*2\r\n$3\r\nGET\r\n$1\r\nx!!"))));
        var empty = AsyncNioResp2.parse(ByteBuffer.wrap(
                ascii("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$0\r\n\r\n")));
        assertEquals(java.util.List.of("SET","6b",""),empty.parts());
    }

    @Test void repliesDistinguishNilEmptyBinaryAndDelete() throws Exception {
        assertArrayEquals(ascii("$-1\r\n"),AsyncNioResp2.encode("NIL",""));
        assertArrayEquals(ascii("$0\r\n\r\n"),AsyncNioResp2.encode("VALUE",""));
        byte[] expected = { '$','3','\r','\n',0,(byte)255,(byte)0xfe,'\r','\n'};
        assertArrayEquals(expected,AsyncNioResp2.encode("VALUE","00fffe"));
        assertArrayEquals(ascii(":1\r\n"),AsyncNioResp2.encode("ONE",""));
        assertArrayEquals(ascii(":0\r\n"),AsyncNioResp2.encode("ZERO",""));
        assertThrows(IOException.class,()->AsyncNioResp2.encode("VALUE","f"));
        assertThrows(IOException.class,()->AsyncNioResp2.encode("VALUE","zz"));
        assertThrows(IOException.class,()->AsyncNioResp2.encode("DANGEROUS",""));
    }

    @Test void refusesResponsePayloadBeyondBudget() {
        assertThrows(IOException.class,()->AsyncNioResp2.encode(
                "VALUE","ab".repeat(AsyncNioResp2.MAX_VALUE+1)));
    }
}
