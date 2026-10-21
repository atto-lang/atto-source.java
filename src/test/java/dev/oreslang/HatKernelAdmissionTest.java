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
    void canonicalKernelContainerParses() {
        assertDoesNotThrow(() -> Parser.parse("""
                define pub kernel Add as
                  fnc main(int left, int right): int {
                    return left + right;
                  }
                end
                """));
    }

    @Test
    void kernelBodyRejectsOrdinaryDeclarations() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define pub kernel Bad as
                          define class NotDeviceCode as end
                        end
                        """));
        assertTrue(failure.getMessage().contains("kernel bodies currently admit only 'fnc'"));
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
