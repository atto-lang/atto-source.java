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
    void forgedPublicRecordConstructorsCannotBypassPermutationProof() {
        var order = List.of(
                new FixedProductCallPlan.BoundSlot(0, 1, "b"),
                new FixedProductCallPlan.BoundSlot(1, 0, "a"));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedProductCallPlan(order, List.of(0, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(new FixedProductCallPlan.BoundSlot(0, 0, null),
                                new FixedProductCallPlan.BoundSlot(0, 1, null)),
                        List.of(0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(new FixedProductCallPlan.BoundSlot(0, 2, null)),
                        List.of(0)));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(new FixedProductCallPlan.BoundSlot(-1, 0, null)),
                        List.of(0)));
        var valid = new FixedProductCallPlan(order, List.of(1, 0));
        assertEquals(List.of(1, 0), valid.abiToSource());
    }

    @Test
    void publicConstructorCannotForgeNamedOrPositionalGrammar() {
        // These are total permutations, but none could be produced by bind().
        assertThrows(IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(new FixedProductCallPlan.BoundSlot(0, 0, "a"),
                                new FixedProductCallPlan.BoundSlot(1, 1, "a")),
                        List.of(0, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(new FixedProductCallPlan.BoundSlot(0, 0, "a"),
                                new FixedProductCallPlan.BoundSlot(1, 1, null)),
                        List.of(0, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(new FixedProductCallPlan.BoundSlot(0, 1, null),
                                new FixedProductCallPlan.BoundSlot(1, 0, "a")),
                        List.of(1, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(new FixedProductCallPlan.BoundSlot(0, 0, "  "),
                                new FixedProductCallPlan.BoundSlot(1, 1, "b")),
                        List.of(0, 1)));

        var validPrefix = new FixedProductCallPlan(
                List.of(new FixedProductCallPlan.BoundSlot(0, 0, null),
                        new FixedProductCallPlan.BoundSlot(1, 2, "c"),
                        new FixedProductCallPlan.BoundSlot(2, 1, "b")),
                List.of(0, 2, 1));
        assertEquals(List.of(0, 2, 1), validPrefix.abiToSource());
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
    void labelComparisonIsCaseSensitive() {
        assertRejected("unknown", List.of(named("A"), named("b"), named("c")));
    }

    private static void assertRejected(String fragment, List<FixedProductCallPlan.InputSlot> args) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(PARAMS, args));
        assertTrue(failure.getMessage().contains(fragment), failure.getMessage());
    }
}
