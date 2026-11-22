package dev.oreslang;

import dev.oreslang.types.CheckedCallPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static dev.oreslang.types.CheckedCallPlan.InputSlot.named;
import static dev.oreslang.types.CheckedCallPlan.InputSlot.positional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Independent finite-shape oracle for the source-to-formal ABI contract.
 *
 * Exercises 8 * 4 * 256 = 8,192 call shapes with presence combinations,
 * positional prefixes, named permutations, duplicate labels, and omissions.
 * The expected ABI map is built without consulting CheckedCallPlan.
 */
final class CheckedCallPlanExhaustiveTest {
    private static final List<String> FORMALS = List.of("a", "b", "c");

    @Test
    void threeFormalPresenceAndNamedPermutationMatrix() {
        int accepted = 0;
        int rejected = 0;

        for (int mask = 0; mask < 8; mask++) {
            List<Boolean> omittable = List.of(
                    (mask & 1) != 0,
                    (mask & 2) != 0,
                    (mask & 4) != 0);
            for (int prefix = 0; prefix <= 3; prefix++) {
                for (int pattern = 0; pattern < 256; pattern++) {
                    ArrayList<CheckedCallPlan.InputSlot> inputs =
                            new ArrayList<>();
                    int[] expectedAbiToSource = {-1, -1, -1};
                    boolean invalid = false;

                    for (int i = 0; i < prefix; i++) {
                        expectedAbiToSource[i] = inputs.size();
                        inputs.add(positional());
                    }

                    int encoded = pattern;
                    for (int i = 0; i < 4; i++) {
                        int digit = encoded & 3;
                        encoded >>= 2;
                        if (digit == 0) continue;
                        int formal = digit - 1;
                        if (expectedAbiToSource[formal] >= 0) {
                            invalid = true;
                        } else {
                            expectedAbiToSource[formal] = inputs.size();
                        }
                        inputs.add(named(FORMALS.get(formal)));
                    }

                    for (int i = 0; i < FORMALS.size(); i++) {
                        if (expectedAbiToSource[i] == -1
                                && !omittable.get(i)) {
                            invalid = true;
                        }
                    }
                    final List<CheckedCallPlan.InputSlot> source =
                            List.copyOf(inputs);
                    if (invalid) {
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> CheckedCallPlan.bind(
                                        FORMALS, omittable, source),
                                "mask=" + mask + ", prefix=" + prefix
                                        + ", pattern=" + pattern);
                        rejected++;
                        continue;
                    }

                    CheckedCallPlan actual =
                            CheckedCallPlan.bind(FORMALS, omittable, source);
                    List<Integer> expected =
                            Arrays.stream(expectedAbiToSource)
                                    .boxed()
                                    .toList();
                    assertEquals(expected, actual.abiToSource());
                    assertEquals(source.size(), actual.suppliedCount());
                    assertEquals(
                            (int) expected.stream()
                                    .filter(index -> index == -1)
                                    .count(),
                            actual.omittedCount());
                    for (int i = 0; i < source.size(); i++) {
                        int formal = actual.parameterForSource(i);
                        assertEquals(i, actual.abiToSource().get(formal));
                    }
                    accepted++;
                }
            }
        }

        assertEquals(8_192, accepted + rejected);
    }
}
