package dev.oreslang;

import dev.oreslang.types.FixedProductCallPlan;
import dev.oreslang.types.FixedProductCallPlan.InputSlot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Raw-stdin property target for `oreslang test --fuzz`.
 *
 * This executes the real Java binder on each mutated byte sequence. It is
 * intentionally independent of JUnit and needs only a Java 21 runtime.
 * It does NOT instrument actor memory or certify runtime race freedom.
 */
public final class FixedProductCallPlanFuzzTarget {
    private FixedProductCallPlanFuzzTarget() { }

    private static int at(byte[] bytes, int index) {
        return bytes.length == 0 ? 0 : Byte.toUnsignedInt(bytes[index % bytes.length]);
    }

    public static void main(String[] args) throws IOException {
        byte[] bytes = System.in.readNBytes(129);
        if (bytes.length == 129) {
            throw new IllegalArgumentException("fuzz input exceeds 128 bytes");
        }
        int mode = at(bytes, 1) % 6;
        int arity = at(bytes, 0) % 12;
        if (mode != 0 && arity < 2) arity = 2;

        List<String> parameters = new ArrayList<>();
        for (int i = 0; i < arity; i++) parameters.add("p" + i);
        int prefix = at(bytes, 2) % (arity + 1);
        List<Integer> targets = new ArrayList<>();
        for (int i = prefix; i < arity; i++) targets.add(i);
        for (int i = targets.size() - 1; i > 0; i--) {
            Collections.swap(targets, i, at(bytes, 3 + i) % (i + 1));
        }

        List<InputSlot> inputs = new ArrayList<>();
        for (int i = 0; i < prefix; i++) inputs.add(InputSlot.positional());
        for (int target : targets) inputs.add(InputSlot.named(parameters.get(target)));

        if (mode == 0) {
            int[] oracle = new int[arity];
            for (int i = 0; i < prefix; i++) oracle[i] = i;
            for (int i = 0; i < targets.size(); i++) {
                oracle[targets.get(i)] = prefix + i;
            }

            FixedProductCallPlan plan = FixedProductCallPlan.bind(parameters, inputs);
            if (plan.abiToSource().size() != arity
                    || plan.sourceOrder().size() != arity) {
                throw new AssertionError("wrong fixed product arity");
            }
            for (int i = 0; i < arity; i++) {
                if (plan.abiToSource().get(i) != oracle[i]
                        || plan.sourceOrder().get(i).sourceIndex() != i) {
                    throw new AssertionError("binder reordered evaluation or misbound ABI slot");
                }
            }
            var saved = List.copyOf(plan.abiToSource());
            if (arity > 0) {
                parameters.set(0, "changed");
                inputs.set(0, InputSlot.named("changed"));
            }
            if (!plan.abiToSource().equals(saved)) {
                throw new AssertionError("caller mutation changed immutable plan");
            }
            return;
        }

        // Every negative case is structurally invalid irrespective of the
        // original shuffle. A successful bind is always a compiler defect.
        switch (mode) {
            case 1 -> inputs.remove(inputs.size() - 1); // missing argument
            case 2 -> inputs.set(0, InputSlot.named("__unknown")); // unknown label
            case 3 -> { // duplicate bound parameter
                inputs.set(0, InputSlot.named("p0"));
                inputs.set(1, InputSlot.named("p0"));
            }
            case 4 -> { // named-to-positional reversal
                inputs.set(0, InputSlot.named("p1"));
                inputs.set(1, InputSlot.positional());
            }
            case 5 -> parameters.set(1, parameters.get(0)); // duplicate declaration
            default -> throw new AssertionError("unexpected fuzz mode");
        }
        try {
            FixedProductCallPlan.bind(parameters, inputs);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid product call was accepted; mode=" + mode);
    }
}
