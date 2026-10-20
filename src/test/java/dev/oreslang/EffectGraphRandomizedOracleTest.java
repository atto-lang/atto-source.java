package dev.oreslang;

import dev.oreslang.types.EffectContracts;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static dev.oreslang.types.EffectContracts.Effect.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Independent reachability oracle; tests dynamic targets, cycles and trap edges. */
final class EffectGraphRandomizedOracleTest {
    private static final EffectContracts.Effect[] EFFECTS =
            {IO, THROW, RAISE, PANIC, CANCELLATION, UNKNOWN_CALL};

    private static Set<EffectContracts.Effect> oracle(
            List<EffectContracts.Callable> nodes, int root) {
        var result = EnumSet.noneOf(EffectContracts.Effect.class);
        for (var effect : EFFECTS) {
            boolean[] visited = new boolean[nodes.size()];
            var pending = new ArrayDeque<Integer>();
            pending.add(root);
            visited[root] = true;
            while (!pending.isEmpty()) {
                var callsite = nodes.get(pending.removeFirst());
                if (callsite.direct().contains(effect)) result.add(effect);
                for (var invocation : callsite.calls()) {
                    if (invocation.resolution()
                            == EffectContracts.Call.Resolution.UNRESOLVED) {
                        if (effect == UNKNOWN_CALL) result.add(effect);
                        continue;
                    }
                    int index = -1;
                    for (int j = 0; j < nodes.size(); j++) {
                        if (nodes.get(j).symbol().equals(invocation.target())) {
                            index = j;
                            break;
                        }
                    }
                    if (index < 0) {
                        if (effect == UNKNOWN_CALL) result.add(effect);
                        continue;
                    }
                    if (effect == THROW && nodes.get(index).trapsThrow()) continue;
                    if (!visited[index]) {
                        visited[index] = true;
                        pending.addLast(index);
                    }
                }
            }
        }
        return result;
    }

    @Test void graphFixedPointMatchesIndependentOracleAcrossDeterministicCases() {
        var random = new Random(20261020L);
        for (int iteration = 0; iteration < 2_000; iteration++) {
            var graph = new EffectContracts.Graph();
            var nodes = new ArrayList<EffectContracts.Callable>();
            for (int node = 0; node < 4; node++) {
                var direct = EnumSet.noneOf(EffectContracts.Effect.class);
                for (var effect : EFFECTS) {
                    if (random.nextInt(5) == 0) direct.add(effect);
                }
                var calls = new ArrayList<EffectContracts.Call>();
                for (int target = 0; target < 4; target++) {
                    if (random.nextBoolean()) {
                        calls.add(EffectContracts.Call.direct("n" + target));
                    }
                }
                if (random.nextInt(4) == 0) {
                    calls.add(EffectContracts.Call.unresolved("n" + random.nextInt(4)));
                }
                if (random.nextInt(7) == 0) {
                    calls.add(EffectContracts.Call.direct("missing"));
                }
                nodes.add(new EffectContracts.Callable(
                        "n" + node, direct, calls, null, random.nextBoolean()));
            }
            for (var node : nodes) graph.add(node);
            var actual = graph.infer();
            for (int root = 0; root < nodes.size(); root++) {
                assertEquals(oracle(nodes, root), actual.get("n" + root),
                        "iteration " + iteration + " root " + root);
            }
        }
    }
}
