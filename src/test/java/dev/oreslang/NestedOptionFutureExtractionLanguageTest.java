package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guest-language conformance for explicit nested extraction/composition. */
final class NestedOptionFutureExtractionLanguageTest {
    @Test
    void unwrapAllAndFlattenAreTypedAndKeepOneLayerUnwrapUnchanged() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Option<Option<int>> deep = Some(Some(42));
                  val Option<int> one = deep.unwrap();
                  val int all = deep.unwrap_all();
                  val Option<int> flat = deep.flatten();

                  stdio.stdout.write(one.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(all);
                  stdio.stdout.write(":");
                  stdio.stdout.write(flat.unwrap());
                  stdio.stdout.write(":");

                  val Option<Option<int>> outer_none = None;
                  val Option<Option<int>> inner_none = Some(None);
                  stdio.stdout.write(outer_none.flatten().is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(inner_none.unwrap().is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(inner_none.flatten().is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("42:42:42:true:true:true", run(program));
    }

    @Test
    void nestedUnwrapAllPanicsWhenEitherLayerIsMissing() {
        for (String nested : new String[] {"None", "Some(None)"}) {
            String program = """
                    pub fnc main(): void {
                      val Option<Option<int>> missing = %s;
                      missing.unwrap_all();
                      return;
                    }
                    """.formatted(nested);
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
            Throwable thrown = assertThrows(Throwable.class, () -> run(program));
            assertTrue(thrown.getMessage().contains("Option::unwrap_all"),
                    "must preserve Ores panic for " + nested + ": " + thrown);
        }
    }

    @Test
    void rejectsMisTypedFlattenAndWrongArity() {
        for (String expression : new String[] {
                "Some(1).flatten()",
                "Some(1).unwrap_all(1)",
                "Some(1).flatten(1)"
        }) {
            String program = """
                    pub fnc main(): void {
                      val value = %s;
                      return;
                    }
                    """.formatted(expression);
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(program), expression);
        }
    }

    @Test
    void futureFlattenAwaitsOuterThenInnerViaScheduler() throws Exception {
        String program = """
                async fnc base(): int {
                  return 41;
                }
                async fnc leaf(int value): int {
                  return value + 1;
                }
                pub fnc main(): void {
                  val Future<Future<int>> outer = base().map(|int value| -> {
                    return leaf(value);
                  });
                  val Future<int> one = await outer;
                  val Future<int> flattened = outer.flatten();
                  stdio.stdout.write(await one);
                  stdio.stdout.write(":");
                  stdio.stdout.write(await flattened);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("42:42", run(program));
    }

    @Test
    void futureFlattenRejectsNonNestedFuture() {
        String program = """
                async fnc leaf(): int {
                  return 42;
                }
                pub fnc main(): void {
                  val future = leaf().flatten();
                  return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "nested-extraction.ores")
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
