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

    private static void assertRejected(String fragment, List<FixedProductCallPlan.InputSlot> args) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(PARAMS, args));
        assertTrue(failure.getMessage().contains(fragment), failure.getMessage());
    }
}
