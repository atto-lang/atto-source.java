package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real .atto parser/type-checker + Truffle guest execution.
 * This is owned Copy<int> / Option<int>; it does NOT claim ReadLoan<T>,
 * non-lexical lifetime or every collection's generic implementation.
 */
final class CheckedCollectionGetCopyLanguageTest {
    @Test
    void checkedGuestReadsProduceSomeAndNoneWithoutMagicSentinels() throws Exception {
        String code = """
                pub routine main(): void {
                  val Array<int> numbers = new Array<int>();
                  stdio.stdout.write(numbers.get_copy(0).is_none());
                  stdio.stdout.write(":");
                  numbers.add(12);
                  numbers.add(42);
                  val Option<int> hit = numbers.get_copy(1);
                  val Option<int> missing = numbers.get_copy(2);
                  stdio.stdout.write(hit.unwrap_or(0));
                  stdio.stdout.write(":");
                  stdio.stdout.write(missing.is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(numbers.get_copy(-1).is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(numbers.get_copy(9223372036854775807).is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(numbers.get_copy(0).unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(numbers.size);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code));
        assertEquals("true:42:true:true:true:12:2", execute(code, "get-copy.atto"));
        // The original .ores suffix remains compatible.
        assertEquals("true:42:true:true:true:12:2", execute(code, "get-copy.ores"));
    }

    @Test
    void sharedAttoFixtureExecutesWithSomeNoneAndCopiedMutation() throws Exception {
        String code;
        try (var stream = getClass().getResourceAsStream(
                "/safe-collections/copy-option-v1.atto")) {
            assertNotNull(stream, "shared LLVM/Java fixture must be present");
            code = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code));
        assertEquals("7:42:true:true:true:true:9", execute(code, "copy-option-v1.atto"));
        assertEquals("7:42:true:true:true:true:9", execute(code, "copy-option-v1.ores"));
    }

    @Test
    void anOwnedCopyNeverTracksSubsequentArrayMutation() throws Exception {
        String code = """
                pub routine main(): void {
                  let mut Array<int> values = new Array<int>();
                  values.add(7);
                  val Option<int> copy = values.get_copy(0);
                  values.set(0, 9);
                  stdio.stdout.write(copy.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(values.get_copy(0).unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code));
        assertEquals("7:9", execute(code, "owned-copy.atto"));
    }

    @Test
    void nonCopyElementCannotMasqueradeAsOwnedOptionValue() {
        String source = """
                fnc invalid(): void {
                  val Array<String> values = new Array<String>();
                  values.add("borrowed?");
                  val candidate = values.get_copy(0);
                  return;
                }
                """;
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("requires a proven Copy<int> element"),
                () -> "expected semantic non-Copy rejection, got: " + failure.getMessage());
    }

    @Test
    void nestedOptionPayloadIsNotAutomaticallyFlattenedIntoCopyInt() {
        String source = """
                fnc invalid(): void {
                  val Array<Option<int>> nested = new Array<Option<int>>();
                  nested.add(Some(42));
                  val candidate = nested.get_copy(0);
                  return;
                }
                """;
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("requires a proven Copy<int> element"),
                () -> "Option<Option<int>> must not be flattened: " + failure.getMessage());
    }

    private static String execute(String program, String filename) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, filename)
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(out)
                .build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
