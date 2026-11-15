package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Focused regression coverage for return-shape checks recovered from #27.
 * Compiler checks and runtime boundary checks must agree; nested Options
 * must remain nested rather than being implicitly flattened.
 */
final class ReturnContractRuntimeTest {
    @Test
    void nestedOptionAndLiteralUnionReturnsPreserveTheirShapes() throws Exception {
        String program = """
                type State = "ready" | "stopped";

                fnc nested(): Option<Option<int>> {
                  return Some(Some(3));
                }

                fnc absent(): Option<int> {
                  return None;
                }

                fnc state(): State {
                  return "ready";
                }

                pub fnc main(): void {
                  stdio.println(nested().unwrap().unwrap());
                  stdio.println(absent().is_none());
                  stdio.println(state());
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "return-contract-shapes.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals("3\ntrue\nready", output.toString(StandardCharsets.UTF_8).trim().replace("\r\n", "\n"));
    }

    @Test
    void literalTypeRejectsAnIncompatibleStaticallyKnownReturn() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc wrong(): "ready" {
                  return "stopped";
                }

                pub fnc main(): void {
                  return;
                }
                """)));
    }

    @Test
    void optionReturnDoesNotAcceptAPlainInteger() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc wrong(): Option<int> {
                  return 7;
                }

                pub fnc main(): void {
                  return;
                }
                """)));
    }
}
