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

/** Compiles and executes typed .atto probes, including affine ownership diagnostics. */
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

    private static String execute(String name) throws Exception {
        String guest = fixture(name);
        TypeChecker.check(Parser.parse(guest));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, name)
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void immediateCopyProbesExecuteAsAttoGuest() throws Exception {
        assertEquals("4142", execute("immediate_copy.atto"));
    }

    @Test
    void moveOnlyTemporaryMayBeAdmitted() throws Exception {
        assertEquals("ok", execute("irrecoverable_literal.atto"));
    }

    @Test
    void fullProbeReturnsOriginalOwnedArray() throws Exception {
        assertEquals("2", execute("typed_move_full.atto"));
    }

    @Test
    void closedProbeReturnsOriginalOwnedArray() throws Exception {
        assertEquals("9", execute("typed_move_closed.atto"));
    }

    @Test
    void zeroCapacityProbeReturnsFullWithoutRegistration() throws Exception {
        assertEquals("17", execute("typed_unbuffered_full.atto"));
    }

    @Test
    void takingMoveOnlyValueForProbeInvalidatesOriginalBinding() throws Exception {
        assertUseAfterMove("irrecoverable_move.atto");
    }

    @Test
    void explicitTakeOfCopyValueInvalidatesOriginalBinding() throws Exception {
        assertUseAfterMove("irrecoverable_take_copy.atto");
    }

    @Test
    void directMatchConsumesRejectedMoveOnlyPayloadExactlyOnce() throws Exception {
        assertEquals("5", execute("typed_direct_match.atto"));
    }

    @Test
    void immediateProbeIsUsableFromAsyncMain() throws Exception {
        assertEquals("29", execute("typed_async_match.atto"));
    }

    @Test
    void guardedRecoverableProbeDestructuringFailsClosed() throws Exception {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(fixture("typed_guarded_match.atto"))));
        assertTrue(error.getMessage().contains("match guards are not supported"),
                error::getMessage);
    }

    @Test
    void discardingRecoverableProbeIsRejected() throws Exception {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(fixture("typed_discard_result.atto"))));
        assertTrue(error.getMessage().contains("cannot be discarded"), error::getMessage);
    }

    @Test
    void matchingTheAffineResultTwiceIsRejected() throws Exception {
        assertUseAfterMove("typed_move_result_reuse.atto");
    }

    private static void assertUseAfterMove(String name) throws Exception {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(fixture(name))));
        assertTrue(error.getMessage().contains("use of moved value"), error::getMessage);
    }
}
