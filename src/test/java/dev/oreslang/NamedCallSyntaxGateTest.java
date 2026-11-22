package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NamedCallSyntaxGateTest {
    @Test
    void parserPreservesNamedValuesInTheirLexicalOrder() {
        Ast.Program program = Parser.parse("""
                fnc combine(int left, int right): int {
                    return left + right;
                }
                fnc caller(): int {
                    return combine(right: 2, left: 1);
                }
                """);
        Ast.FunctionDecl caller = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().get(1);
        Ast.ReturnStmt returned = (Ast.ReturnStmt) caller.body().getFirst();
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, returned.value());
        assertInstanceOf(Ast.NamedArgExpr.class, call.arguments().get(0));
        assertInstanceOf(Ast.NamedArgExpr.class, call.arguments().get(1));
        assertTrue(((Ast.NamedArgExpr) call.arguments().get(0)).name().equals("right"));
        assertTrue(((Ast.NamedArgExpr) call.arguments().get(1)).name().equals("left"));
    }

    @Test
    void parserRejectsDuplicateLabelsAndPositionalAfterNamed() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc f(int a, int b): int { return a + b; }
                fnc caller(): int { return f(a: 1, a: 2); }
                """));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("""
                        fnc f(int a, int b): int { return a + b; }
                        fnc caller(): int { return f(a: 1, 2); }
                        """));
        assertTrue(failure.getMessage().contains("positional arguments cannot follow named"));
    }

    @Test
    void parserRejectsSpreadAfterNamedAndEmptyTrailingCallItem() {
        IllegalArgumentException spread = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("""
                        fnc f(int a): int { return a; }
                        fnc caller(List<int> args): int { return f(a: 1, ...args); }
                        """));
        assertTrue(spread.getMessage().contains("spread arguments cannot follow named"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc f(int a): int { return a; }
                fnc caller(): int { return f(a: 1,); }
                """));
    }

    @Test
    void parsedNamedCallsRemainFailClosedUntilTypeOwnershipAndRuntimeConsumePlan() {
        Ast.Program program = Parser.parse("""
                fnc combine(int left, int right): int {
                    return left + right;
                }
                pub fnc main(): int {
                    return combine(right: 2, left: 1);
                }
                """);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(failure.getMessage().contains("NAMED_CALL_BINDING_UNSUPPORTED"));
    }
}
