package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser tests over the actual complete AttoLang AST and source lexer.
 * This suite does not execute guest code or assert full AOT parity.
 */
final class ByteBitSequenceParserTest {
    private static Ast.SequencePattern firstPattern(String source) {
        Ast.Program program = Parser.parse("""
                pub routine main(): void {
                    val data = Bytes.from_list([165]);
                    match data over
                      on %s -> {}
                      on _ -> {}
                    end
                    return;
                }
                """.formatted(source));
        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules().getFirst()
                .declarations().stream().filter(d -> d instanceof Ast.FunctionDecl)
                .findFirst().orElseThrow();
        Ast.MatchStmt matched = (Ast.MatchStmt) main.body().stream()
                .filter(s -> s instanceof Ast.MatchStmt)
                .findFirst().orElseThrow();
        return (Ast.SequencePattern) matched.arms().getFirst().pattern();
    }

    @Test
    void bytesSingletonIsAValueLiteralNotAWidth() {
        Ast.SequencePattern seq = firstPattern("bytes[3]");
        assertEquals(Ast.SequenceUnit.BYTE, seq.unit());
        assertEquals(1, seq.elements().size());
        assertEquals(3, seq.elements().getFirst().literal());
        assertNull(seq.tail());
    }

    @Test
    void bytesWithFinalTailIsOrderedFixedPrefix() {
        Ast.SequencePattern seq = firstPattern("bytes[71, 69, 84 | rest]");
        assertEquals(Ast.SequenceUnit.BYTE, seq.unit());
        assertEquals(List.of(71, 69, 84),
                seq.elements().stream().map(Ast.SequenceElement::literal).toList());
        assertEquals("rest", seq.tail());
    }

    @Test
    void bitsSequenceIsPositionalOneBitAtATime() {
        Ast.SequencePattern seq = firstPattern("bits[1, 0, 1 | tail]");
        assertEquals(Ast.SequenceUnit.BIT, seq.unit());
        assertEquals(List.of(1, 0, 1),
                seq.elements().stream().map(Ast.SequenceElement::literal).toList());
        assertEquals("tail", seq.tail());
    }

    @Test
    void bitsCaptureAndWildcardAreTypedAstNodes() {
        Ast.SequencePattern seq = firstPattern("bits[flag, _, 1 | _]");
        assertEquals("flag", seq.elements().get(0).binding());
        assertEquals("_", seq.elements().get(1).binding());
        assertEquals(1, seq.elements().get(2).literal());
        assertEquals("_", seq.tail());
    }

    @Test
    void zeroWidthSequencePatternsAreValidOnlyWithoutTail() {
        assertTrue(firstPattern("bytes[]").elements().isEmpty());
        assertTrue(firstPattern("bits[]").elements().isEmpty());
        assertNull(firstPattern("bytes[]").tail());
        assertNull(firstPattern("bits[]").tail());
    }

    @Test
    void invalidByteBitLiteralRangesRejectBeforeAstEmission() {
        for (String pattern : List.of("bytes[256]", "bytes[-1]", "bits[2]",
                "bits[-1]", "bits[true]", "bytes[999999999999999999999]")) {
            assertThrows(IllegalArgumentException.class, () -> firstPattern(pattern), pattern);
        }
    }

    @Test
    void malformedOrAmbiguousTailSyntaxRejects() {
        for (String pattern : List.of("bytes[| rest]", "bits[| rest]",
                "bytes[1 | rest, 2]", "bits[1 | rest | more]", "bytes[1, ..rest]",
                "bytes[x, x | rest]", "bits[x | x]", "bytes[1,]",
                "bits[1,, 0]", "bytes[1 2]")) {
            assertThrows(IllegalArgumentException.class, () -> firstPattern(pattern), pattern);
        }
    }

    @Test
    void parserCannotBeTrickedIntoUnboundedPrefixPlan() {
        StringBuilder large = new StringBuilder("bytes[");
        for (int i = 0; i < 257; i++) {
            if (i > 0) large.append(',');
            large.append(1);
        }
        large.append(']');
        assertThrows(IllegalArgumentException.class, () -> firstPattern(large.toString()));
    }

    @Test
    void packetTypedFieldSyntaxStillFailsClosedUntilDedicatedLowering() {
        assertThrows(IllegalArgumentException.class, () -> firstPattern("packet {payload: bytes[3]}"));
        assertThrows(IllegalArgumentException.class, () -> firstPattern("binary {flags: bits[3]}"));
    }

    @Test
    void symbolLiteralsAndOrdinaryArraySyntaxRemainIndependent() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub routine main(): void {
                    val symbol = #ready;
                    val data = [1, 2, 3];
                    val first = data[0];
                    return;
                }
                """));
    }
}
