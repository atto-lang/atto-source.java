package dev.oreslang;

import dev.oreslang.types.CheckedCallPlan;
import dev.oreslang.types.FixedProductCallPlan;
import dev.oreslang.types.OptionalCallPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.oreslang.types.FixedProductCallPlan.InputSlot.named;
import static dev.oreslang.types.FixedProductCallPlan.InputSlot.namedProjection;
import static dev.oreslang.types.FixedProductCallPlan.InputSlot.positional;
import static dev.oreslang.types.FixedProductCallPlan.InputSlot.positionalProjection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void legacyPlansDelegateToExactlyTheSameCanonicalBinding() {
        List<String> names = List.of("a", "b", "c");
        List<FixedProductCallPlan.InputSlot> fixedInputs =
                List.of(
                        positionalProjection(0, 0),
                        positionalProjection(0, 1),
                        named("c", 1));

        CheckedCallPlan checkedRequired =
                CheckedCallPlan.required(names, fixedInputs);
        FixedProductCallPlan legacyRequired =
                FixedProductCallPlan.bind(names, fixedInputs);
        assertEquals(
                checkedRequired.abiToSource(),
                legacyRequired.abiToSource());

        List<Boolean> omittable = List.of(false, true, false);
        List<FixedProductCallPlan.InputSlot> optionalInputs =
                List.of(positional(0), named("c", 1));
        CheckedCallPlan checkedOptional =
                CheckedCallPlan.bind(
                        names, omittable, optionalInputs);
        OptionalCallPlan legacyOptional =
                OptionalCallPlan.bind(
                        names, omittable, optionalInputs);

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
}
