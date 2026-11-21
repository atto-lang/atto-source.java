package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TupleCallableSpreadResumableTest {
    @Test
    void uncheckedResumableSpreadArityMismatchFailsAsLanguageError() throws Exception {
        String code = """
                fnc tuple(int value): int {
                  return value;
                }

                async fnc loadPair(): (int, int) {
                  return (4, 2);
                }

                async fnc total(): int {
                  return tuple(...(await loadPair()));
                }

                pub fnc main(): void {
                  stdio.println(await total());
                  return;
                }
                """;

        Ast.Program deliberatelyUnchecked = Parser.parse(code);
        Source source = Source.newBuilder(
                        OresLanguage.ID, code, "tuple-callable-spread-bypass.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (OresLanguage.PrecheckedProgramScope ignored =
                     OresLanguage.openPrecheckedProgram(
                             "tuple-callable-spread-bypass.ores",
                             code,
                             deliberatelyUnchecked);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false)
                     .build()) {
            PolyglotException failure = assertThrows(
                    PolyglotException.class,
                    () -> context.eval(source));
            assertTrue(
                    failure.getMessage().contains("no function tuple with arity 2"),
                    failure.getMessage());
        }
    }

    @Test
    void userTupleCallableWinsAfterResumableSpreadExpansion() throws Exception {
        String code = """
                fnc tuple(int left, int right): int {
                  return left * 10 + right;
                }

                async fnc loadPair(): (int, int) {
                  return (4, 2);
                }

                async fnc total(): int {
                  return tuple(...(await loadPair()));
                }

                pub fnc main(): void {
                  stdio.println(await total());
                  return;
                }
                """;

        var program = OresCompiler.parseAndTypeCheck(code);
        assertDoesNotThrow(() -> OwnershipChecker.check(program));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(
                            OresLanguage.ID, code, "tuple-callable-spread.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        assertEquals(
                "42",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .strip());
    }
}
