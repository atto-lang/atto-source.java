package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.types.OwnershipChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class TupleCallableSpreadResumableTest {
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
