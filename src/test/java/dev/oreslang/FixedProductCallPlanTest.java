package dev.oreslang;

import dev.oreslang.types.FixedProductCallPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.oreslang.types.FixedProductCallPlan.InputSlot.named;
import static dev.oreslang.types.FixedProductCallPlan.InputSlot.positional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FixedProductCallPlanTest {
    private static final List<String> PARAMS = List.of("a", "b", "c");

    @Test
    void tupleSpreadRetainsPositionalIdentityOrder() {
        var plan = FixedProductCallPlan.bind(
                PARAMS, List.of(positional(), positional(), positional()));
        assertEquals(List.of(0, 1, 2), plan.abiToSource());
    }

    @Test
    void recordSpreadBindsByLabelsWithoutChangingLexicalEvaluationOrder() {
        // Source scalar order: a first, c second, b third.
        var plan = FixedProductCallPlan.bind(
                PARAMS, List.of(named("a"), named("c"), named("b")));
        assertEquals(List.of(0, 2, 1), plan.abiToSource());
        assertEquals(
                List.of(0, 1, 2),
                plan.sourceOrder().stream()
                        .map(FixedProductCallPlan.BoundSlot::sourceIndex)
                        .toList());
    }

    @Test
    void positionalPrefixCanPrecedeNamedOrRecordTail() {
        var plan = FixedProductCallPlan.bind(
                PARAMS, List.of(positional(), named("c"), named("b")));
        assertEquals(List.of(0, 2, 1), plan.abiToSource());
    }

    @Test
    void collisionsUnknownLabelsMissingAndExtraSlotsReject() {
        assertRejected("duplicate", List.of(positional(), named("a"), named("b")));
        assertRejected("duplicate", List.of(named("a"), named("a"), named("c")));
        assertRejected("unknown", List.of(named("a"), named("nope"), named("c")));
        assertRejected("arity", List.of(named("a"), named("b")));
        assertRejected("arity", List.of(
                named("a"), named("b"), named("c"), named("extra")));
    }

    @Test
    void positionalAfterFirstNamedSlotRejects() {
        assertRejected("positional", List.of(named("c"), positional(), named("a")));
    }

    @Test
    void declarationParameterNamesMustBeStableUniqueAndCaseSensitive() {
        var duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(
                        List.of("a", "a"), List.of(positional(), positional())));
        assertTrue(duplicate.getMessage().contains("duplicate"));

        assertRejected("unknown", List.of(named("A"), named("b"), named("c")));
    }

    @Test
    void publicConstructorCannotForgePermutationOrNamedGrammar() {
        var reordered = List.of(
                new FixedProductCallPlan.BoundSlot(0, 1, "b"),
                new FixedProductCallPlan.BoundSlot(1, 0, "a"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new FixedProductCallPlan(reordered, List.of(0, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(
                                new FixedProductCallPlan.BoundSlot(0, 0, null),
                                new FixedProductCallPlan.BoundSlot(0, 1, null)),
                        List.of(0, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(
                                new FixedProductCallPlan.BoundSlot(0, 0, "a"),
                                new FixedProductCallPlan.BoundSlot(1, 1, "a")),
                        List.of(0, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(
                                new FixedProductCallPlan.BoundSlot(0, 0, "a"),
                                new FixedProductCallPlan.BoundSlot(1, 1, null)),
                        List.of(0, 1)));

        var valid = new FixedProductCallPlan(reordered, List.of(1, 0));
        assertEquals(List.of(1, 0), valid.abiToSource());
    }

    @Test
    void spreadProjectionProvenanceSurvivesNamedAbiPermutation() {
        var plan = FixedProductCallPlan.bind(
                PARAMS,
                List.of(
                        positional(0),
                        FixedProductCallPlan.InputSlot.namedProjection(
                                "c", 1, 0),
                        FixedProductCallPlan.InputSlot.namedProjection(
                                "b", 1, 1)));

        assertEquals(List.of(0, 2, 1), plan.abiToSource());
        assertEquals(
                List.of(0, 1, 1),
                plan.sourceOrder().stream()
                        .map(FixedProductCallPlan.BoundSlot::callItemIndex)
                        .toList());
        assertEquals(
                List.of(-1, 0, 1),
                plan.sourceOrder().stream()
                        .map(FixedProductCallPlan.BoundSlot::projectionIndex)
                        .toList());
    }

    @Test
    void publicConstructorRejectsForgedOrMixedSourceProvenance() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(
                                new FixedProductCallPlan.BoundSlot(
                                        0, 0, null, 0, -1),
                                new FixedProductCallPlan.BoundSlot(
                                        1, 1, null)),
                        List.of(0, 1)));

        assertThrows(
                IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(
                                new FixedProductCallPlan.BoundSlot(
                                        0, 0, null, 0, 1),
                                new FixedProductCallPlan.BoundSlot(
                                        1, 1, null, 0, 2)),
                        List.of(0, 1)));

        assertThrows(
                IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(
                                new FixedProductCallPlan.BoundSlot(
                                        0, 0, null, 1, 0),
                                new FixedProductCallPlan.BoundSlot(
                                        1, 1, null, 0, 0)),
                        List.of(0, 1)));
    }

    @Test
    void planSnapshotsInputsAndIsImmutableIncludingZeroArity() {
        assertEquals(
                List.of(),
                FixedProductCallPlan.bind(List.of(), List.of()).abiToSource());

        var inputs = new ArrayList<>(List.of(named("b"), named("a")));
        var plan = FixedProductCallPlan.bind(List.of("a", "b"), inputs);
        inputs.set(0, positional());

        assertEquals(List.of(1, 0), plan.abiToSource());
        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.abiToSource().set(0, 0));
        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.sourceOrder().clear());
    }

    private static void assertRejected(
            String fragment,
            List<FixedProductCallPlan.InputSlot> args) {
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(PARAMS, args));
        assertTrue(failure.getMessage().contains(fragment), failure.getMessage());
    }
}
