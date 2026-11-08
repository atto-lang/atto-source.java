package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-level trap/Result/Option regression: the .atto resource is compiled
 * and evaluated as a real guest, not simulated by a Java host implementation.
 */
final class TrapResultOptionHardeningTest {
    private static String fixture(String name) throws Exception {
        try (InputStream input = TrapResultOptionHardeningTest.class.getResourceAsStream(
                "/trap-result-option/" + name)) {
            assertNotNull(input, name + " fixture is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void ordinaryErrAndNoneRemainDataInsideTheAddedOptionLayer() throws Exception {
        String source = fixture("ordinary-data.atto");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(
                OresLanguage.ID, source, "ordinary-data.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(guest);
        }
        assertEquals("true:true:true:true:true:true:true:true:true:true:true",
                stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void nestedAwaitCannotBeHiddenInsideTrapExpression() throws Exception {
        String source = fixture("nested-await-reject.atto");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage().contains("trap expression cannot contain suspension"),
                error::getMessage);
    }

    @Test
    void pendingFutureCannotBeRepresentedAsTrappedCompletion() throws Exception {
        String source = fixture("pending-future-reject.atto");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage().contains("trap expression cannot wrap Future"),
                error::getMessage);
    }

    @Test
    void trapResultDoesNotCoerceToBareResult() throws Exception {
        String source = fixture("implicit-flatten-reject.atto");
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }
}
