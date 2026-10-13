package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelectNestedReturnContractTest {
    @Test
    void evaluatesSynchronousDoSelectReturnOnlyOnce() throws Exception {
        String source = """
                pub fnc produce(): int {
                  stdio.stdout.write("evaluated:");
                  return 9;
                }
                pub fnc compute(): int {
                  do select {
                    default: {
                      return produce();
                    }
                  }
                  return -1;
                }
                pub routine main(): void {
                  stdio.stdout.write(compute());
                  return;
                }
                """;
        assertWarnings(source, 0);
        assertEquals("evaluated:9", run(source));
    }

    @Test
    void nestedSynchronousDoSelectReturnExitsEnclosingCallableOnce() throws Exception {
        String source = """
                pub fnc compute(): int {
                  do select {
                    default: {
                      do select {
                        default: {
                          stdio.stdout.write("inner:");
                          return 1;
                        }
                      }
                      stdio.stdout.write("unreachable outer");
                      return 2;
                    }
                  }
                  return -1;
                }
                pub routine main(): void {
                  stdio.stdout.write(compute());
                  return;
                }
                """;
        assertWarnings(source, 0);
        assertEquals("inner:1", run(source));
    }

    @Test
    void doSelectReturnTypeCannotBypassEnclosingCallableType() {
        String source = """
                pub fnc compute(): int {
                  do select {
                    default: {
                      return "not an integer";
                    }
                  }
                  return 42;
                }
                """;
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckWithDiagnostics(source));
    }

    @Test
    void nestedLambdaReturnProducesNoSelectWarning() throws Exception {
        String source = """
                pub fnc main(): void {
                  do select {
                    default: {
                      val Fnc<int,int> twice = |number| -> {
                        return number * 2;
                      };
                      stdio.stdout.write(twice(6));
                    }
                  }
                  stdio.stdout.write(":done");
                  return;
                }
                """;
        assertWarnings(source, 0);
        assertEquals("12:done", run(source));
    }

    static void assertWarnings(String source, int expected) {
        var result = OresCompiler.parseAndTypeCheckWithDiagnostics(source);
        assertEquals(expected, result.warnings().size(), result.warnings().toString());
        result.warnings().forEach(w -> assertTrue(w.contains("W-SELECT-RETURN"), w));
    }

    static String run(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, text, "select-nested.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
