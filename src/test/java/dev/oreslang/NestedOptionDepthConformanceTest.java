package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Each depth exercises the actual guest parser, checker, and evaluator. */
final class NestedOptionDepthConformanceTest {
    @Test
    void unwrapAllHandlesOneThroughEightStaticLayers() throws Exception {
        for (int depth = 1; depth <= 8; depth++) {
            String type = "int";
            String initializer = "42";
            for (int i = 0; i < depth; i++) {
                type = "Option<" + type + ">";
                initializer = "Some(" + initializer + ")";
            }
            String source = """
                    pub fnc main(): void {
                      val %s nested = %s;
                      stdio.stdout.write(nested.unwrap_all());
                      return;
                    }
                    """.formatted(type, initializer);
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source),
                    "depth=" + depth);
            assertEquals("42", execute(source), "depth=" + depth);
        }
    }

    private static String execute(String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, text, "nested-depth.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
