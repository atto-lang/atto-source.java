package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual Java guest/parser regression slice for exact and prefix Bytes
 * matching. JVM/Truffle execution is NOT strict full guest AOT proof.
 */
final class ByteBitSequencePatternGuestTest {
    @Test
    void bytePrefixMatchesAndCopiesOwnedTail() throws Exception {
        assertEquals("33", run("""
                pub routine main(): void {
                  val data = Bytes.from_list([71, 69, 84, 33]);
                  match data over
                    on bytes[71, 69, 84 | rest] -> {
                      stdio.stdout.write(rest.byte_at(0));
                    }
                    on _ -> { stdio.stdout.write(0); }
                  end
                  return;
                }
                """));
    }

    @Test
    void bytePrefixMismatchDoesNotPublishCapturesOrExecuteGuard() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                  val data = Bytes.from_list([71, 69, 84, 33]);
                  match data over
                    on bytes[80, 69, 84 | rest] -> { stdio.stdout.write(0); }
                    on bytes[71, 69, 84 | _] -> { stdio.stdout.write(42); }
                    on _ -> { stdio.stdout.write(1); }
                  end
                  return;
                }
                """));
    }

    @Test
    void byteSingletonIsLiteralNotSegmentWidth() throws Exception {
        assertEquals("42|7", run("""
                pub routine main(): void {
                  val one = Bytes.from_list([3]);
                  match one over
                    on bytes[3] -> { stdio.stdout.write(42); }
                    on _ -> { stdio.stdout.write(0); }
                  end
                  stdio.stdout.write("|");
                  val two = Bytes.from_list([3, 4]);
                  match two over
                    on bytes[3] -> { stdio.stdout.write(0); }
                    on bytes[3 | _] -> { stdio.stdout.write(7); }
                    on _ -> { stdio.stdout.write(1); }
                  end
                  return;
                }
                """));
    }

    @Test
    void bitPatternsUseMsbFirstAndExactBitLength() throws Exception {
        assertEquals("42|7", run("""
                pub routine main(): void {
                  val data = Bytes.from_list([165]);
                  match data over
                    on bits[1, 0, 1, 0, 0, 1, 0, 1] -> { stdio.stdout.write(42); }
                    on _ -> { stdio.stdout.write(0); }
                  end
                  stdio.stdout.write("|");
                  match data over
                    on bits[1] -> { stdio.stdout.write(0); }
                    on bits[1, 0, 1 | _] -> { stdio.stdout.write(7); }
                    on _ -> { stdio.stdout.write(1); }
                  end
                  return;
                }
                """));
    }

    @Test
    void emptyByteAndBitPatternsMatchOnlyEmptySource() throws Exception {
        assertEquals("42|7", run("""
                pub routine main(): void {
                  val empty = Bytes.empty();
                  match empty over
                    on bytes[] -> { stdio.stdout.write(42); }
                    on _ -> { stdio.stdout.write(0); }
                  end
                  stdio.stdout.write("|");
                  val one = Bytes.from_list([1]);
                  match one over
                    on bits[] -> { stdio.stdout.write(0); }
                    on _ -> { stdio.stdout.write(7); }
                  end
                  return;
                }
                """));
    }

    @Test
    void typedScalarCapturesHaveCorrectStaticTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  val data = Bytes.from_list([165]);
                  match data over
                    on bytes[first, _ | _] -> {
                      val i: int = first;
                    }
                    on _ -> {}
                  end
                  match data over
                    on bits[flag | _] -> {
                      val b: bool = flag;
                    }
                    on _ -> {}
                  end
                  return;
                }
                """)));
    }

    @Test
    void binaryInputMustBeRealBytes() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                          match "GET" over
                            on bytes[71, 69, 84] -> { return; }
                            on _ -> { return; }
                          end
                        }
                        """)));
    }

    @Test
    void byteAndBitLiteralRangesAreCompileTimeErrors() {
        for (String pattern : new String[] {
                "bytes[256]", "bytes[-1]", "bits[2]", "bits[true]"
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                    Parser.parse("pub routine main(): void { match data over on "
                            + pattern + " -> {} on _ -> {} end return; }"), pattern);
        }
    }

    @Test
    void malformedOrDuplicatedTailsFailDuringParsing() {
        for (String pattern : new String[] {
                "bytes[| rest]", "bits[1 | first | second]",
                "bytes[1 | rest, 2]", "bytes[x, x | rest]",
                "bits[x | x]", "bytes[1, ..rest]"
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                    Parser.parse("pub routine main(): void { match data over on "
                            + pattern + " -> {} on _ -> {} end return; }"), pattern);
        }
    }

    @Test
    void namedBitTailFailsClosedBeforeIrUntilBitViewLifetimeProof() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                          val data = Bytes.from_list([165]);
                          match data over
                            on bits[1, 0, 1 | rest] -> {}
                            on _ -> {}
                          end
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("BitView"), failure::getMessage);
    }

    @Test
    void forgedAstCannotBypassLiteralBoundsOrDuplicateBindingChecks() {
        assertThrows(IllegalArgumentException.class, () -> new Ast.SequencePattern(
                Ast.SequenceUnit.BYTE,
                java.util.List.of(new Ast.SequenceElement(256, null)), null));
        assertThrows(IllegalArgumentException.class, () -> new Ast.SequencePattern(
                Ast.SequenceUnit.BIT,
                java.util.List.of(new Ast.SequenceElement(2, null)), null));
        assertThrows(IllegalArgumentException.class, () -> new Ast.SequencePattern(
                Ast.SequenceUnit.BYTE,
                java.util.List.of(new Ast.SequenceElement(null, "x"),
                        new Ast.SequenceElement(null, "x")), null));
        assertThrows(IllegalArgumentException.class, () -> new Ast.SequencePattern(
                Ast.SequenceUnit.BIT, java.util.List.of(), "rest"));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "byte-bit-sequence-patterns.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
