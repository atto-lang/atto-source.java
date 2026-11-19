package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.CheckedCallPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.oreslang.types.CheckedCallPlan.InputSlot.named;
import static dev.oreslang.types.CheckedCallPlan.InputSlot.positional;
import static org.junit.jupiter.api.Assertions.*;

final class CheckedCallPlanCurrentMainTest {
    @Test
    void bindsNamedAndPositionalInputsToFormalSlotsWithoutChangingSourceOrder() {
        CheckedCallPlan plan = CheckedCallPlan.bind(
                List.of("host", "port", "tag"),
                List.of(true, false, true),
                List.of(named("tag"), named("port")));

        assertEquals(List.of(-1, 1, 0), plan.abiToSource());
        assertEquals(List.of(2, 1), plan.sourceOrder().stream()
                .map(CheckedCallPlan.BoundSlot::parameterIndex).toList());
        assertEquals(2, plan.suppliedCount());
        assertEquals(1, plan.omittedCount());
        assertTrue(plan.isOmitted(0));
        assertFalse(plan.isOmitted(1));
        assertTrue(plan.isOmitted(2));
        assertEquals(1, plan.parameterForSource(1));
        assertFalse(plan.isTriviallyPositional());
    }

    @Test
    void mixesPositionalPrefixWithNamedNonTrailingOmission() {
        CheckedCallPlan plan = CheckedCallPlan.bind(
                List.of("a", "b", "c"),
                List.of(false, true, false),
                List.of(positional(), named("c")));
        assertEquals(List.of(0, -1, 1), plan.abiToSource());
        assertEquals(1, plan.omittedCount());
        assertTrue(plan.isTriviallyPositional() == false);
    }

    @Test
    void requiredAndLegacyPositionalPlansAreIdentityMappings() {
        CheckedCallPlan plan = CheckedCallPlan.required(
                List.of("a", "b", "c"),
                List.of(positional(), positional(), positional()));
        assertEquals(List.of(0, 1, 2), plan.abiToSource());
        assertEquals(0, plan.omittedCount());
        assertTrue(plan.isTriviallyPositional());
    }

    @Test
    void rejectsUnknownDuplicateMissingAndReorderedArguments() {
        assertThrows(IllegalArgumentException.class, () ->
                CheckedCallPlan.required(List.of("a", "b"), List.of(named("missing"))));
        assertThrows(IllegalArgumentException.class, () ->
                CheckedCallPlan.required(List.of("a", "b"), List.of(named("a"), named("a"))));
        assertThrows(IllegalArgumentException.class, () ->
                CheckedCallPlan.required(List.of("a", "b"), List.of(positional(), named("a"))));
        assertThrows(IllegalArgumentException.class, () ->
                CheckedCallPlan.required(List.of("a", "b"), List.of(named("b"), positional())));
        assertThrows(IllegalArgumentException.class, () ->
                CheckedCallPlan.required(List.of("a", "b"), List.of(positional())));
        assertThrows(IllegalArgumentException.class, () ->
                CheckedCallPlan.bind(List.of("a", "b"), List.of(false, false),
                        List.of(named("a"))));
    }

    @Test
    void checkedPlanIsBoundToExactCallAstIdentityAndInvalidatedOnRewrite() {
        Ast.Expr callee = new Ast.NameExpr("connect");
        Ast.Expr tagValue = new Ast.LiteralExpr("edge");
        Ast.Expr portValue = new Ast.LiteralExpr(443L);
        Ast.CallExpr source = new Ast.CallExpr(callee, List.of(
                new Ast.NamedArgExpr("tag", tagValue),
                new Ast.NamedArgExpr("port", portValue)));
        CheckedCallPlan plan = CheckedCallPlan.required(
                List.of("port", "tag"), List.of(named("tag"), named("port")));

        try (CheckedCallPlan.ValidationScope scope = CheckedCallPlan.beginValidation()) {
            CheckedCallPlan.beginRecheck(source);
            CheckedCallPlan.record(source, plan);
            scope.commit();
        }
        assertSame(plan, CheckedCallPlan.require(source));

        Ast.CallExpr unchanged = source.rewrite(
                source.callee(), source.typeArguments(),
                source.typeArgumentsPresent(), source.arguments());
        Ast.copyCheckedMetadata(source, unchanged);
        assertSame(plan, CheckedCallPlan.require(unchanged));

        Ast.CallExpr relabeled = source.rewrite(
                source.callee(), source.typeArguments(),
                source.typeArgumentsPresent(), List.of(
                        new Ast.NamedArgExpr("port", tagValue),
                        new Ast.NamedArgExpr("tag", portValue)));
        Ast.copyCheckedMetadata(source, relabeled);
        assertNull(CheckedCallPlan.lookup(relabeled),
                "rewritten labels/expressions must require a fresh semantic proof");
    }

    @Test
    void failedValidationRevokesOnlyTheCallSitesItVisited() {
        Ast.CallExpr call = new Ast.CallExpr(
                new Ast.NameExpr("f"), List.of(new Ast.LiteralExpr(1L)));
        Ast.CallExpr independent = new Ast.CallExpr(
                new Ast.NameExpr("g"), List.of(new Ast.LiteralExpr(2L)));
        CheckedCallPlan one = CheckedCallPlan.required(List.of("x"), List.of(positional()));
        CheckedCallPlan two = CheckedCallPlan.required(List.of("y"), List.of(positional()));
        CheckedCallPlan.record(call, one);
        CheckedCallPlan.record(independent, two);

        assertThrows(IllegalArgumentException.class, () -> {
            try (CheckedCallPlan.ValidationScope scope = CheckedCallPlan.beginValidation()) {
                CheckedCallPlan.beginRecheck(call);
                CheckedCallPlan.record(call, one);
                throw new IllegalArgumentException("simulated type or ownership failure");
            }
        });
        assertNull(CheckedCallPlan.lookup(call));
        assertSame(two, CheckedCallPlan.require(independent));
    }
}
