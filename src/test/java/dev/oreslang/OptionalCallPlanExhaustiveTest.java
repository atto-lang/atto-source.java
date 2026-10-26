package dev.oreslang;

import dev.oreslang.types.FixedProductCallPlan;
import dev.oreslang.types.OptionalCallPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static dev.oreslang.types.FixedProductCallPlan.InputSlot.named;
import static dev.oreslang.types.FixedProductCallPlan.InputSlot.positional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exhausts every optionality mask and every legal positional-prefix +
 * named-tail supplied shape through arity six.
 */
final class OptionalCallPlanExhaustiveTest {
    @Test
    void everyFiniteOmissionMaskAndNamedPermutationIsTotalAndDeterministic() {
        for (int arity = 0; arity <= 6; arity++) {
            List<String> names = new ArrayList<>(arity);
            for (int i = 0; i < arity; i++) names.add("p" + i);

            int masks = 1 << arity;
            for (int optionalMask = 0; optionalMask < masks; optionalMask++) {
                List<Boolean> omittable = new ArrayList<>(arity);
                for (int i = 0; i < arity; i++) {
                    omittable.add((optionalMask & (1 << i)) != 0);
                }

                for (int suppliedMask = 0; suppliedMask < masks; suppliedMask++) {
                    int firstMissing = arity;
                    for (int i = 0; i < arity; i++) {
                        if ((suppliedMask & (1 << i)) == 0) {
                            firstMissing = i;
                            break;
                        }
                    }

                    // A positional prefix cannot jump over a missing slot.
                    for (int prefix = 0; prefix <= firstMissing; prefix++) {
                        List<Integer> tail = new ArrayList<>();
                        for (int i = prefix; i < arity; i++) {
                            if ((suppliedMask & (1 << i)) != 0) tail.add(i);
                        }
                        verifyPermutations(
                                names,
                                omittable,
                                suppliedMask,
                                prefix,
                                tail,
                                new ArrayList<>());
                    }
                }
            }
        }
    }

    private static void verifyPermutations(
            List<String> names,
            List<Boolean> omittable,
            int suppliedMask,
            int prefix,
            List<Integer> remaining,
            List<Integer> order) {
        if (!remaining.isEmpty()) {
            for (int i = 0; i < remaining.size(); i++) {
                List<Integer> rest = new ArrayList<>(remaining);
                int target = rest.remove(i);
                order.add(target);
                verifyPermutations(
                        names,
                        omittable,
                        suppliedMask,
                        prefix,
                        rest,
                        order);
                order.remove(order.size() - 1);
            }
            return;
        }

        List<FixedProductCallPlan.InputSlot> inputs = new ArrayList<>();
        int[] expected = new int[names.size()];
        Arrays.fill(expected, -1);

        for (int i = 0; i < prefix; i++) {
            inputs.add(positional());
            expected[i] = i;
        }
        for (int i = 0; i < order.size(); i++) {
            int target = order.get(i);
            inputs.add(named(names.get(target)));
            expected[target] = prefix + i;
        }

        boolean missingRequired = false;
        for (int i = 0; i < names.size(); i++) {
            if ((suppliedMask & (1 << i)) == 0 && !omittable.get(i)) {
                missingRequired = true;
                break;
            }
        }

        if (missingRequired) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> OptionalCallPlan.bind(names, omittable, inputs));
            return;
        }

        OptionalCallPlan plan =
                OptionalCallPlan.bind(names, omittable, inputs);
        assertEquals(
                Arrays.stream(expected).boxed().toList(),
                plan.abiToSource());
        assertEquals(omittable, plan.omittable());
        assertEquals(inputs.size(), plan.suppliedCount());
        assertEquals(names.size() - inputs.size(), plan.omittedCount());

        for (int i = 0; i < plan.sourceOrder().size(); i++) {
            OptionalCallPlan.BoundSlot slot = plan.sourceOrder().get(i);
            assertEquals(i, slot.sourceIndex());
            assertEquals(
                    expected[slot.parameterIndex()],
                    slot.sourceIndex());
        }

        // Canonical construction must revalidate the same omission mask,
        // source density and ABI mapping rather than trusting bind().
        assertEquals(
                plan,
                new OptionalCallPlan(
                        plan.sourceOrder(),
                        plan.abiToSource(),
                        plan.omittable()));
    }
}
