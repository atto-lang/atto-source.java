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
    void permitsSignedFiniteFloatLiteralsButRejectsNonFiniteValues() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Numbers as
                  final const float offset: -1.5
                  final const float other: +2.25
                end
                """)));
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Numbers as
                          final const float invalid: 1e309
                        end
                        """)));
        assertTrue(rejected.getMessage().contains("final const"), rejected.getMessage());
    }

    @Test
    void rejectsCallsEvenWhenCalleeExistsAndIsPureLooking() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc calculate(): int { return 42; }
                        define class Numbers as
                          final const int answer: calculate()
                        end
                        """)));
        assertTrue(rejected.getMessage().contains("final const"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("runtime/unsupported expression"), rejected.getMessage());
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
    void exhaustiveSmallIntegerDomainMatchesExactArithmetic() {
        // 16,641 pairs including negatives, zero and mixed signs. No guest
        // execution, Java integer-promotion ambiguity, or random seed needed.
        for (long left = -64; left <= 64; left++) {
            for (long right = -64; right <= 64; right++) {
                Ast.Expr a = new Ast.LiteralExpr(left);
                Ast.Expr b = new Ast.LiteralExpr(right);
                assertEquals(Math.addExact(left, right),
                        CompileTimeConstantEvaluator.evaluate(new Ast.BinaryExpr("+", a, b)));
                assertEquals(Math.subtractExact(left, right),
                        CompileTimeConstantEvaluator.evaluate(new Ast.BinaryExpr("-", a, b)));
                assertEquals(Math.multiplyExact(left, right),
                        CompileTimeConstantEvaluator.evaluate(new Ast.BinaryExpr("*", a, b)));
                assertEquals(left < right,
                        CompileTimeConstantEvaluator.evaluate(new Ast.BinaryExpr("<", a, b)));
                assertEquals(left == right,
                        CompileTimeConstantEvaluator.evaluate(new Ast.BinaryExpr("eq", a, b)));
                if (right != 0) {
                    assertEquals(left % right,
                            CompileTimeConstantEvaluator.evaluate(new Ast.BinaryExpr("%", a, b)));
                }
            }
        }
    }

    @Test
    void signedZeroAndIntegerTypeArePreserved() {
        Object zero = CompileTimeConstantEvaluator.evaluate(
                new Ast.UnaryExpr("-", new Ast.LiteralExpr(0.0d)));
        assertInstanceOf(Double.class, zero);
        assertEquals(Double.doubleToRawLongBits(-0.0d),
                Double.doubleToRawLongBits((Double) zero));
        Object integer = CompileTimeConstantEvaluator.evaluate(
                new Ast.UnaryExpr("-", new Ast.LiteralExpr(2L)));
        assertInstanceOf(Long.class, integer);
        assertEquals(-2L, integer);
        assertEquals(3L, CompileTimeConstantEvaluator.evaluate(
                new Ast.BinaryExpr("+", new Ast.LiteralExpr(1L), new Ast.LiteralExpr(2L))));
    }

    @Test
    void numericConstantEqualityAgreesWithGuestValueEquality() {
        java.util.function.BiFunction<Object, Object, Boolean> eq = (a, b) ->
                (Boolean) CompileTimeConstantEvaluator.evaluate(
                        new Ast.BinaryExpr("eq", new Ast.LiteralExpr(a), new Ast.LiteralExpr(b)));
        java.util.function.BiFunction<Object, Object, Boolean> neq = (a, b) ->
                (Boolean) CompileTimeConstantEvaluator.evaluate(
                        new Ast.BinaryExpr("neq", new Ast.LiteralExpr(a), new Ast.LiteralExpr(b)));

        assertTrue(eq.apply(1L, 1.0d));
        assertTrue(eq.apply(1.0d, 1L));
        assertTrue(eq.apply(+0.0d, -0.0d));
        assertTrue(eq.apply(-0.0d, 0L));
        assertFalse(neq.apply(1L, 1.0d));
        assertFalse(eq.apply(1L, 1.5d));

        // Two i64 values remain exact even when the same double rounding
        // would otherwise collapse them above the 2^53 precision boundary.
        long a = 9_007_199_254_740_992L;
        long b = 9_007_199_254_740_993L;
        assertFalse(eq.apply(a, b));
        assertTrue(neq.apply(a, b));
        // A mixed i64/f64 comparison intentionally uses the runtime's f64
        // widening semantics, even when precision is lost.
        assertTrue(eq.apply(b, (double) a));

        assertTrue(eq.apply(
                dev.oreslang.runtime.OresSymbol.process("ready"),
                dev.oreslang.runtime.OresSymbol.process("ready")));
        assertFalse(eq.apply(
                dev.oreslang.runtime.OresSymbol.process("ready"),
                "ready"));
        assertTrue(neq.apply(
                dev.oreslang.runtime.OresSymbol.process("ready"),
                "ready"));
        assertTrue(eq.apply("ready", "ready"));
        assertFalse(eq.apply(true, "true"));
    }

    @Test
    void finiteFloatEqualityCanInitializeACompileTimeBoolean() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Equality as
                  final const bool signedZero: -0.0 eq 0.0
                  final const bool sameFloat: 1.5 eq 1.5
                end
                """)));
    }

    @Test
    void bitwiseConstantsAgreeWithRuntimeShiftRules() {
        Ast.Expr four = new Ast.LiteralExpr(4L);
        Ast.Expr one = new Ast.LiteralExpr(1L);
        assertEquals(5L, CompileTimeConstantEvaluator.evaluate(
                new Ast.BinaryExpr("|", four, one)));
        assertEquals(0L, CompileTimeConstantEvaluator.evaluate(
                new Ast.BinaryExpr("&", four, one)));
        assertEquals(5L, CompileTimeConstantEvaluator.evaluate(
                new Ast.BinaryExpr("^", four, one)));
        assertEquals(16L, CompileTimeConstantEvaluator.evaluate(
                new Ast.BinaryExpr("<<", four, new Ast.LiteralExpr(2L))));
        assertEquals(2L, CompileTimeConstantEvaluator.evaluate(
                new Ast.BinaryExpr(">>", four, one)));
        assertEquals(Long.MAX_VALUE, CompileTimeConstantEvaluator.evaluate(
                new Ast.BinaryExpr(">>>", new Ast.LiteralExpr(-1L), one)));
        for (long invalid : new long[]{-1L, 64L, Long.MAX_VALUE}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> CompileTimeConstantEvaluator.evaluate(
                            new Ast.BinaryExpr("<<", one, new Ast.LiteralExpr(invalid))));
            assertTrue(error.getMessage().contains("shift distance"), error.getMessage());
        }
    }

    @Test
    void overflowingBoundaryOperationsAlwaysFailClosed() {
        for (Ast.Expr expression : java.util.List.of(
                new Ast.BinaryExpr("+", new Ast.LiteralExpr(Long.MAX_VALUE), new Ast.LiteralExpr(1L)),
                new Ast.BinaryExpr("-", new Ast.LiteralExpr(Long.MIN_VALUE), new Ast.LiteralExpr(1L)),
                new Ast.UnaryExpr("-", new Ast.LiteralExpr(Long.MIN_VALUE)),
                new Ast.BinaryExpr("*", new Ast.LiteralExpr(Long.MIN_VALUE), new Ast.LiteralExpr(2L))
        )) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> CompileTimeConstantEvaluator.evaluate(expression));
            assertTrue(failure.getMessage().contains("overflow"), failure.getMessage());
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
