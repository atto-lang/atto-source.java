package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded production parser admission; not stored Pair or guest proof. */
final class InlinePairLiteralParserTest {
    private static Object parse(String body) {
        return Parser.parseStrictAtto("pub fnc main(): void { " + body + " return; }");
    }
    private static void rejects(String body, String diagnostic) {
        var e = assertThrows(IllegalArgumentException.class, () -> parse(body));
        assertTrue(e.getMessage().contains(diagnostic), e::getMessage);
    }
    @Test void atomicTokenDoesNotJoinAcrossTrivia() {
        var tokens = new Lexer("x:=5 x: =5 x:/* gap */=5 x::y").scan();
        assertEquals(1L, tokens.stream().filter(t -> t.type() == Token.Type.COLON_EQUAL).count());
        assertEquals(2L, tokens.stream().filter(t -> t.type() == Token.Type.COLON).count());
        assertEquals(1L, tokens.stream().filter(t -> t.type() == Token.Type.DOUBLE_COLON).count());
    }
    @Test void mapAndRecordInlineFactoryEquivalence() {
        assertEquals(parse("val r = Record.of(x := 5);"), parse("val r = Record.of(Pair.of(\"x\", 5));"));
        assertEquals(parse("val r = Record.of(\"x\" := 5);"), parse("val r = Record.of(Pair.of(\"x\", 5));"));
        assertEquals(parse("val m = Map.of(x := 5);"), parse("val m = Map.of(Pair.of(\"x\", 5));"));
        assertEquals(parse("val m = Map.of(\"not an identifier\" := 5);"), parse("val m = Map.of(Pair.of(\"not an identifier\", 5));"));
    }
    @Test void noUnsupportedStoredPairOrImplicitReclassification() {
        rejects("const x := 5;", "E_PAIR_STORAGE_UNSUPPORTED");
        rejects("const x = x := 5;", "E_PAIR_STORAGE_UNSUPPORTED");
        rejects("val r = Record.of(x := y := 5);", "E_PAIR_STORAGE_UNSUPPORTED");
        rejects("val r = Record.of(x := 1, x := 2);", "duplicate");
        rejects("val r = Record.of(\"not an identifier\" := 5);", "valid static parameter identifier");
        rejects("val m = Map.of(x := 1, \"x\" := 2);", "duplicate");
        assertThrows(IllegalArgumentException.class, () -> parse("val r = {x := 5};"));
        assertThrows(IllegalArgumentException.class, () -> parse("val m = Map.of_entries(x := 5);"));
    }
}
