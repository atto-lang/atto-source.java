package dev.oreslang;

import dev.oreslang.types.FixedProductCallPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.oreslang.types.FixedProductCallPlan.InputSlot.named;
import static dev.oreslang.types.FixedProductCallPlan.InputSlot.positional;
import static org.junit.jupiter.api.Assertions.*;

/** Exhaustively prove finite source-to-ABI permutations through arity six. */
final class FixedProductCallPlanExhaustiveTest {
    @Test
    void allPositionalPrefixesAndNamedTailPermutationsMapExactlyOnce() {
        for (int arity = 0; arity <= 6; arity++) {
            for (int prefix = 0; prefix <= arity; prefix++) {
                List<Integer> remaining = new ArrayList<>();
                for (int i = prefix; i < arity; i++) remaining.add(i);
                verifyPermutations(remaining, new ArrayList<>(), arity, prefix);
            }
        }
    }

    private static void verifyPermutations(
            List<Integer> remaining, List<Integer> tail, int arity, int prefix) {
        if (remaining.isEmpty()) {
            List<String> parameters = new ArrayList<>();
            List<FixedProductCallPlan.InputSlot> slots = new ArrayList<>();
            int[] expectedAbiToSource = new int[arity];
            for (int i = 0; i < arity; i++) parameters.add("p" + i);
            for (int i = 0; i < prefix; i++) {
                slots.add(positional());
                expectedAbiToSource[i] = i;
            }
            for (int i = 0; i < tail.size(); i++) {
                int target = tail.get(i);
                slots.add(named("p" + target));
                expectedAbiToSource[target] = prefix + i;
            }
            FixedProductCallPlan plan = FixedProductCallPlan.bind(parameters, slots);
            assertEquals(arity, plan.sourceOrder().size());
            for (int i = 0; i < arity; i++) {
                assertEquals(expectedAbiToSource[i], plan.abiToSource().get(i));
                assertEquals(i, plan.sourceOrder().get(i).sourceIndex());
            }
            assertEquals(plan, new FixedProductCallPlan(plan.sourceOrder(), plan.abiToSource()));
            return;
        }
        for (int i = 0; i < remaining.size(); i++) {
            List<Integer> rest = new ArrayList<>(remaining);
            int target = rest.remove(i);
            tail.add(target);
            verifyPermutations(rest, tail, arity, prefix);
            tail.removeLast();
        }
    }

    @Test
    void forgedPermutationsAndCollisionsAreRejected() {
        List<String> params = List.of("a", "b");
        assertThrows(IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(params, List.of(named("a"), named("a"))));
        assertThrows(IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(params, List.of(named("a"), positional())));
        assertThrows(IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(params, List.of(named("a"), named("unknown"))));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(new FixedProductCallPlan.BoundSlot(0, 1, null),
                                new FixedProductCallPlan.BoundSlot(1, 0, "a")),
                        List.of(1, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(new FixedProductCallPlan.BoundSlot(0, 0, "a"),
                                new FixedProductCallPlan.BoundSlot(1, 1, "a")),
                        List.of(0, 1)));
    }
}
