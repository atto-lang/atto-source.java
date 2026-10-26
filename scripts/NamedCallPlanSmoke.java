import dev.oreslang.types.NamedCallBindingPlan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Dependency-free regression oracle; validates the binder, not Oreslang guest execution. */
public final class NamedCallPlanSmoke {
    private static int checked = 0;

    private static void require(boolean valid) {
        if (!valid) throw new AssertionError("incorrect named-call binding");
        checked++;
    }

    private static void reject(List<String> formals, List<String> labels) {
        try {
            NamedCallBindingPlan.bind(formals, labels);
        } catch (IllegalArgumentException expected) {
            checked++;
            return;
        }
        throw new AssertionError("expected rejection: " + labels);
    }

    public static void main(String[] args) {
        List<String> names = List.of("left", "right");
        var positional = NamedCallBindingPlan.bind(names, Arrays.asList(null, null));
        require(positional.sourceToFormal().equals(List.of(0, 1)));
        var reversed = NamedCallBindingPlan.bind(names, List.of("right", "left"));
        require(reversed.sourceToFormal().equals(List.of(1, 0)));
        require(reversed.inFormalOrder(List.of(23, 19)).equals(List.of(19, 23)));
        require(NamedCallBindingPlan.bind(names, Arrays.asList(null, "right"))
                .formalToSource().equals(List.of(0, 1)));
        reject(names, List.of("left", "left"));
        reject(names, Arrays.asList(null, "left"));
        reject(names, List.of("LEFT", "right"));
        reject(names, List.of("left"));
        reject(names, Arrays.asList("right", null));
        reject(names, Arrays.asList(null, null, null));
        reject(List.of("x", "x"), List.of("x", "x"));
        reject(names, List.of("", "right"));
        try {
            reversed.sourceToFormal().add(4);
            throw new AssertionError("mutable plan");
        } catch (UnsupportedOperationException expected) {
            checked++;
        }
        try {
            reversed.inFormalOrder(List.of(1));
            throw new AssertionError("accepted wrong argument count");
        } catch (IllegalArgumentException expected) {
            checked++;
        }

        Random random = new Random(0x4f524553L);
        for (int width = 0; width <= 12; width++) {
            List<String> formals = new ArrayList<>();
            for (int i = 0; i < width; i++) formals.add("p" + i);
            for (int iteration = 0; iteration < 100; iteration++) {
                List<String> labels = new ArrayList<>(formals);
                java.util.Collections.shuffle(labels, random);
                List<Integer> sourceValues = new ArrayList<>();
                for (String label : labels) sourceValues.add(formals.indexOf(label));
                var plan = NamedCallBindingPlan.bind(formals, labels);
                List<Integer> expected = new ArrayList<>();
                for (int i = 0; i < width; i++) expected.add(i);
                require(plan.inFormalOrder(sourceValues).equals(expected));
            }
        }
        System.out.println("NamedCallBindingPlan smoke: PASS (" + checked + " checks)");
    }
}
