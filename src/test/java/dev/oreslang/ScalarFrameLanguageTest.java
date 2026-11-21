package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/**
 * True .atto parser/typechecker/evaluator acceptance, NOT a host-model
 * test. These assertions are AUTHORED ONLY until an exact-head Maven run
 * actually executes. Root source uses a suspends routine to print results;
 * the called scalar fnc body must be admitted to ScalarFrameMachine.
 */
final class ScalarFrameLanguageTest {
    @Test
    void nonTailRecursionExceedsLegacyNativeGuardWithoutGrowingCarrierStack()
            throws Exception {
        assertEquals("1024", execute("deep-non-tail-scalar-frame.atto"));
    }

    @Test
    void mutualTailRecursionOneHundredThousandHopsRemainsBounded()
            throws Exception {
        assertEquals("0", execute("mutual-tail-scalar-frame.atto"));
    }

    @Test
    void immutableScalarLocalsArePreservedAcrossExplicitHeapFrames()
            throws Exception {
        assertEquals("9", execute("immutable-local-scalar-frame.atto"));
    }

    private static String execute(String filename) throws Exception {
        String path = "/actor-stack/" + filename;
        String sourceText;
        try (InputStream in = ScalarFrameLanguageTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing compiler-backed AttoLang fixture: " + path);
            sourceText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertFalse(sourceText.isBlank());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, filename)
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
