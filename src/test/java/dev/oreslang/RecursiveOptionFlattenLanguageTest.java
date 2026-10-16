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
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Box as
                        end

                        fnc bad(): void {
                          val Option<Option<Box>> nested = Some(Some(new Box()));
                          val Option<Box> first = nested.flatten();
                          val Option<Box> second = nested.flatten();
                          return;
                        }
                        """)));
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
