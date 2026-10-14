package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * A real compiled source callable exercises the root-only cleanup-aware
 * adapter. Actor STOP semantics remain deliberately independent.
 */
final class RootSourceCleanupAdapterTest {
    @Test
    void rootSourceContinuationPreservesAwaitFinallyAndDeferOrdering() throws Exception {
        String source = """
                async fnc answer(): int {
                  return 42;
                }

                async fnc compute(): int {
                  defer stdio.stdout.write("defer:");
                  try {
                    val int x = await answer();
                    stdio.stdout.write("body:");
                    return x;
                  } catch (err) {
                    stdio.stdout.write("unexpected-catch:");
                    return -1;
                  } finally {
                    stdio.stdout.write("finally:");
                  }
                }

                pub async fnc main(): void {
                  stdio.stdout.write(await compute());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source,
                    "root-cleanup-adapter.ores")
                    .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        assertEquals("body:finally:defer:42", output.toString(StandardCharsets.UTF_8));
    }
}
