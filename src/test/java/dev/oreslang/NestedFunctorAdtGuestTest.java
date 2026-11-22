package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-checked .atto guest fixtures (not a substitute for native AOT or
 * other-backend parity). The program must execute inside the guest language.
 */
final class NestedFunctorAdtGuestTest {
    private static String fixture(String filename) throws IOException {
        String resource = "/atto/functor-v1/" + filename;
        try (InputStream in = NestedFunctorAdtGuestTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing checked-in .atto guest fixture " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void nestedMapsAndAdtTranspositionsExecuteWithTheirExactReturnShapes() throws Exception {
        String text = fixture("nested-functor-positive.atto");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(text));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, text, "nested-functor-positive.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(guest);
        }
        // 3 Option<Result>, 3 Result<Option>, 3 Option.transpose,
        // 3 Result.transpose, 3 Result.flatten, 1 non-flattening map.
        assertEquals("FNV1:" + "1".repeat(16) + ":END",
                output.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void threeLayerMapsPreserveTaggedShapesAndSkipDormantCallbacks() throws Exception {
        String text = fixture("nested-functor-depth-positive.atto");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(text));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, text, "nested-functor-depth-positive.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(guest);
        }
        assertEquals("FNV2:" + "1".repeat(20) + ":END",
                output.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void invalidFunctorDomainOrCarrierFailsBeforeGuestExecution() throws Exception {
        for (String filename : List.of(
                "bad-result-flatten-error.atto",
                "bad-result-transpose-input.atto",
                "bad-implicit-layer-collapse.atto",
                "bad-map-err-domain.atto",
                "bad-result-flatten-arity.atto",
                "bad-borrowed-result-transpose.atto",
                "bad-borrowed-result-flatten.atto",
                "bad-borrowed-option-transpose.atto",
                "bad-unresolved-transpose.atto",
                "bad-adtdispatch-capture.atto",
                "bad-three-layer-result-collapse.atto",
                "bad-three-layer-option-collapse.atto",
                "bad-deep-transpose-result-shape.atto",
                "bad-deep-transpose-option-shape.atto",
                "bad-deep-map-error-domain.atto")) {
            String text = fixture(filename);
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(text),
                    "must statically reject " + filename);
        }
    }
}
