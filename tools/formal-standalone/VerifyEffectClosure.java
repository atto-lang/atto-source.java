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
        // A dynamic callback has no certified target even when its
        // descriptive label collides with an otherwise pure known definition.
        var indirect = new EffectContracts.Graph();
        indirect.add(EffectContracts.Callable.pure("entry", Set.of(),
            List.of(EffectContracts.Call.unresolved("lookalike"))));
        indirect.add(EffectContracts.Callable.inferred("lookalike", Set.of(),
            List.of()));
        require(indirect.infer().get("entry").equals(
            Set.of(EffectContracts.Effect.UNKNOWN_CALL)),
            "unresolved callback was incorrectly resolved by its label");
        try {
            indirect.validate();
            throw new AssertionError("pure caller must reject unresolved callback");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains("[unresolved] lookalike"),
                "missing trusted unresolved witness");
        }
        var trapDynamic = new EffectContracts.Graph();
        trapDynamic.add(EffectContracts.Callable.trapping("trapped", Set.of(
            EffectContracts.Effect.THROW), List.of(
            EffectContracts.Call.unresolved("lookalike"))));
        trapDynamic.add(EffectContracts.Callable.inferred("lookalike", Set.of(),
            List.of()));
        trapDynamic.add(EffectContracts.Callable.inferred("caller", Set.of(),
            List.of(EffectContracts.Call.direct("trapped"))));
        require(trapDynamic.infer().get("caller").equals(
            Set.of(EffectContracts.Effect.UNKNOWN_CALL)),
            "trap must not hide unknown callback effects");
        // An inferred/unbounded graph is useful for reporting but cannot
        // certify executable closed-world code if any target remains unknown.
        var openGraph = new EffectContracts.Graph();
        openGraph.add(EffectContracts.Callable.inferred("entry", Set.of(),
                List.of(EffectContracts.Call.unresolved("dynamic"))));
        require(openGraph.validate().get("entry").equals(
                Set.of(EffectContracts.Effect.UNKNOWN_CALL)),
                "analysis must still disclose unknown effect facts");
        try {
            openGraph.validateClosedWorld();
            throw new AssertionError("unbounded unresolved call certified");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains("[unresolved] dynamic"),
                    "closed-world negative must retain witness");
        }
        var fullyResolved = new EffectContracts.Graph();
        fullyResolved.add(EffectContracts.Callable.inferred("callee", Set.of(
                EffectContracts.Effect.IO), List.of()));
        fullyResolved.add(EffectContracts.Callable.inferred("entry", Set.of(),
                List.of(EffectContracts.Call.direct("callee"))));
        require(fullyResolved.validate().get("entry").equals(
                Set.of(EffectContracts.Effect.IO)),
                "declared direct targets remain analyzable as hypotheses");
        try {
            fullyResolved.validateClosedWorld();
            throw new AssertionError("caller-supplied direct call was accepted as compiler-proven");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains("compiler-resolved provenance"),
                    "direct call must carry a provenance-denial witness");
        }
        var zeroCallGraph = new EffectContracts.Graph();
        zeroCallGraph.add(EffectContracts.Callable.inferred("known", Set.of(
                EffectContracts.Effect.ALLOC), List.of()));
        require(zeroCallGraph.validateClosedWorld().get("known").equals(
                Set.of(EffectContracts.Effect.ALLOC)),
                "call-free known-effect graph must remain valid");
        try {
            new EffectContracts.Graph().validateClosedWorld();
            throw new AssertionError("empty graph should not self-certify");
        } catch (IllegalArgumentException expected) {
            // No vacuous empty-program closed-world certificate.
        }
        // Explicit effect ABI v1 parity with the native 10-bit mask.
        for (int mask = 0; mask <= dev.oreslang.types.EffectAbiV1.KNOWN_MASK; ++mask) {
            var row = dev.oreslang.types.EffectAbiV1.decode(mask);
            require(dev.oreslang.types.EffectAbiV1.encode(row) == mask,
                "effect ABI roundtrip failed at mask " + mask);
        }
        try {
            dev.oreslang.types.EffectAbiV1.decode(Integer.MIN_VALUE);
            throw new AssertionError("native unresolved marker must fail closed");
        } catch (IllegalArgumentException expected) {
            // not a source-declarable permission
        }
        System.out.println("PASS " + checked
            + " effect-closure roots + trap/FFI negative control (bounded model only)");
    }
}
