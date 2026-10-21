package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class HatKernelAdmissionTest {
    @Test
    void kernelIsReservedAndCannotBeUsedAsIdentifier() {
        var tokens = new Lexer("kernel").scan();
        assertEquals(Token.Type.KERNEL, tokens.getFirst().type());

        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub fnc kernel(): int { return 1; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc f(): void { val kernel = 1; return; }"));
    }

    @Test
    void canonicalKernelDeclarationIsRecognizedButFailsClosedUntilLoweringExists() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("define pub kernel K as end"));
        assertTrue(failure.getMessage().contains("kernel declarations are reserved for Oreslang HAT"));
        assertTrue(failure.getMessage().contains("no implicit CPU fallback"));
    }

    @Test
    void commentsAndStringsDoNotActivateKernelBoundary() {
        assertDoesNotThrow(() -> Parser.parse("""
                // define pub kernel K as end
                pub fnc name(): string {
                  return "kernel";
                }
                """));
    }
}
