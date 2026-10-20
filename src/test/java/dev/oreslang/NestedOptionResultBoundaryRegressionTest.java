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

/**
 * unwrap_all is a static Option projection, never a recursive search for a
 * runtime OptionValue hidden inside Result or an erased leaf.
 */
final class NestedOptionResultBoundaryRegressionTest {
    @Test
    void resultContainingSomeDoesNotCauseAnExtraOptionExtraction() throws Exception {
        String program = """
                fnc preserve(Option<Option<Result<Option<int>, String>>> wrapped): Result<Option<int>, String> {
                  return wrapped.unwrap_all();
                }
                pub fnc main(): void {
                  val Result<Option<int>, String> leaf = preserve(Some(Some(Ok(Some(42)))));
                  stdio.stdout.write(leaf.unwrap().unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("42", execute(program));
    }

    @Test
    void resultContainingNoneIsNotMistakenForMissingOuterOption() throws Exception {
        String program = """
                fnc preserve(Option<Option<Result<Option<int>, String>>> wrapped): Result<Option<int>, String> {
                  return wrapped.unwrap_all();
                }
                pub fnc main(): void {
                  val Result<Option<int>, String> leaf = preserve(Some(Some(Ok(None))));
                  stdio.stdout.write(leaf.unwrap().is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("true", execute(program));
    }

    @Test
    void typedNestedExtractionCannotCrossErasedAnyBoundary() {
        String source = """
                fnc invalid(Option<Option<Any>> value): Any {
                  return value.unwrap_all();
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    private static String execute(String code) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "result-boundary.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(stdout)
                .build()) {
            context.eval(source);
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }
}
