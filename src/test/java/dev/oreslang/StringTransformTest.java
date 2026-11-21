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
    void strictUtf8BytesExposeExactUnsignedWireValues() {
        assertEquals("ok", run("""
                pub routine main(): void {
                  val List<int> ascii = "A\u0000z".utf8_bytes();
                  if ascii.size != 3
                    || ascii.get(0) != 65
                    || ascii.get(1) != 0
                    || ascii.get(2) != 122 then
                    panic("ASCII UTF-8 bytes mismatch");
                  fi

                  val List<int> unicode = "é😀".utf8_bytes();
                  if unicode.size != 6
                    || unicode.get(0) != 195
                    || unicode.get(1) != 169
                    || unicode.get(2) != 240
                    || unicode.get(3) != 159
                    || unicode.get(4) != 152
                    || unicode.get(5) != 128 then
                    panic("Unicode UTF-8 bytes mismatch");
                  fi
                  stdio.stdout.write("ok");
                  return;
                }
                """));
    }

    @Test
    void utf8BytesHasCheckedZeroArgumentSignature() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  val List<int> bytes = "abc".utf8_bytes();
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                          val bytes = "abc".utf8_bytes(1);
                          return;
                        }
                        """)));
    }

    @Test
    void strictUtf8StringDecodesCopiedByteListsAndRejectsMalformedInput() {
        assertEquals("A" + String.valueOf((char) 0) + "B|é😀", run("""
                pub routine main(): void {
                  val List<int> first = [65, 0, 66];
                  val List<int> second = [195, 169, 240, 159, 152, 128];
                  stdio.stdout.write(first.utf8_string());
                  stdio.stdout.write("|");
                  stdio.stdout.write(second.utf8_string());
                  return;
                }
                """));

        PolyglotException malformed = assertThrows(PolyglotException.class,
                () -> run("""
                        pub routine main(): void {
                          val List<int> bytes = [192, 175];
                          stdio.stdout.write(bytes.utf8_string());
                          return;
                        }
                        """));
        assertTrue(malformed.getMessage().contains("invalid UTF-8"));

        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                          val List<string> values = ["not", "bytes"];
                          val string decoded = values.utf8_string();
                          return;
                        }
                        """)));
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
