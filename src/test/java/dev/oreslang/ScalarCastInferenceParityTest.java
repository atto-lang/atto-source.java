package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scalar typing parity contract shared with the native LLVM tests.
 *
 * A declaration annotation (const int x = 7) checks the binding, whereas
 * (const x = 7 as int) explicitly casts the value before inferring x.
 * Neither construct may silently convert bool/string to int.
 */
final class ScalarCastInferenceParityTest {
    private static void accepts(String body) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                "pub fnc main(): int { " + body + " return 42; }")), body);
    }

    private static void rejects(String body) {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(
                Parser.parse("pub fnc main(): int { " + body + " return 42; }")), body);
    }

    @Test void declarationAndExpressionTypeAscriptionsAgreeForScalars() {
        for (String body : new String[] {
                "const int x = 7; const y = 7 as int; const int z = y;",
                "const x: int = 7; const y = 7 as int; const z: int = y;",
                "const x = 7; const int y = x; const z = x as int;",
                "const bool x = true; const y = true as bool; const bool z = y;",
                "const string x = \"a\"; const y = \"a\" as string; const string z = y;",
                "const int x = (2 + 5) as int; const y = (2 + 5) as int;",
                "const bool x = (2 eq 2) as bool; const y = (2 eq 2) as bool;"
        }) accepts(body);
    }

    @Test void inferenceFlowsThroughFunctionsConditionalsAndScopes() {
        for (String body : new String[] {
                "const inferred = (true ? 7 : 8); const int checked = inferred;",
                "const inferred = (true ? 7 : 8) as int; const int checked = inferred;",
                "const x = 7 as int; if true; do const nested = x as int; const int y = nested; fi",
                "let number = 7 as int; number = 9; const int result = number;",
                "const x = 7 as? int; const Option<int> checked = x;"
        }) accepts(body);
    }

    @Test void invalidCastsAndIncompatibleInferredBindingsAreRejected() {
        for (String body : new String[] {
                "const x = 7 as bool;",
                "const x = true as int;",
                "const x = \"7\" as int;",
                "const x = 7 as string;",
                "const x = 7 as? bool;",
                "const int x = true;",
                "const bool x = 7;",
                "const int x = 7 as bool;",
                "const x = false; const int y = x;",
                "const x = 7 as MissingNominalType;"
        }) rejects(body);
    }

    @Test void inferredConstDoesNotGainMutationCapability() {
        rejects("const x = 7 as int; x = 8;");
        rejects("const int x = 7; x = 8;");
    }

    @Test void bothFormsProduceTheSameGuestValue() throws Exception {
        String source = """
                pub fnc main(): void {
                  const int annotated = 7;
                  const inferred = 7 as int;
                  const bool flag = true as bool;
                  const text = "ok" as string;
                  stdio.println(annotated + inferred);
                  stdio.println(flag);
                  stdio.println(text);
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, source, "scalar-cast-parity.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            assertDoesNotThrow(() -> context.eval(program));
        }
        String printed = output.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("14"), printed);
        assertTrue(printed.contains("true"), printed);
        assertTrue(printed.contains("ok"), printed);
    }
}
