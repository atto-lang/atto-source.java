package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Failure at any statically known Option layer must be a panic, not a value. */
final class NestedOptionNoneBoundaryTest {
    @Test
    void everyMissingLayerPanics() {
        for (int depth = 1; depth <= 6; depth++) {
            for (int absent = 0; absent < depth; absent++) {
                String declared = "int";
                for (int i = 0; i < depth; i++) {
                    declared = "Option<" + declared + ">";
                }
                String wrapped = "None";
                for (int i = 0; i < absent; i++) {
                    wrapped = "Some(" + wrapped + ")";
                }
                String program = """
                        pub fnc main(): void {
                          val %s nested = %s;
                          nested.unwrap_all();
                          return;
                        }
                        """.formatted(declared, wrapped);
                assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
                Throwable error = assertThrows(Throwable.class, () -> execute(program));
                assertTrue(String.valueOf(error.getMessage()).contains("Option::unwrap_all"),
                        "depth=" + depth + ", absent=" + absent + ": " + error);
            }
        }
    }

    private static void execute(String text) throws Exception {
        Source source = Source.newBuilder(OresLanguage.ID, text, "nested-none.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            context.eval(source);
        }
    }
}
