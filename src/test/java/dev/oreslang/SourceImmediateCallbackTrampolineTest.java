package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for #2996. Loop backedges already have a source trampoline,
 * but ordinary synchronous statements and immediately-completed callbacks
 * must not retain one Java interpreter frame per guest operation.
 */
@Timeout(30)
final class SourceImmediateCallbackTrampolineTest {
    @Test
    void thousandsOfStraightLineStatementsUseHeapOwnedCallbackPump() throws Exception {
        StringBuilder code = new StringBuilder("""
                pub async fnc main(): void {
                  let total = 0;
                """);
        for (int i = 0; i < 2_048; i++) {
            code.append("  total = total + 1;\n");
        }
        code.append("""
                  stdio.stdout.write(total);
                  return;
                }
                """);
        assertEquals("2048", run(code.toString()));
    }

    @Test
    void immediatelySettledAsyncValuesPreserveSequentialExactlyOnceEffects()
            throws Exception {
        StringBuilder code = new StringBuilder("""
                async fnc one(): int {
                  return 1;
                }
                pub async fnc main(): void {
                  let total = 0;
                """);
        for (int i = 0; i < 128; i++) {
            code.append("  total = total + (await one());\n");
        }
        code.append("""
                  stdio.stdout.write(total);
                  return;
                }
                """);
        assertEquals("128", run(code.toString()));
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, sourceText, "callback-trampoline.ores")
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
