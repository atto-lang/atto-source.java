package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Real compiler + Truffle actor guest: actual Map is owned by a spawned actor. */
final class AttoRedisActorPersistentGuestTest {
    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void actorOwnsKeyValueStateAndRespondsToPersistentStdinCommands() throws Exception {
        String code;
        try (InputStream resource = getClass().getResourceAsStream(
                "/atto/redis-bridge-v1/redis_actor_stdio.atto")) {
            assertNotNull(resource, "canonical actor .atto fixture missing");
            byte[] bytes = resource.readAllBytes();
            assertTrue(bytes.length > 100 && bytes.length < 8192);
            code = new String(bytes, StandardCharsets.UTF_8);
        }
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code));
        String input = "GET\n6100\nSET\n6100\n00ff\nGET\n6100\n"
                + "SET\n6200\n\nGET\n6200\nDEL\n6100\nGET\n6100\n";
        String expected = "READY\nNIL\nOK\nVALUE\n00ff\nOK\nVALUE\n\nONE\nNIL\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .in(new ByteArrayInputStream(input.getBytes(StandardCharsets.US_ASCII)))
                .out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, code,
                    "redis_actor_stdio.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals(expected, output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        System.out.println("ATTO_REDIS_ACTOR_KV_REAL_GUEST=EXECUTED_PASS");
    }
    // Each test below executes the same canonical .atto actor through the
    // actual type checker and Truffle guest. No host-side KV implementation.
    private static String executeGuest(String input) throws Exception {
        String code;
        try (InputStream resource = AttoRedisActorPersistentGuestTest.class
                .getResourceAsStream("/atto/redis-bridge-v1/redis_actor_stdio.atto")) {
            assertNotNull(resource);
            code = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .in(new ByteArrayInputStream(input.getBytes(StandardCharsets.US_ASCII)))
                .out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, code, "redis_actor_stdio.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void overwriteIsLastWriteWinsAndDeleteIsIdempotent() throws Exception {
        String input = "SET\n6b\n3131\nSET\n6b\n3232\nGET\n6b\n"
                + "DEL\n6b\nDEL\n6b\nGET\n6b\n";
        assertEquals("READY\nOK\nOK\nVALUE\n3232\nONE\nZERO\nNIL\n",
                executeGuest(input));
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void binaryHexKeysAndEmptyValueStayIsolated() throws Exception {
        String input = "SET\n6100\n\nSET\n6200\nff00\nGET\n6100\n"
                + "GET\n6200\nDEL\n6100\nGET\n6200\n";
        String expected = "READY\nOK\nOK\nVALUE\n\nVALUE\nff00\nONE\nVALUE\nff00\n";
        assertEquals(expected, executeGuest(input));
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void mailboxProcessesManySequentialMixedCommandsWithoutReordering() throws Exception {
        StringBuilder input = new StringBuilder();
        StringBuilder expected = new StringBuilder("READY\n");
        for (int i = 0; i < 48; i++) {
            String key = String.format("%04x", i + 1);
            String value = String.format("%04x", i + 42);
            input.append("SET\n").append(key).append('\n').append(value).append('\n');
            input.append("GET\n").append(key).append('\n');
            input.append("DEL\n").append(key).append('\n');
            input.append("GET\n").append(key).append('\n');
            expected.append("OK\nVALUE\n").append(value).append("\nONE\nNIL\n");
        }
        assertEquals(expected.toString(), executeGuest(input.toString()));
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void freshAttoGuestContextDoesNotRetainPriorActorKeyspace() throws Exception {
        assertEquals("READY\nOK\n", executeGuest("SET\n6b\n3131\n"));
        assertEquals("READY\nNIL\n", executeGuest("GET\n6b\n"));
    }

}
