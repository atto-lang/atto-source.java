package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SourceEncodingTest {
    @Test void rejectsMalformedEncodingEvenInComments() {
        String prefix = "fnc main():int {return 1;} // ";
        for (String invalid : new String[] {"\0", "\ud800", "\udc00", "\ud800x"}) {
            var failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(prefix + invalid));
            assertTrue(failure.getMessage().contains(invalid.equals("\0") ? "NUL" : "UTF-16"));
        }
        assertDoesNotThrow(() -> Parser.parse(prefix + "\ud83d\ude00"));
    }
}
