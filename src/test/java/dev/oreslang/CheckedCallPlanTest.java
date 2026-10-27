package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.CheckedCallPlan;
import dev.oreslang.types.FixedProductCallPlan;
import dev.oreslang.types.OptionalCallPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.oreslang.types.CheckedCallPlan.InputSlot.named;
import static dev.oreslang.types.CheckedCallPlan.InputSlot.namedProjection;
import static dev.oreslang.types.CheckedCallPlan.InputSlot.positional;
import static dev.oreslang.types.CheckedCallPlan.InputSlot.positionalProjection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CheckedCallPlanTest {
    @Test
    void requiredPlanPreservesOneLexicalSourceToAbiMapping() {
        CheckedCallPlan plan = CheckedCallPlan.required(
                List.of("a", "b", "c"),
                List.of(
                        positional(0),
                        namedProjection("c", 1, 0),
                        namedProjection("b", 1, 1)));

        assertEquals(List.of(0, 2, 1), plan.abiToSource());
        assertEquals(
                List.of(0, 2, 1),
                plan.sourceOrder().stream()
                        .map(CheckedCallPlan.BoundSlot::parameterIndex)
                        .toList());
        assertEquals(
                List.of(0, 1, 1),
                plan.sourceOrder().stream()
                        .map(CheckedCallPlan.BoundSlot::callItemIndex)
                        .toList());
        assertEquals(
                List.of(-1, 0, 1),
                plan.sourceOrder().stream()
                        .map(CheckedCallPlan.BoundSlot::projectionIndex)
                        .toList());
        assertEquals(0, plan.omittedCount());
        assertFalse(plan.isOmitted(1));
    }

    @Test
    void optionalPlanRepresentsAbsenceWithoutInventingSourceValues() {
        CheckedCallPlan plan = CheckedCallPlan.bind(
                List.of("a", "b", "c"),
                List.of(false, true, false),
                List.of(
                        positional(0),
                        named("c", 1)));

        assertEquals(List.of(0, -1, 1), plan.abiToSource());
        assertEquals(2, plan.suppliedCount());
        assertEquals(1, plan.omittedCount());
        assertTrue(plan.isOmitted(1));
        assertEquals(0, plan.parameterForSource(0));
        assertEquals(2, plan.parameterForSource(1));
    }

    @Test
    void publicConstructorRejectsLabelFormalAndPresenceForgery() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        List.of("a", "b"),
                        List.of(false, false),
                        List.of(
                                new CheckedCallPlan.BoundSlot(
                                        0, 1, "a", 0, -1),
                                new CheckedCallPlan.BoundSlot(
                                        1, 0, "b", 1, -1)),
                        List.of(1, 0)));

        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        List.of("a", "b"),
                        List.of(false, false),
                        List.of(
                                new CheckedCallPlan.BoundSlot(
                                        0, 0, null, 0, -1)),
                        List.of(0, -1)));
    }

    @Test
    void publicConstructorRejectsMixedOrReorderedProvenance() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        List.of("a", "b"),
                        List.of(false, false),
                        List.of(
                                new CheckedCallPlan.BoundSlot(
                                        0, 0, null, 0, -1),
                                new CheckedCallPlan.BoundSlot(
                                        1, 1, null, -1, -1)),
                        List.of(0, 1)));

        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        List.of("a", "b"),
                        List.of(false, false),
                        List.of(
                                new CheckedCallPlan.BoundSlot(
                                        0, 0, null, 1, 0),
                                new CheckedCallPlan.BoundSlot(
                                        1, 1, null, 0, 0)),
                        List.of(0, 1)));

        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        List.of("a", "b"),
                        List.of(false, false),
                        List.of(
                                new CheckedCallPlan.BoundSlot(
                                        0, 0, null, 0, 1),
                                new CheckedCallPlan.BoundSlot(
                                        1, 1, null, 0, 2)),
                        List.of(0, 1)));
    }

    @Test
    void checkedPlanMetadataIsIdentityBoundAndConflictChecked() {
        Ast.CallExpr source = new Ast.CallExpr(
                new Ast.NameExpr("f"),
                List.of(new Ast.LiteralExpr(1L)));
        Ast.CallExpr structurallyEqual = new Ast.CallExpr(
                new Ast.NameExpr("f"),
                List.of(new Ast.LiteralExpr(1L)));
        CheckedCallPlan plan = CheckedCallPlan.required(
                List.of("value"),
                List.of(positional(0)));

        CheckedCallPlan.record(source, plan);
        assertSame(plan, CheckedCallPlan.require(source));
        assertNull(
                CheckedCallPlan.lookup(structurallyEqual),
                "structurally equal ASTs must not share checked authority");

        CheckedCallPlan conflicting = CheckedCallPlan.required(
                List.of("other"),
                List.of(positional(0)));
        assertThrows(
                IllegalStateException.class,
                () -> CheckedCallPlan.record(source, conflicting));

        Ast.CallExpr rewritten = source.rewrite(
                source.callee(),
                source.typeArguments(),
                source.typeArgumentsPresent(),
                source.arguments());
        Ast.copyCheckedMetadata(source, rewritten);
        assertEquals(plan, CheckedCallPlan.require(rewritten));
    }

    @Test
    void legacyPlansDelegateToExactlyTheSameCanonicalBinding() {
        List<String> names = List.of("a", "b", "c");
        List<CheckedCallPlan.InputSlot> checkedFixedInputs =
                List.of(
                        positionalProjection(0, 0),
                        positionalProjection(0, 1),
                        named("c", 1));
        List<FixedProductCallPlan.InputSlot> legacyFixedInputs =
                checkedFixedInputs.stream()
                        .map(input -> new FixedProductCallPlan.InputSlot(
                                input.label(),
                                input.callItemIndex(),
                                input.projectionIndex()))
                        .toList();

        CheckedCallPlan checkedRequired =
                CheckedCallPlan.required(names, checkedFixedInputs);
        FixedProductCallPlan legacyRequired =
                FixedProductCallPlan.bind(names, legacyFixedInputs);
        assertEquals(
                checkedRequired.abiToSource(),
                legacyRequired.abiToSource());

        List<Boolean> omittable = List.of(false, true, false);
        List<CheckedCallPlan.InputSlot> checkedOptionalInputs =
                List.of(positional(0), named("c", 1));
        List<FixedProductCallPlan.InputSlot> legacyOptionalInputs =
                checkedOptionalInputs.stream()
                        .map(input -> new FixedProductCallPlan.InputSlot(
                                input.label(),
                                input.callItemIndex(),
                                input.projectionIndex()))
                        .toList();
        CheckedCallPlan checkedOptional =
                CheckedCallPlan.bind(
                        names, omittable, checkedOptionalInputs);
        OptionalCallPlan legacyOptional =
                OptionalCallPlan.bind(
                        names, omittable, legacyOptionalInputs);

        assertEquals(
                checkedOptional.abiToSource(),
                legacyOptional.abiToSource());
        assertEquals(
                checkedOptional.sourceOrder().stream()
                        .map(CheckedCallPlan.BoundSlot::parameterIndex)
                        .toList(),
                legacyOptional.sourceOrder().stream()
                        .map(OptionalCallPlan.BoundSlot::parameterIndex)
                        .toList());
    }
    @Test
    void oneRawSpreadCannotMixTupleAndRecordProjectionKinds() {
        List<String> formals = List.of("a", "b");
        List<CheckedCallPlan.InputSlot> forged =
                List.of(
                        positionalProjection(0, 0),
                        namedProjection("b", 0, 1));

        assertThrows(
                IllegalArgumentException.class,
                () -> CheckedCallPlan.required(formals, forged));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        formals,
                        List.of(false, false),
                        List.of(
                                new CheckedCallPlan.BoundSlot(0, 0, null, 0, 0),
                                new CheckedCallPlan.BoundSlot(1, 1, "b", 0, 1)),
                        List.of(0, 1)));

        List<FixedProductCallPlan.InputSlot> legacy =
                List.of(
                        FixedProductCallPlan.InputSlot.positionalProjection(0, 0),
                        FixedProductCallPlan.InputSlot.namedProjection("b", 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(formals, legacy));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(
                                new FixedProductCallPlan.BoundSlot(
                                        0, 0, null, 0, 0),
                                new FixedProductCallPlan.BoundSlot(
                                        1, 1, "b", 0, 1)),
                        List.of(0, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> OptionalCallPlan.bind(
                        formals,
                        List.of(false, true),
                        legacy));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OptionalCallPlan(
                        List.of(
                                new OptionalCallPlan.BoundSlot(
                                        0, 0, null, 0, 0),
                                new OptionalCallPlan.BoundSlot(
                                        1, 1, "b", 0, 1)),
                        List.of(0, 1),
                        List.of(false, true)));

        // Adjacent projections of different raw call items are legal, as is
        // a tuple argument followed by a separately named scalar argument.
        CheckedCallPlan separate = CheckedCallPlan.required(
                formals,
                List.of(
                        positionalProjection(0, 0),
                        named("b", 1)));
        assertEquals(List.of(0, 1), separate.abiToSource());
    }

    @Test
    void rejectsForgedTupleProjectionGapsThroughBinderAndPublicConstructors() {
        List<CheckedCallPlan.InputSlot> gap =
                List.of(
                        positionalProjection(0, 0),
                        positionalProjection(0, 2));
        assertThrows(IllegalArgumentException.class,
                () -> CheckedCallPlan.required(List.of("a", "b"), gap));
        assertThrows(IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        List.of("a", "b"),
                        List.of(false, false),
                        List.of(
                                new CheckedCallPlan.BoundSlot(0, 0, null, 0, 0),
                                new CheckedCallPlan.BoundSlot(1, 1, null, 0, 2)),
                        List.of(0, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(
                        List.of("a", "b"),
                        List.of(
                                FixedProductCallPlan.InputSlot.positionalProjection(0, 0),
                                FixedProductCallPlan.InputSlot.positionalProjection(0, 2))));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(
                                new FixedProductCallPlan.BoundSlot(0, 0, null, 0, 0),
                                new FixedProductCallPlan.BoundSlot(1, 1, null, 0, 2)),
                        List.of(0, 1)));
    }

    @Test
    void rejectsForgedRecordProjectionGapsAcrossOptionalAdapters() {
        List<CheckedCallPlan.InputSlot> gap =
                List.of(
                        namedProjection("b", 0, 0),
                        namedProjection("a", 0, 2));
        assertThrows(IllegalArgumentException.class,
                () -> CheckedCallPlan.bind(
                        List.of("a", "b", "c"),
                        List.of(false, false, true),
                        gap));
        assertThrows(IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        List.of("a", "b", "c"),
                        List.of(false, false, true),
                        List.of(
                                new CheckedCallPlan.BoundSlot(0, 1, "b", 0, 0),
                                new CheckedCallPlan.BoundSlot(1, 0, "a", 0, 2)),
                        List.of(1, 0, -1)));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallPlan.bind(
                        List.of("a", "b", "c"),
                        List.of(false, false, true),
                        List.of(
                                FixedProductCallPlan.InputSlot.namedProjection("b", 0, 0),
                                FixedProductCallPlan.InputSlot.namedProjection("a", 0, 2))));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallPlan(
                        List.of(
                                new OptionalCallPlan.BoundSlot(0, 1, "b", 0, 0),
                                new OptionalCallPlan.BoundSlot(1, 0, "a", 0, 2)),
                        List.of(1, 0, -1),
                        List.of(false, false, true)));
    }

}
