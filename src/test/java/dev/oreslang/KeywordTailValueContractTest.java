package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class KeywordTailValueContractTest {
    private static void check(String body) {
        TypeChecker.check(Parser.parseStrictAtto("pub fnc main(): int { " + body + " }"));
    }

    @Test
    void namedFunctionRequiresAnExplicitReturn() {
        assertDoesNotThrow(() -> check("do if true then return 42; else return 1; fi"));
        assertThrows(IllegalArgumentException.class, () -> check("if true then return 42; else return 1; fi"));
        assertThrows(IllegalArgumentException.class, () -> check("if true then return 42; fi"));
        assertThrows(IllegalArgumentException.class, () -> check("if true then 42; else 1; fi"));
        assertThrows(IllegalArgumentException.class, () -> check("42;"));
        assertDoesNotThrow(() -> check("return if true then 42 else 1 fi;"));
    }

    @Test
    void commaSequenceSupportsContextualValLocal() {
        assertDoesNotThrow(() -> check("""
            do if true then
              let int val = 5,
              return val + 42;
            else
              return 1;
            fi
            """));
    }

    @Test
    void nonTailIfDoesNotReturnEarly() {
        assertDoesNotThrow(() -> check("if true then return 42; else return 1; fi return 99;"));
    }

    @Test
    void singleArmFunctionalIfDiscardsValueAndContinues() {
        assertDoesNotThrow(() -> check("if true then return 42; fi return 99;"));
        assertDoesNotThrow(() -> check("if false then ^ 42; fi return 99;"));
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
