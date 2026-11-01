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
 * Native Oreslang source admission and guest execution for immutable-default
 * scalar locals. Compile-time const and non-Copy referent ownership remain
 * separate compatibility gates; no claim of general immutable objects.
 */
final class ImmutableDefaultScalarBindingTest {
    private static String main(String body) {
        return "pub fnc main(): int { " + body + " }";
    }

    private static void accepts(String body) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(main(body))), body);
    }

    private static void rejects(String body) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(main(body))), body);
    }

    @Test void typedDefaultAndConstAgreeOnLiteral() {
        for (String body : new String[] {
                "int x = 7; return x;",
                "const x = 7; return x;",
                "const int x = 7; return x;",
                "const x: int = 7; return x;",
                "int x = 7; int y = x * 3; return y;",
                "bool enabled = true; if enabled; do return 7; else return 0; fi",
                "int x = (2 + 5); return x;",
                "int x = -7; return x + 14;"
        }) accepts(body);
    }

    @Test void noImplicitMutabilityOrInferredTypeEscape() {
        for (String body : new String[] {
                "int x = 7; x = 8; return x;",
                "int x = 7; x += 1; return x;",
                "int x = 7; int x = 8; return x;",
                "int x = x + 1; return x;",
                "int x = y; int y = 7; return x;",
                "int x = unknown_name; return x;",
                "bool value = 7; return 0;",
                "int value = true; return 0;",
                "int x = 7; rt mut borrow x; return x;",
                "int x = 7; return x = 8;"
        }) rejects(body);
    }

    @Test void realGraalGuestExecutesEquivalentReadOnlyValues() throws Exception {
        String program = """
                pub fnc main(): void {
                    int typed = 7;
                    const inferred = 7;
                    const int annotated = 7;
                    stdio.println(typed + inferred + annotated);
                    return;
                }
                """;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "immutable-default.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(bytes).build()) {
            assertDoesNotThrow(() -> context.eval(source));
        }
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("21"),
                "three immutable scalar locals must execute in guest context");
    }
}
