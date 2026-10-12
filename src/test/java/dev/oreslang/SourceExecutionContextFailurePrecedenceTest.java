package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real guest execution after await: cleanup provenance must belong to the
 * heap-owned SourceTask execution context, not to its transient carrier.
 */
final class SourceExecutionContextFailurePrecedenceTest {
    private static PolyglotException fails(String code, ByteArrayOutputStream out) throws Exception {
        Source source = Source.newBuilder(OresLanguage.ID, code, "source-context-failure.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            return assertThrows(PolyglotException.class, () -> context.eval(source));
        }
    }

    private static String output(ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.UTF_8).trim().replace("\r", "");
    }

    @Test
    void awaitedSourceTaskRetainsPanicAcrossMultipleFailingDefers() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PolyglotException failure = fails("""
                async fnc ready(): int { return 7; }
                pub async fnc main(): void {
                  val int step = await ready();
                  defer stdio.println("earlier-defer");
                  defer stdio.println(step % 0);
                  defer stdio.println("later-defer");
                  val Option<int> missing = None;
                  val int ignored = missing.unwrap();
                }
                """, out);
        assertTrue(failure.getMessage().contains("Option::unwrap"),
                () -> "source defer masked original panic: " + failure.getMessage());
        assertEquals("later-defer\nearlier-defer", output(out));
    }

    @Test
    void awaitedSourceTaskPreservesPanicAcrossFailedFinally() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PolyglotException failure = fails("""
                async fnc ready(): int { return 7; }
                pub async fnc main(): void {
                  val int step = await ready();
                  try {
                    val Option<int> missing = None;
                    val int ignored = missing.unwrap();
                  } catch (e) {
                    stdio.println("panic-was-caught");
                  } finally {
                    stdio.println("finally-once");
                    val int cleanupError = step % 0;
                  }
                  stdio.println("should-not-continue");
                }
                """, out);
        assertTrue(failure.getMessage().contains("Option::unwrap"),
                () -> "source finally masked original panic: " + failure.getMessage());
        assertEquals("finally-once", output(out));
    }

    @Test
    void sourceFinallyErrorOverridesNormalReturnAfterAwait() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PolyglotException failure = fails("""
                async fnc ready(): int { return 7; }
                async fnc compute(): int {
                  val int step = await ready();
                  try {
                    return 42;
                  } catch (e) {
                    return 0;
                  } finally {
                    stdio.println("finally-before-delivery");
                    val int cleanupError = step % 0;
                  }
                }
                pub async fnc main(): void {
                  stdio.println(await compute());
                }
                """, out);
        assertFalse(failure.getMessage().isBlank());
        assertEquals("finally-before-delivery", output(out));
    }
}
