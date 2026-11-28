package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DenoteTypeTest {
    private static void check(String source) {
        TypeChecker.check(Parser.parse(source));
    }

    @Test
    void transparentAliasAndForwardReference() {
        assertDoesNotThrow(() -> check("""
                denote type Age = Scalar;
                denote type Scalar = int;
                fnc identity(Age n): int { return n; }
                fnc back(int n): Age { return n; }
                """));
    }

    @Test
    void genericAliasUsesExistingChecker() {
        assertDoesNotThrow(() -> check("""
                denote type PairOf<T> = [T, T];
                fnc first(PairOf<int> n): int { return n[0]; }
                """));
    }

    @Test
    void visibilityAndLiteralUnionArePreservedInAst() {
        var program = Parser.parse("denote pub type Dice = 5 | 6 | 7;");
        var alias = (Ast.TypeAliasDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals("Dice", alias.name());
        assertEquals(Ast.Visibility.PUBLIC, alias.visibility());
        var legacy = (Ast.TypeAliasDecl) Parser.parse("type Dice = 5 | 6 | 7;")
                .modules().getFirst().declarations().getFirst();
        assertEquals(legacy.target(), alias.target());
    }

    @Test
    void rejectsMalformedAndNonTypeIntroducers() {
        for (String source : new String[] {
                "denote actor Worker;",
                "denote const x = 42;",
                "denote type X as int;",
                "denote type X = ;",
                "denote type X = int",
                "denote type X = 5 + 6;",
                "pub denote type X = int;",
                "denote async type X = int;",
                "denote pub[pkg] type X = int;"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void denotationCannotCompleteForwardSignature() {
        for (String source : new String[] {
                "declare type X; denote type X = int;",
                "denote type X = int; declare type X;"
        }) {
            var error = assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
            assertTrue(error.getMessage().contains("cannot complete a forward declaration"), error.getMessage());
        }
        assertDoesNotThrow(() -> check("declare type X; type X = int;"));
    }

    @Test
    void duplicateUnknownAndCyclicAliasesFailEvenWhenUnused() {
        for (String source : new String[] {
                "denote type X = int; denote type X = bool;",
                "denote type X = NonexistentType;",
                "denote type X = X;",
                "denote type X = Y; denote type Y = X;"
        }) {
            assertThrows(IllegalArgumentException.class, () -> check(source), source);
        }
    }

    @Test
    void contextualKeywordDoesNotRenameFunctionOrBinding() {
        assertDoesNotThrow(() -> check("""
                fnc denote(int n): int { return n; }
                fnc caller(int denote): int { return denote; }
                """));
    }
}
