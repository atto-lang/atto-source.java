package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Security/interop checks for the one-actor-output async NIO2 reply ABI. */
final class AsyncNioResp2CompositeTest {
    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void canonicalSimpleAndBinaryReplies() throws Exception {
        assertArrayEquals(ascii("+OK\r\n"), AsyncNioResp2.encodeCompositeReply("OK"));
        assertArrayEquals(ascii("+PONG\r\n"), AsyncNioResp2.encodeCompositeReply("PONG"));
        assertArrayEquals(ascii("$-1\r\n"), AsyncNioResp2.encodeCompositeReply("NIL"));
        assertArrayEquals(ascii(":1\r\n"), AsyncNioResp2.encodeCompositeReply("ONE"));
        assertArrayEquals(ascii(":0\r\n"), AsyncNioResp2.encodeCompositeReply("ZERO"));
        assertArrayEquals(ascii("-ERR unsupported command\r\n"),
                AsyncNioResp2.encodeCompositeReply("ERR"));
        assertArrayEquals(ascii("$0\r\n\r\n"),
                AsyncNioResp2.encodeCompositeReply("VALUE:"));
        assertArrayEquals(new byte[] {'$', '4', '\r', '\n',
                (byte) 0xff, 0, '\r', '\n', '\r', '\n'},
                AsyncNioResp2.encodeCompositeReply("VALUE:ff000d0a"));
    }

    @Test
    void rejectsForgedMalformedOrOversizedActorReplyWithoutBlocking() {
        for (String malformed : new String[] {
                "VALUE", "VALUE:0", "VALUE:xx", "VALUE:ff00zz", "VALUE:af\n",
                "OK:ffff", "PONG:ff", "NIL:anything", "VALUE:0f\r\n+OK",
                "ONE:00", "BAD", "", "value:00"
        }) {
            assertThrows(IOException.class,
                    () -> AsyncNioResp2.encodeCompositeReply(malformed),
                    "must fail-closed: " + malformed.replace("\n", "\\n"));
        }
        assertThrows(IOException.class,
                () -> AsyncNioResp2.encodeCompositeReply(null));
        assertThrows(IOException.class,
                () -> AsyncNioResp2.encodeCompositeReply(
                        "VALUE:" + "aa".repeat(AsyncNioResp2.MAX_VALUE + 1)));
    }
}
