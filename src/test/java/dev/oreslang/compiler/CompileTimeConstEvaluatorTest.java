package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class CompileTimeConstEvaluatorTest {
    @Test
    void integerDivisionHasFloatCompileTimeType() {
        CompileTimeConstEvaluator.Evaluation evaluated =
                CompileTimeConstEvaluator.evaluate(
                        new Ast.BinaryExpr(
                                "/",
                                new Ast.LiteralExpr(4L),
                                new Ast.LiteralExpr(2L)));

        assertEquals("float", evaluated.type().name());
        Ast.LiteralExpr literal =
                assertInstanceOf(Ast.LiteralExpr.class, evaluated.expression());
        assertEquals(2.0d, literal.value());
        assertFalse(CompileTimeConstEvaluator.typeMatches(
                Ast.TypeRef.simple("int"), evaluated.type()));
        assertTrue(CompileTimeConstEvaluator.typeMatches(
                Ast.TypeRef.simple("float"), evaluated.type()));
    }

    @Test
    void runtimeNamesRemainOutsideConstEvaluatorAuthority() {
        CompileTimeConstEvaluator.Failure failure = assertThrows(
                CompileTimeConstEvaluator.Failure.class,
                () -> CompileTimeConstEvaluator.evaluate(new Ast.NameExpr("runtimeValue")));

        assertEquals(
                CompileTimeConstEvaluator.FailureKind.RUNTIME_DEPENDENT,
                failure.kind());
    }

    @Test
    void nonFiniteFloatResultsFailClosedForBackendParity() {
        CompileTimeConstEvaluator.Failure failure = assertThrows(
                CompileTimeConstEvaluator.Failure.class,
                () -> CompileTimeConstEvaluator.evaluate(
                        new Ast.BinaryExpr(
                                "/",
                                new Ast.LiteralExpr(1.0d),
                                new Ast.LiteralExpr(0.0d))));

        assertEquals(CompileTimeConstEvaluator.FailureKind.INVALID, failure.kind());
        assertTrue(failure.getMessage().contains("non-finite"), failure::toString);
    }

    @Test
    void aggregateElementBudgetIsDeterministic() {
        ArrayList<Ast.Expr> elements = new ArrayList<>();
        for (int i = 0; i <= CompileTimeConstEvaluator.MAX_AGGREGATE_ELEMENTS; i++) {
            elements.add(new Ast.LiteralExpr(1L));
        }

        CompileTimeConstEvaluator.Failure failure = assertThrows(
                CompileTimeConstEvaluator.Failure.class,
                () -> CompileTimeConstEvaluator.evaluate(new Ast.ListExpr(elements)));

        assertEquals(CompileTimeConstEvaluator.FailureKind.RESOURCE, failure.kind());
        assertTrue(failure.getMessage().contains("aggregate element budget"),
                failure::toString);
    }

    @Test
    void estimatedMemoryBudgetCountsCompileTimeStringMaterialization() {
        String chunk = "x".repeat(CompileTimeConstEvaluator.MAX_STRING_CHARS);
        ArrayList<Ast.Expr> chunks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            chunks.add(new Ast.LiteralExpr(chunk));
        }

        CompileTimeConstEvaluator.Failure failure = assertThrows(
                CompileTimeConstEvaluator.Failure.class,
                () -> CompileTimeConstEvaluator.evaluate(new Ast.ListExpr(chunks)));

        assertEquals(CompileTimeConstEvaluator.FailureKind.RESOURCE, failure.kind());
        assertTrue(failure.getMessage().contains("estimated memory budget"),
                failure::toString);
    }

    @Test
    void compileTimeIntegerOverflowFailsClosedInsteadOfWrapping() {
        CompileTimeConstEvaluator.Failure failure = assertThrows(
                CompileTimeConstEvaluator.Failure.class,
                () -> CompileTimeConstEvaluator.evaluate(
                        new Ast.BinaryExpr(
                                "+",
                                new Ast.LiteralExpr(Long.MAX_VALUE),
                                new Ast.LiteralExpr(1L))));

        assertEquals(CompileTimeConstEvaluator.FailureKind.INVALID, failure.kind());
        assertTrue(failure.getMessage().contains("overflow"), failure::toString);
    }

    @Test
    void tupleAndRecordConstantsNormalizeWithoutRuntimeObjects() {
        Ast.TupleExpr tuple = new Ast.TupleExpr(List.of(
                new Ast.BinaryExpr(
                        "+", new Ast.LiteralExpr(1L), new Ast.LiteralExpr(2L)),
                new Ast.LiteralExpr("ok")));

        CompileTimeConstEvaluator.Evaluation tupleValue =
                CompileTimeConstEvaluator.evaluate(tuple);
        Ast.TupleExpr normalized =
                assertInstanceOf(Ast.TupleExpr.class, tupleValue.expression());
        assertEquals(
                List.of(3L, "ok"),
                normalized.elements().stream()
                        .map(Ast.LiteralExpr.class::cast)
                        .map(Ast.LiteralExpr::value)
                        .toList());

        Ast.ObjectExpr record = new Ast.ObjectExpr(
                List.of(
                        Ast.ObjectField.named(
                                "count",
                                new Ast.BinaryExpr(
                                        "*",
                                        new Ast.LiteralExpr(3L),
                                        new Ast.LiteralExpr(4L))),
                        Ast.ObjectField.named("label", new Ast.LiteralExpr("ready"))),
                Ast.ObjectKind.RECORD,
                List.of());

        CompileTimeConstEvaluator.Evaluation recordValue =
                CompileTimeConstEvaluator.evaluate(record);
        assertTrue(recordValue.type().isRecordType());
        Ast.ObjectExpr normalizedRecord =
                assertInstanceOf(Ast.ObjectExpr.class, recordValue.expression());
        assertEquals(12L,
                ((Ast.LiteralExpr) normalizedRecord.fields().getFirst().value()).value());
        assertEquals("ready",
                ((Ast.LiteralExpr) normalizedRecord.fields().get(1).value()).value());
    }
}
