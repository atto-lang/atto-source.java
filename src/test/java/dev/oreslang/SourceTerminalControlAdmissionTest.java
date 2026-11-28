package dev.oreslang;

import dev.oreslang.parser.Lexer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SourceTerminalControlAdmissionTest {
    @Test
    void rawEscAndDelAreRejectedBeforeTokenizationIncludingTrivia() {
        for (char control : new char[]{'\u001b', '\u007f'}) {
            for (String source : new String[]{
                    "pub fnc main():int{/*" + control + "*/return 42;}",
                    "pub fnc main():int{return 42;}//" + control,
                    "pub fnc main():int{val string x = \"" + control + "\";return 42;}"
            }) {
                var error = assertThrows(IllegalArgumentException.class,
                        () -> new Lexer(source).scan());
                assertTrue(error.getMessage().contains("terminal control"));
            }
        }
    }

    @Test
    void normalWhitespaceAndStringsRemainLexable() {
        assertDoesNotThrow(() -> new Lexer(
                "pub fnc main():int{\r\n\treturn 42;\r\n}").scan());
        assertDoesNotThrow(() -> new Lexer(
                "pub fnc main():int{val string s = \"ordinary\";return 42;}").scan());
    }
}
