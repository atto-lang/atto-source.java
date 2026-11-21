package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser-only parity checks against atto-spec #174/#183 and Rust/Wasm #479.
 * Not an executed JVM guest or a typed value-expression implementation.
 */
final class AttoDoNegatedConditionalParserTest {
    private static Ast.IfStmt firstConditional(String statement) {
        Ast.Program program = Parser.parse("""
                fnc choose(bool ready, bool other): int {
                """ + statement + """
                  return 0;
                }
                """);
        Ast.FunctionDecl function = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        return assertInstanceOf(Ast.IfStmt.class, function.body().getFirst());
    }

    @Test
    void proceduralNegatedHeadsUseTheSameCheckedConditionalAst() {
        for (String head : new String[] {"ifnot", "unless"}) {
            Ast.IfStmt conditional = firstConditional(
                    "do " + head + " ready then return 42; else return 7; fi\n");
            Ast.UnaryExpr negated = assertInstanceOf(Ast.UnaryExpr.class,
                    conditional.branches().getFirst().condition());
            assertEquals("!", negated.operator());
            assertInstanceOf(Ast.NameExpr.class, negated.operand());
            assertEquals(1, conditional.branches().size());
            assertFalse(conditional.elseBody().isEmpty());
        }

        Ast.IfStmt positive = firstConditional(
                "do if ready then return 42; else return 7; fi\n");
        assertInstanceOf(Ast.NameExpr.class, positive.branches().getFirst().condition());
    }

    @Test
    void inversionAppliesOnlyToTheInitialConditionNotElif() {
        Ast.IfStmt conditional = firstConditional(
                "do unless ready then return 42; elif other then return 7; else return 3; fi\n");
        assertEquals(2, conditional.branches().size());
        assertInstanceOf(Ast.UnaryExpr.class, conditional.branches().getFirst().condition());
        assertInstanceOf(Ast.NameExpr.class, conditional.branches().get(1).condition());
    }

    @Test
    void nonProceduralKeywordHeadsRetainCompatibility() {
        assertInstanceOf(Ast.UnaryExpr.class, firstConditional(
                "unless ready then return 42; else return 7; fi\n")
                .branches().getFirst().condition());
        assertInstanceOf(Ast.UnaryExpr.class, firstConditional(
                "ifnot ready then return 42; else return 7; fi\n")
                .branches().getFirst().condition());
    }

    @Test
    void unsupportedBindingsAndMalformedProceduralHeadsFailClosed() {
        for (String statement : new String[] {
                "do ifnot let value = ready then return 1; fi",
                "do unless let value = ready then return 1; fi",
                "do ifnot ready return 1; fi",
                "do unless ready return 1; fi"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> firstConditional(statement), statement);
        }
    }
}
