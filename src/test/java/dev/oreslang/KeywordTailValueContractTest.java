package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class KeywordTailValueContractTest {
    private static void check(String body) {
        TypeChecker.check(Parser.parse("pub fnc main(): int { " + body + " }"));
    }

    @Test
    void explicitAndImplicitKeywordReturnsTypecheck() {
        assertDoesNotThrow(() -> check("if true then return 42; else return 1; fi"));
        assertDoesNotThrow(() -> check("if true then 42; else 1; fi"));
    }

    @Test
    void commaSequenceSupportsContextualValLocal() {
        assertDoesNotThrow(() -> check("""
            if true then
              let int val = 5,
              val + 42
            else
              1
            fi
            """));
    }

    @Test
    void nonTailIfDoesNotReturnEarly() {
        assertDoesNotThrow(() -> check("if true then 42; else 1; fi return 99;"));
    }

    @Test
    void bracedIfHasNoImplicitReturn() {
        assertThrows(IllegalArgumentException.class,
            () -> check("if true { 42; } else { 1; }"));
    }

    @Test
    void incompleteKeywordIfHasNoGuaranteedReturn() {
        assertThrows(IllegalArgumentException.class,
            () -> check("if true then 42 fi"));
    }
}
