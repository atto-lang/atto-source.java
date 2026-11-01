package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The first portable actor execution slice: spawn -> ready -> send ->
 * receive -> output -> stop -> done. This deliberately does not assert
 * the proposed state{} loan syntax or inheritance/runtime trait dispatch.
 */
final class SourceActorLifecycleBaselineTest {
    private static final String RESOURCE =
            "/fixtures/actor-execution/v1/spawn-send-receive-stop.ores";
    private static final String EXACT_GIT_BLOB =
            "7b358d4871f931f21d1a874a15802ac3dd32ba64";

    private static byte[] pinnedSource() throws Exception {
        byte[] bytes;
        try (InputStream stream = SourceActorLifecycleBaselineTest.class
                .getResourceAsStream(RESOURCE)) {
            assertNotNull(stream, "missing actor lifecycle source fixture");
            bytes = stream.readAllBytes();
        }

        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        sha.update(bytes);
        assertEquals(EXACT_GIT_BLOB, HexFormat.of().formatHex(sha.digest()),
                "the JVM guest source must exactly match the shared conformance fixture");
        return bytes;
    }

    @Test
    void portableLifecycleMustPassStaticTypeAndOwnershipChecks() throws Exception {
        String program = new String(pinnedSource(), StandardCharsets.UTF_8);
        var parsed = assertDoesNotThrow(() -> Parser.parse(program));
        assertDoesNotThrow(() -> TypeChecker.check(parsed),
                "portable actor source must type-check before guest execution");
        assertDoesNotThrow(() -> OwnershipChecker.check(parsed),
                "portable actor source must pass ownership admission");
    }

    @Test
    void portableSpawnSendReceiveOutputStopExecutesInGuest() throws Exception {
        String program = new String(pinnedSource(), StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program,
                        "actor-execution-v1-spawn-send-receive-stop.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .buildLiteral();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8),
                "actor output must be observable after ready/send and before done");
    }
}
