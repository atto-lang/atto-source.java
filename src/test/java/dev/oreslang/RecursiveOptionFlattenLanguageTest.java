package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RecursiveOptionFlattenLanguageTest {
    @Test
    void nestedOptionsAreNotImplicitlyAssignableToFlattenedType() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc bad(Option<Option<int>> nested): Option<int> {
                            return nested;
                        }
                        """)));
        assertDoesNotThrow(() ->
                TypeChecker.check(Parser.parse("""
                        fnc good(Option<Option<int>> nested): Option<int> {
                            return nested.flatten();
                        }
                        """)));
    }

    @Test
    void flattenMovesAffineOptionExactlyOnce() {
        // Future<int> is an already-known move-only payload, avoiding a
        // spurious class-construction error masking the actual ownership test.
        String source = """
                fnc invalid(Option<Option<Future<int>>> nested): Option<Future<int>> {
                  val Option<Future<int>> first = nested.flatten();
                  return nested.flatten();
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        org.junit.jupiter.api.Assertions.assertTrue(
                failure.getMessage().contains("use of moved value 'nested'"),
                () -> "expected ownership double-move rejection, got: " + failure);
    }

    @Test
    void singleAffineFlattenRemainsValid() {
        String source = """
                fnc valid(Option<Option<Future<int>>> nested): Option<Future<int>> {
                  return nested.flatten();
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void explicitFlattenCollapsesNestedOptionsWithoutCollapsingByDefault() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Option<Option<Option<int>>> nested = Some(Some(Some(11)));
                  val Option<int> flat = nested.flatten();
                  stdio.stdout.write(flat.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(flat.unwrap());

                  val Option<Option<int>> preserved = Some(None);
                  stdio.stdout.write(":");
                  stdio.stdout.write(preserved.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(preserved.flatten().is_none());

                  val Option<Option<int>> absent = None;
                  stdio.stdout.write(":");
                  stdio.stdout.write(absent.flatten().is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "recursive-option-flatten.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals("true:11:true:true:true", output.toString(StandardCharsets.UTF_8));
    }
}
