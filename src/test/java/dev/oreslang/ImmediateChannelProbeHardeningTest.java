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

/** Execute .atto probes and enforce a recoverable ownership boundary at compilation. */
@Timeout(10)
final class ImmediateChannelProbeHardeningTest {
    private static String fixture(String name) throws Exception {
        try (InputStream stream = ImmediateChannelProbeHardeningTest.class.getResourceAsStream(
                "/atto/channel-probes-v1/" + name)) {
            if (stream == null) {
                throw new AssertionError("missing Atto guest fixture: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void immediateCopyProbesExecuteAsAttoGuest() throws Exception {
        String guest = fixture("immediate_copy.atto");
        TypeChecker.check(Parser.parse(guest));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, "immediate_copy.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("4142", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void moveOnlyLvalueCannotBeSilentlyConsumedByBooleanProbe() throws Exception {
        assertRejectedMoveOnly("irrecoverable_move.atto");
    }

    @Test
    void moveOnlyTemporaryCannotBeSilentlyDroppedByBooleanProbe() throws Exception {
        assertRejectedMoveOnly("irrecoverable_literal.atto");
    }

    private static void assertRejectedMoveOnly(String name) throws Exception {
        String guest = fixture(name);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)));
        assertTrue(error.getMessage().contains(
                "try writech cannot accept a move-only payload"), error::getMessage);
    }
}
