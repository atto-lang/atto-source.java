package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.CompileTimeConstantEvaluator;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CompileTimeConstantEvaluatorTest {
    private static Ast.FieldDecl field(String source) {
        Ast.Program program = Parser.parse("define class Constants as\n  final const int value: "
                + source + "\nend\n");
        return ((Ast.ClassDecl) program.modules().getFirst().declarations().getFirst())
                .fields().getFirst();
    }

    @Test
    void evaluatesConstantIntegerArithmeticWithPrecedence() {
        assertEquals(14L, CompileTimeConstantEvaluator.evaluate(field("2 + 3 * 4").initializer()));
        assertEquals(9L, CompileTimeConstantEvaluator.evaluate(field("(2 + 3) * 2 - 1").initializer()));
        assertEquals(-5L, CompileTimeConstantEvaluator.evaluate(field("-5").initializer()));
        assertEquals(Long.MIN_VALUE, CompileTimeConstantEvaluator.evaluate(
                field("-9223372036854775808").initializer()));
    }

    @Test
    void typeCheckerAcceptsPureConstantsButKeepsFinalStorageFixed() {
        Ast.Program program = Parser.parse("""
                define class Constants as
                  final const int answer: (2 + 3) * 4
                  final const bool ready: true && !false
                  final const string name: "hello" + " world"
                  final const Symbol state: #ready
                end
                """);
        assertDoesNotThrow(() -> TypeChecker.check(program));
        Ast.ClassDecl klass = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        for (Ast.FieldDecl declaration : klass.fields()) {
            assertTrue(declaration.finalDeclaration());
            assertTrue(declaration.compileTimeRequired());
            assertEquals(Ast.BindingKind.VAL, declaration.bindingKind());
        }
    }

    @Test
    void rejectsOverflowZeroDivisionAndRuntimeCalls() {
        for (String initializer : new String[] {
                "9223372036854775807 + 1",
                "3037000500 * 3037000500",
                "1 % 0",
                "calculate()",
                "1 + calculate()",
                "false && expensive()",
                "false ? expensive() : 7",
                "1 / 2"
        }) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "define class Constants as\n final const int value: "
                                    + initializer + "\nend\n")), initializer);
            assertTrue(failure.getMessage().contains("final const"), failure.getMessage());
        }
    }

    @Test
    void protectsEvaluatorBudgetAndRejectsUserCodeWithoutRunningIt() {
        Ast.Expr expression = new Ast.LiteralExpr(1L);
        for (int i = 0; i < 70; i++) expression = new Ast.UnaryExpr("+", expression);
        final Ast.Expr tooDeep = expression;
        assertThrows(IllegalArgumentException.class,
                () -> CompileTimeConstantEvaluator.evaluate(tooDeep));
        assertThrows(IllegalArgumentException.class,
                () -> CompileTimeConstantEvaluator.evaluate(
                        new Ast.CallExpr(new Ast.NameExpr("sideEffect"), java.util.List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> CompileTimeConstantEvaluator.evaluate(
                        new Ast.LiteralExpr("x".repeat(65_537))));
    }

    @Test
    void legacyConstBindingsDoNotAcquireGlobalCompileTimeRestrictions() {
        Ast.Program program = Parser.parse("""
                define class C as
                  const int ordinary = 5;
                  final int runtime: compute()
                end
                """);
        // Parser admission remains intact: these declarations are not
        // stamped compileTimeRequired by the explicit final const grammar.
        Ast.ClassDecl klass = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertFalse(klass.fields().get(0).compileTimeRequired());
        assertFalse(klass.fields().get(1).compileTimeRequired());
    }
}
