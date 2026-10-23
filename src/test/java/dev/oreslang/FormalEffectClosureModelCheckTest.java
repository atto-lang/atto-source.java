package dev.oreslang;

import dev.oreslang.types.EffectContracts;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static dev.oreslang.types.EffectContracts.Effect.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounded exhaustive verification of the independent call-graph effect lattice:
 * 2^9 call graphs * 2^6 direct-effect assignments = 32,768 models.
 * This checks the effect-library contract, NOT frontend proof of trusted calls.
 */
final class FormalEffectClosureModelCheckTest {
    private static final String[] NAMES = {"f0", "f1", "f2"};

    @Test void everySmallGraphAgreesWithIndependentReachabilityOracle() {
        for (int edges = 0; edges < (1 << 9); edges++) {
            for (int effects = 0; effects < (1 << 6); effects++) {
                var graph = new EffectContracts.Graph();
                for (int source = 0; source < 3; source++) {
                    var direct = EnumSet.noneOf(EffectContracts.Effect.class);
                    if ((effects & (1 << source)) != 0) direct.add(IO);
                    if ((effects & (1 << (source + 3))) != 0) direct.add(PANIC);
                    var calls = new ArrayList<EffectContracts.Call>();
                    for (int target = 0; target < 3; target++) {
                        if ((edges & (1 << (source * 3 + target))) != 0) {
                            calls.add(EffectContracts.Call.direct(NAMES[target]));
                        }
                    }
                    graph.add(EffectContracts.Callable.inferred(NAMES[source], direct, calls));
                }
                var actual = graph.infer();
                for (int source = 0; source < 3; source++) {
                    var visited = new boolean[3];
                    var q = new ArrayDeque<Integer>();
                    visited[source] = true;
                    q.add(source);
                    var expected = EnumSet.noneOf(EffectContracts.Effect.class);
                    while (!q.isEmpty()) {
                        int cursor = q.removeFirst();
                        if ((effects & (1 << cursor)) != 0) expected.add(IO);
                        if ((effects & (1 << (cursor + 3))) != 0) expected.add(PANIC);
                        for (int next = 0; next < 3; next++) {
                            if (!visited[next] && (edges & (1 << (cursor * 3 + next))) != 0) {
                                visited[next] = true;
                                q.addLast(next);
                            }
                        }
                    }
                    assertEquals(expected, actual.get(NAMES[source]),
                            "edge mask=" + edges + " effect mask=" + effects + " source=" + source);
                }
            }
        }
    }

    @Test void boundedAdmissionRejectsExcessiveCallEdgesAndInvalidSymbols() {
        var graph = new EffectContracts.Graph();
        var calls = new ArrayList<EffectContracts.Call>();
        for (int i = 0; i < 1025; i++) {
            calls.add(EffectContracts.Call.direct("callee"));
        }
        assertThrows(IllegalArgumentException.class, () ->
                graph.add(EffectContracts.Callable.inferred("root", Set.of(), calls)));
        assertThrows(IllegalArgumentException.class, () -> EffectContracts.Call.direct(" "));
        assertThrows(IllegalArgumentException.class, () ->
                EffectContracts.Callable.inferred(" ", Set.of(), List.of()));
    }

    @Test void trappingDoesNotSwallowPanicOrUnknownCalls() {
        var graph = new EffectContracts.Graph();
        graph.add(EffectContracts.Callable.trapping("handler", Set.of(THROW, PANIC),
                List.of(EffectContracts.Call.direct("opaque.native"))));
        graph.add(EffectContracts.Callable.inferred("caller", Set.of(),
                List.of(EffectContracts.Call.direct("handler"))));
        assertEquals(Set.of(PANIC, UNKNOWN_CALL), graph.infer().get("caller"));
    }
}
