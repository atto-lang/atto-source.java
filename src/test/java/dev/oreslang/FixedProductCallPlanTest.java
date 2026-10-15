package dev.oreslang;

import dev.oreslang.types.FixedProductCallPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.oreslang.types.FixedProductCallPlan.InputSlot.named;
import static dev.oreslang.types.FixedProductCallPlan.InputSlot.positional;
import static org.junit.jupiter.api.Assertions.*;

final class FixedProductCallPlanTest {
    private static final List<String> PARAMS = List.of("a", "b", "c");

    @Test
    void tupleSpreadRetainsIdentityOrder() {
        var plan = FixedProductCallPlan.bind(PARAMS,
                List.of(positional(), positional(), positional()));
        assertEquals(List.of(0, 1, 2), plan.abiToSource());
    }

    @Test
    void recordSpreadBindsByLabelsNotFieldOrder() {
        // Record initializer evaluates a=first, c=second, b=third.
        var plan = FixedProductCallPlan.bind(PARAMS,
                List.of(named("a"), named("c"), named("b")));
        assertEquals(List.of(0, 2, 1), plan.abiToSource());
        assertEquals(List.of(0, 1, 2),
                plan.sourceOrder().stream().map(FixedProductCallPlan.BoundSlot::sourceIndex).toList());
    }

    @Test
    void positionalPrefixCanPrecedeRecordTail() {
        var plan = FixedProductCallPlan.bind(PARAMS,
                List.of(positional(), named("c"), named("b")));
        assertEquals(List.of(0, 2, 1), plan.abiToSource());
    }

    @Test
    void rejectsSlotCollisionsUnknownsAndMissingArity() {
        assertRejected("duplicate", List.of(positional(), named("a"), named("b")));
        assertRejected("duplicate", List.of(named("a"), named("a"), named("c")));
        assertRejected("unknown", List.of(named("a"), named("nope"), named("c")));
        assertRejected("arity", List.of(named("a"), named("b")));
        assertRejected("arity", List.of(named("a"), named("b"), named("c"), named("d")));
    }

    @Test
    void positionalAfterFirstLabelIsRejected() {
        assertRejected("positional", List.of(named("c"), positional(), named("a")));
    }

    @Test
    void declarationLabelDuplicatesAreRejected() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(List.of("a", "a"), List.of(positional(), positional())));
        assertTrue(failure.getMessage().contains("duplicate"), failure.getMessage());
    }

    @Test
    void checkedCallPlanCannotBeForgedThroughPublicConstructors() {
        // A total permutation is not enough to prove that a source label
        // names the target ABI slot. Only bind() has declared parameter names.
        assertEquals(0, FixedProductCallPlan.class.getConstructors().length);
        assertFalse(FixedProductCallPlan.class.isRecord());
        assertEquals(1, FixedProductCallPlan.class.getDeclaredConstructors().length);
        assertTrue(java.lang.reflect.Modifier.isPrivate(
                FixedProductCallPlan.class.getDeclaredConstructors()[0].getModifiers()));
    }

    @Test
    void selectedParameterLabelsDetermineAbiRatherThanForgedSlotNames() {
        var swapped = FixedProductCallPlan.bind(List.of("a", "b"),
                List.of(named("b"), named("a")));
        assertEquals(List.of(1, 0), swapped.abiToSource());
        assertEquals(1, swapped.sourceOrder().getFirst().parameterIndex());
        assertEquals(0, swapped.sourceOrder().getLast().parameterIndex());

        var identity = FixedProductCallPlan.bind(List.of("a", "b"),
                List.of(named("a"), named("b")));
        assertEquals(List.of(0, 1), identity.abiToSource());
        assertNotEquals(identity, swapped);
        assertEquals(swapped, FixedProductCallPlan.bind(List.of("a", "b"),
                List.of(named("b"), named("a"))));
        assertEquals(swapped.hashCode(), FixedProductCallPlan.bind(List.of("a", "b"),
                List.of(named("b"), named("a"))).hashCode());

        assertThrows(IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(List.of("a", "b"),
                        List.of(named("a"), named("a"))));
        assertThrows(IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(List.of("a", "b"),
                        List.of(named("a"), positional())));
    }

    @Test
    void binderSnapshotIsImmutableAndHandlesZeroArity() {
        assertEquals(List.of(), FixedProductCallPlan.bind(List.of(), List.of()).abiToSource());
        var inputs = new java.util.ArrayList<>(
                List.of(named("b"), named("a")));
        var plan = FixedProductCallPlan.bind(List.of("a", "b"), inputs);
        inputs.set(0, positional());
        assertEquals(List.of(1, 0), plan.abiToSource());
        assertThrows(UnsupportedOperationException.class, () -> plan.abiToSource().set(0, 0));
        assertThrows(UnsupportedOperationException.class, () -> plan.sourceOrder().clear());
    }

    @Test
    void deterministicPermutationPropertyRegression() {
        // A small reproducible fuzzing corpus for CI. Values must be assigned
        // to ABI indices without reordering lexical source evaluation.
        var random = new java.util.Random(982L);
        for (int arity = 0; arity <= 9; arity++) {
            List<String> names = new java.util.ArrayList<>();
            for (int i = 0; i < arity; i++) names.add("p" + i);
            for (int round = 0; round < 1000; round++) {
                int prefix = random.nextInt(arity + 1);
                List<Integer> namedTargets = new java.util.ArrayList<>();
                for (int i = prefix; i < arity; i++) namedTargets.add(i);
                java.util.Collections.shuffle(namedTargets, random);
                List<FixedProductCallPlan.InputSlot> inputs = new java.util.ArrayList<>();
                for (int i = 0; i < prefix; i++) inputs.add(positional());
                for (int index : namedTargets) inputs.add(named(names.get(index)));

                var plan = FixedProductCallPlan.bind(names, inputs);
                int[] oracle = new int[arity];
                for (int i = 0; i < prefix; i++) oracle[i] = i;
                for (int i = 0; i < namedTargets.size(); i++) {
                    oracle[namedTargets.get(i)] = prefix + i;
                }
                assertEquals(arity, plan.abiToSource().size());
                for (int i = 0; i < arity; i++) {
                    assertEquals(oracle[i], plan.abiToSource().get(i),
                            "arity=" + arity + " round=" + round + " ABI index=" + i);
                }
                assertEquals(plan, FixedProductCallPlan.bind(names, inputs));
            }
        }
    }

    @Test
    void labelComparisonIsCaseSensitive() {
        assertRejected("unknown", List.of(named("A"), named("b"), named("c")));
    }

    private static void assertRejected(String fragment, List<FixedProductCallPlan.InputSlot> args) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(PARAMS, args));
        assertTrue(failure.getMessage().contains(fragment), failure.getMessage());
    }
}
