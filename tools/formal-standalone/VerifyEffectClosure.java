import dev.oreslang.types.EffectContracts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-free JDK21 runner for the effect-closure bounded proof.
 *
 * Exhausts 512 directed three-function call graphs, 64 combinations of
 * direct IO/PANIC assignments, and all three starting roots (98,304 roots).
 * This verifies the analysis library, NOT compiler construction of call edges.
 */
public final class VerifyEffectClosure {
    private static final String[] SYMBOLS = { "f0", "f1", "f2" };

    private VerifyEffectClosure() { }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private static Set<EffectContracts.Effect> oracle(int edges, int effects, int root) {
        var reached = new boolean[3];
        var queue = new ArrayDeque<Integer>();
        reached[root] = true;
        queue.add(root);
        var result = EnumSet.noneOf(EffectContracts.Effect.class);
        while (!queue.isEmpty()) {
            int source = queue.removeFirst();
            if ((effects & (1 << source)) != 0) result.add(EffectContracts.Effect.IO);
            if ((effects & (1 << (source + 3))) != 0)
                result.add(EffectContracts.Effect.PANIC);
            for (int target = 0; target < 3; ++target) {
                if (!reached[target] && (edges & (1 << (3 * source + target))) != 0) {
                    reached[target] = true;
                    queue.addLast(target);
                }
            }
        }
        return result;
    }

    public static void main(String[] args) {
        require(args.length == 0, "no arguments accepted");
        int checked = 0;
        for (int edges = 0; edges < 512; edges++) {
            for (int effects = 0; effects < 64; effects++) {
                var graph = new EffectContracts.Graph();
                for (int source = 0; source < 3; source++) {
                    var direct = EnumSet.noneOf(EffectContracts.Effect.class);
                    if ((effects & (1 << source)) != 0) direct.add(EffectContracts.Effect.IO);
                    if ((effects & (1 << (source + 3))) != 0)
                        direct.add(EffectContracts.Effect.PANIC);
                    var calls = new ArrayList<EffectContracts.Call>();
                    for (int target = 0; target < 3; target++) {
                        if ((edges & (1 << (3 * source + target))) != 0)
                            calls.add(EffectContracts.Call.direct(SYMBOLS[target]));
                    }
                    graph.add(EffectContracts.Callable.inferred(SYMBOLS[source], direct, calls));
                }
                Map<String, Set<EffectContracts.Effect>> actual = graph.infer();
                for (int root = 0; root < 3; root++) {
                    Set<EffectContracts.Effect> expected = oracle(edges, effects, root);
                    require(expected.equals(actual.get(SYMBOLS[root])),
                        "bad transitive effects, graph=" + edges
                        + " direct=" + effects + " root=" + root);
                    ++checked;
                }
            }
        }
        var escaped = new EffectContracts.Graph();
        escaped.add(EffectContracts.Callable.trapping("trapped",
            Set.of(EffectContracts.Effect.THROW, EffectContracts.Effect.PANIC),
            List.of(EffectContracts.Call.direct("unresolved.ffi"))));
        escaped.add(EffectContracts.Callable.inferred("caller", Set.of(),
            List.of(EffectContracts.Call.direct("trapped"))));
        require(escaped.infer().get("caller").equals(Set.of(
            EffectContracts.Effect.PANIC, EffectContracts.Effect.UNKNOWN_CALL)),
            "trapping must not hide panic or missing FFI targets");
        System.out.println("PASS " + checked
            + " effect-closure roots + trap/FFI negative control (bounded model only)");
    }
}
