package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real compiler + Truffle execution of a persistent AttoLang KV guest. */
final class AttoRedisPersistentGuestTest {
    @Test
    void commandStreamExecutesInAttoMapAndPreservesHexBinaryValues() throws Exception {
        String code;
        try (InputStream resource = getClass().getResourceAsStream(
                "/atto/redis-bridge-v1/redis_stdio.atto")) {
            assertNotNull(resource, "canonical .atto guest fixture missing");
            byte[] bytes = resource.readAllBytes();
            assertTrue(bytes.length > 100 && bytes.length < 8192);
            code = new String(bytes, StandardCharsets.UTF_8);
        }
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code));
        // a NUL key, non-UTF8 value, empty live value and later tombstone.
        String input = "GET\n6100\nSET\n6100\n00ff\nGET\n6100\n"
                + "SET\n6200\n\nGET\n6200\nDEL\n6100\nGET\n6100\n";
        String expected = "READY\nNIL\nOK\nVALUE\n00ff\nOK\nVALUE\n\nONE\nNIL\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .in(new ByteArrayInputStream(input.getBytes(StandardCharsets.US_ASCII)))
                .out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, code,
                    "redis_stdio.atto").mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals(expected, output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        System.out.println("ATTO_REDIS_PERSISTENT_EPHEMERAL_GUEST=EXECUTED");
    }
}
