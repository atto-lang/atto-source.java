package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class StringTransformTest {
    @Test
    void replacementIsLiteralNonRecursiveAndPreservesUnicode() {
        assertEquals("&amp;&amp;|a$1b$1c|é😀中|aaaa|", run("""
                pub routine main(): void {
                  stdio.stdout.write("&&".replace_literal("&", "&amp;"));
                  stdio.stdout.write("|");
                  stdio.stdout.write("a.b.c".replace_literal(".", "$1"));
                  stdio.stdout.write("|");
                  stdio.stdout.write("é😀中".replace_literal("&", "x"));
                  stdio.stdout.write("|");
                  stdio.stdout.write("aa".replace_literal("a", "aa"));
                  stdio.stdout.write("|");
                  stdio.stdout.write("".replace_literal("x", "y"));
                  return;
                }
                """));
    }

    @Test
    void replacementDoesNotMutateTheReceiverAndCanRemoveMatches() {
        assertEquals("ababa|Xba|a|ababa", run("""
                pub routine main(): void {
                  val string value = "ababa";
                  stdio.stdout.write(value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(value.replace_literal("aba", "X"));
                  stdio.stdout.write("|");
                  stdio.stdout.write(value.replace_literal("ab", ""));
                  stdio.stdout.write("|");
                  stdio.stdout.write(value);
                  return;
                }
                """));
    }

    @Test
    void embeddedNulIsDataAcrossConcatReplaceAndStdout() {
        String nul = String.valueOf((char) 0);
        assertEquals("A" + nul + "B|AXB|A" + nul + "BC" + nul + "D", run("""
                pub routine main(): void {
                  val string value = "A\\u0000B";
                  stdio.stdout.write(value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(value.replace_literal("\\u0000", "X"));
                  stdio.stdout.write("|");
                  stdio.stdout.write(value + "C\\u0000D");
                  return;
                }
                """));
    }

    @Test
    void decodedUnpairedSurrogatesAreRejectedBeforeUtf8Boundary() {
        String high = "\\uD800";
        String low = "\\uDC00";
        for (String escaped : new String[] {high, low}) {
            IllegalArgumentException quoted = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("pub routine main(): void { val string value = \"" + escaped
                            + "\"; return; }"));
            assertTrue(quoted.getMessage().contains("unpaired Unicode surrogate"));

            IllegalArgumentException template = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("pub routine main(): void { val string value = `" + escaped
                            + "`; return; }"));
            assertTrue(template.getMessage().contains("unpaired Unicode surrogate"));
        }

        assertDoesNotThrow(() -> Parser.parse(
                "pub routine main(): void { val string value = \"\\uD83D\\uDE00\"; return; }"));
    }

    @Test
    void strictUtf8RoundTripKeepsUnsignedBytesAndEmbeddedNul() {
        assertEquals("A" + (char) 0 + "B|é😀|6|195|128", run("""
                pub routine main(): void {
                  val List<int> first = [65, 0, 66];
                  val List<int> second = "é😀".utf8_bytes();
                  stdio.stdout.write(first.utf8_string());
                  stdio.stdout.write("|");
                  stdio.stdout.write(second.utf8_string());
                  stdio.stdout.write("|");
                  stdio.stdout.write(second.size.to_string());
                  stdio.stdout.write("|");
                  stdio.stdout.write(second.get(0).to_string());
                  stdio.stdout.write("|");
                  stdio.stdout.write(second.get(5).to_string());
                  return;
                }
                """));
    }

    @Test
    void strictUtf8RejectsMalformedAndOutOfRangeByteSequences() {
        for (String bytes : java.util.List.of(
                "[192, 175]", "[237, 160, 128]", "[244, 144, 128, 128]",
                "[240, 159, 152]", "[128]", "[256]", "[-1]")) {
            PolyglotException failure = assertThrows(PolyglotException.class, () -> run("""
                    pub routine main(): void {
                      val List<int> bytes = %s;
                      stdio.stdout.write(bytes.utf8_string());
                      return;
                    }
                    """.formatted(bytes)));
            assertTrue(failure.getMessage().contains("UTF-8")
                    || failure.getMessage().contains("0..255"), failure.getMessage());
        }
    }

    @Test
    void strictUtf8OperationsHaveCheckedSignatures() {
        for (String expression : java.util.List.of(
                "\"abc\".utf8_bytes(1)", "bytes.utf8_string(1)",
                "wrong.utf8_string()")) {
            assertThrows(IllegalArgumentException.class, () ->
                    TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                          val List<int> bytes = [65];
                          val List<string> wrong = ["a"];
                          val result = %s;
                          return;
                        }
                        """.formatted(expression))));
        }
    }

    @Test
    void emptyNeedleIsRejected() {
        PolyglotException error = assertThrows(PolyglotException.class,
                () -> run("""
                        pub routine main(): void {
                          stdio.stdout.write("value".replace_literal("", "x"));
                          return;
                        }
                        """));
        assertTrue(error.getMessage().contains("needle must not be empty"));
    }

    @Test
    void invalidCallsAreRejectedStatically() {
        for (String expression : new String[] {
                "\"x\".replace_literal(1, \"y\")",
                "\"x\".replace_literal(\"x\")",
                "\"x\".unknown_method(\"x\", \"y\")"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "pub routine main(): void { val result = " + expression + "; return; }")));
        }
    }

    private static String run(String source) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(OresLanguage.ID, source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
