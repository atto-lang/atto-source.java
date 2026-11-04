package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compile-time evaluation must not silently wrap integers, grant runtime
 * authority, or change finite IEEE relational semantics.
 */
final class CompileTimeConstEvaluatorHardeningTest {
    private static Ast.LiteralExpr literal(Object value) {
        return new Ast.LiteralExpr(value);
    }

    private static Object value(Ast.Expr expression) {
        CompileTimeConstEvaluator.Evaluation result =
                CompileTimeConstEvaluator.evaluate(expression);
        return assertInstanceOf(Ast.LiteralExpr.class, result.expression()).value();
    }

    private static CompileTimeConstEvaluator.Failure fails(Ast.Expr expression) {
        return assertThrows(CompileTimeConstEvaluator.Failure.class,
                () -> CompileTimeConstEvaluator.evaluate(expression));
    }

    @Test
    void checkedIntegerArithmeticRejectsOverflowInsteadOfWraparound() {
        for (Ast.Expr expression : new Ast.Expr[] {
                new Ast.BinaryExpr("+", literal(Long.MAX_VALUE), literal(1L)),
                new Ast.BinaryExpr("-", literal(Long.MIN_VALUE), literal(1L)),
                new Ast.BinaryExpr("*", literal(Long.MAX_VALUE), literal(2L)),
                new Ast.UnaryExpr("-", literal(Long.MIN_VALUE))
        }) {
            CompileTimeConstEvaluator.Failure error = fails(expression);
            assertEquals(CompileTimeConstEvaluator.FailureKind.INVALID, error.kind());
            assertTrue(error.getMessage().contains("overflow"), error::toString);
        }
        assertEquals(42L, value(new Ast.BinaryExpr("+", literal(40L), literal(2L))));
        assertEquals(-42L, value(new Ast.UnaryExpr("-", literal(42L))));
    }

    @Test
    void largeMixedIntegerFloatComparisonDoesNotRoundIntToDouble() {
        long exactlyAfterTwoTo53 = 9_007_199_254_740_993L;
        double twoTo53 = 9_007_199_254_740_992.0d;
        assertEquals(true, value(new Ast.BinaryExpr(">",
                literal(exactlyAfterTwoTo53), literal(twoTo53))));
        assertEquals(false, value(new Ast.BinaryExpr("==",
                literal(exactlyAfterTwoTo53), literal(twoTo53))));
        assertEquals(true, value(new Ast.BinaryExpr("<",
                literal(twoTo53), literal(exactlyAfterTwoTo53))));
    }

    @Test
    void ieeePositiveAndNegativeZeroCompareEqual() {
        assertEquals(true, value(new Ast.BinaryExpr("==",
                literal(-0.0d), literal(+0.0d))));
        assertEquals(false, value(new Ast.BinaryExpr("<",
                literal(-0.0d), literal(+0.0d))));
        assertEquals(false, value(new Ast.BinaryExpr(">",
                literal(+0.0d), literal(-0.0d))));
    }

    @Test
    void nonFiniteConstantIsNotSilentlyEmbeddedIntoAotImage() {
        for (double floating : new double[] { Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
            CompileTimeConstEvaluator.Failure failure = fails(literal(floating));
            assertEquals(CompileTimeConstEvaluator.FailureKind.INVALID, failure.kind());
            assertTrue(failure.getMessage().contains("non-finite"), failure::toString);
        }
        assertTrue(fails(new Ast.BinaryExpr("/", literal(1.0d), literal(0.0d)))
                .getMessage().contains("non-finite"));
    }

    @Test
    void boundedStringsRejectOversizedLiteralAndConcatenation() {
        String limit = "x".repeat(CompileTimeConstEvaluator.MAX_STRING_CHARS);
        assertEquals(limit, value(literal(limit)));

        CompileTimeConstEvaluator.Failure oversized = fails(literal(limit + "x"));
        assertEquals(CompileTimeConstEvaluator.FailureKind.RESOURCE, oversized.kind());

        CompileTimeConstEvaluator.Failure concat = fails(
                new Ast.BinaryExpr("+", literal(limit), literal("x")));
        assertEquals(CompileTimeConstEvaluator.FailureKind.RESOURCE, concat.kind());
        assertTrue(concat.getMessage().contains("string length"), concat::toString);
    }

    @Test
    void runtimeNamesAreNeverEvaluatedDuringCompilation() {
        CompileTimeConstEvaluator.Failure failure = fails(new Ast.NameExpr("runtime_value"));
        assertEquals(CompileTimeConstEvaluator.FailureKind.RUNTIME_DEPENDENT, failure.kind());
    }
}
