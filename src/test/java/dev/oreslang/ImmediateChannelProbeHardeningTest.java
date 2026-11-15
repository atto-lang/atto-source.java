package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Executes real .atto typed try-write probes and checks ownership diagnostics. */
@Timeout(10)
final class ImmediateChannelProbeHardeningTest {
    private static String fixture(String dir, String name) throws Exception {
        try (InputStream stream = ImmediateChannelProbeHardeningTest.class.getResourceAsStream(
                "/atto/" + dir + "/" + name)) {
            if (stream == null) {
                throw new AssertionError("missing Atto guest fixture: " + dir + "/" + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String run(String fixture) throws Exception {
        String guest = fixture("channel-probes-v2", fixture);
        TypeChecker.check(Parser.parse(guest));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, fixture)
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void typedImmediateCopyProbeReportsAcceptedFullAndRetry() throws Exception {
        assertEquals("4142", run("typed_copy.atto"));
    }

    @Test
    void typedImmediateProbeReturnsMoveOnlyPayloadOnFull() throws Exception {
        assertEquals("7", run("typed_move_full.atto"));
    }

    @Test
    void senderCannotReuseOwnerAfterTakingIntoTypedProbe() throws Exception {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        fixture("channel-probes-v2", "moved_source_after_probe.atto"))));
        assertTrue(failure.getMessage().contains("moved value"), failure::getMessage);
    }

    @Test
    void legacyBooleanAssignmentToTryWritechIsRejected() throws Exception {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        fixture("channel-probes-v1", "immediate_copy.atto"))));
        assertTrue(failure.getMessage().contains("bool")
                        || failure.getMessage().contains("TryWriteResult"),
                failure::getMessage);
    }
}
