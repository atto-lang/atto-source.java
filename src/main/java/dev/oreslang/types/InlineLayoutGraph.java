package dev.oreslang.types;

import java.util.ArrayList;
import java.util.List;

/**
 * Backend-neutral inline-storage dependency admission primitive.
 * Call only after normalized, target-specific layout classification.
 * An edge from owner to target means the owner contains that target.
 * Non-inline edges terminate physical layout expansion, not type checking.
 */
public final class InlineLayoutGraph {
    private InlineLayoutGraph() { }

    public record Edge(int owner, int target, boolean inline) { }

    private static final int MAX_NODES = 16384;
    private static final int MAX_EDGES = 65536;

    /**
     * Return dependency-first layout order, or fail before layout allocation.
     * Iterative Kahn traversal avoids a recursive host stack even for a long chain.
     * Repeated edges are charged separately and cannot bypass the edge limit.
     */
    public static List<Integer> admit(int nodeCount, List<Edge> edges) {
        if (nodeCount < 0 || nodeCount > MAX_NODES || edges == null
                || edges.size() > MAX_EDGES) {
            throw new IllegalArgumentException("E_TYPE_LAYOUT_LIMIT: graph capacity");
        }
        List<List<Integer>> dependents = new ArrayList<>(nodeCount);
        int[] pending = new int[nodeCount];
        for (int i = 0; i < nodeCount; i++) dependents.add(new ArrayList<>());
        int count = 0;
        for (Edge edge : edges) {
            if (++count > MAX_EDGES) {
                throw new IllegalArgumentException("E_TYPE_LAYOUT_LIMIT: graph edge work");
            }
            if (edge == null || edge.owner() < 0 || edge.owner() >= nodeCount
                    || edge.target() < 0 || edge.target() >= nodeCount) {
                throw new IllegalArgumentException("E_TYPE_LAYOUT_INVALID: unresolved dependency");
            }
            if (!edge.inline()) continue;
            pending[edge.owner()]++;
            dependents.get(edge.target()).add(edge.owner());
        }
        List<Integer> order = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) if (pending[i] == 0) order.add(i);
        for (int i = 0; i < order.size(); i++) {
            for (int dependent : dependents.get(order.get(i))) {
                if (--pending[dependent] == 0) order.add(dependent);
            }
        }
        if (order.size() != nodeCount) {
            throw new IllegalArgumentException("E_TYPE_LAYOUT_CYCLE: cyclic inline storage dependencies");
        }
        return List.copyOf(order);
    }
}
