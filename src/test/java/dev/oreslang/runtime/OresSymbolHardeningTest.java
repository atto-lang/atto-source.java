package dev.oreslang.runtime;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OresSymbolHardeningTest {
    @Test void normalizationIsBoundedBeforeProcessingUntrustedRawInput() {
        assertThrows(IllegalArgumentException.class,
                () -> OresSymbol.process("a".repeat(1_025)));
        assertThrows(IllegalArgumentException.class,
                () -> OresSymbol.process("x" + (char) 0xD800));
        assertThrows(IllegalArgumentException.class,
                () -> OresSymbol.process((char) 0xDC00 + "x"));

        // Canonically equivalent composed and decomposed names have one identity.
        assertEquals(OresSymbol.process("café"), OresSymbol.process("cafe\u0301"));
        assertEquals(256, OresSymbol.process("é".repeat(128)).name()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test void printedProcessSymbolsRoundTripThroughActualLexer() {
        String escapedName = "a" + (char) 92 + "b" + (char) 34 + "c";
        for (String name : List.of(
                "a".repeat(128),
                "a".repeat(129),
                "café",
                escapedName,
                "Symbol.iterator")) {
            OresSymbol original = OresSymbol.process(name);
            List<Token> scanned = new Lexer(original.toString()).scan();
            assertEquals(Token.Type.SYMBOL_LITERAL, scanned.getFirst().type(), original.toString());
            assertEquals(Token.Type.EOF, scanned.get(1).type(), original.toString());
            assertEquals(original, OresSymbol.process(scanned.getFirst().lexeme()));
        }
        assertEquals('#', OresSymbol.process("a".repeat(128)).toString().charAt(0));
        assertEquals('"', OresSymbol.process("a".repeat(129)).toString().charAt(1));
    }
}
