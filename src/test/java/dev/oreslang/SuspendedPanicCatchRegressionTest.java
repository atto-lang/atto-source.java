package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class SuspendedPanicCatchRegressionTest {
    @Test
    void aPanickingAsyncTryBodyRunsFinallyButBypassesOrdinaryCatch() throws Exception {
        String sourceText = """
                async fnc ready(): int {
                  return 7;
                }

                pub fnc main(): void {
                  try {
                    val int x = await ready();
                    None.unwrap();
                  } catch (err) {
                    stdio.stdout.write("INCORRECT_CATCH");
                  } finally {
                    stdio.stdout.write("FINALLY_RAN");
                  }
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(sourceText));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, "suspended-panic-catch.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            PolyglotException failure =
                    assertThrows(PolyglotException.class, () -> context.eval(source));
            assertTrue(failure.getMessage().contains("Option::unwrap"));
        }

        String stdout = output.toString(StandardCharsets.UTF_8);
        assertTrue(stdout.contains("FINALLY_RAN"), "finally must run on panic unwind");
        assertFalse(stdout.contains("INCORRECT_CATCH"),
                "ordinary catch must never turn a panic into handled control flow");
    }
}
