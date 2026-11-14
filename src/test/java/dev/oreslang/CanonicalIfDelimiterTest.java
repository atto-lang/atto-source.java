package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CanonicalIfDelimiterTest {
    private static void check(String body) {
        Parser.parse("pub fnc main(): int { " + body + " }");
    }

    @Test
    void bracesDoNotNeedFi() {
        assertDoesNotThrow(() -> check("if true { return 42; } else { return 1; }"));
        assertDoesNotThrow(() -> check("if false { return 1; } elif true { return 42; } else { return 0; }"));
    }

    @Test
    void keywordIfNeedsFi() {
        assertDoesNotThrow(() -> check("if true then return 42; else return 1; fi"));
    }

    @Test
    void nestedMixedStyleScopesRemainDistinct() {
        assertDoesNotThrow(() -> check("""
            if true then
              if true { return 42; } else { return 1; }
            else
              return 0;
            fi
            """));
        assertDoesNotThrow(() -> check("""
            if true {
              if true then
                return 42;
              else
                return 1;
              fi
            } else { return 0; }
            """));
    }

    @Test
    void mixedTerminatorsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> check("if true { return 42; } else { return 1; } fi"));
        assertThrows(IllegalArgumentException.class,
            () -> check("if true then return 42; else return 1; }"));
        assertThrows(IllegalArgumentException.class,
            () -> check("if true { return 42; else return 1; fi"));
    }
}
