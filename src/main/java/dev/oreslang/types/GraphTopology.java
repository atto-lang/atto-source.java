package dev.oreslang.types;

import java.util.Arrays;
import java.util.List;

/**
 * Closed logical graph topology primitive. Ordered edge positions are semantic;
 * -1 is an absent edge. IDs and numbering are not semantic. This is not the
 * guest equality operator or a proof that arbitrary guest objects are hashable.
 */
public final class GraphTopology {
    private GraphTopology() { }

    public record Node(int type, long value, List<Integer> edges) {
        public Node { edges = List.copyOf(edges); }
    }

    public record Limits(int nodes, int edges, int work) {
        public Limits {
            if (nodes < 1 || nodes > 65536 || edges < 0 || edges > 262144
                    || work < 1 || work > 1048576) throw refused("invalid limits");
        }
    }

    /** Canonical BFS numbering of a complete strongly reachable, trusted host table. */
    public static List<Node> canonicalize(List<Node> table, int root, Limits limits) {
        // Admit the caller's immutable snapshot only; do not invoke arbitrary
        // graph-object accessors or use guest hashCode/equals to memoize identity.
        int n = table.size();
        if (n < 1 || n > limits.nodes() || root < 0 || root >= n) throw refused("root/node budget");
        table = List.copyOf(table); // trusted host snapshot, random access thereafter
        if (table.size() != n) throw refused("unstable table");
        int edges = 0;
        int work = 0;
        for (Node node : table) {
            if (node == null || node.type() < 1) throw refused("node type");
            if (++work > limits.work() || node.edges().size() > limits.edges() - edges)
                throw refused("edge/work budget");
            edges += node.edges().size();
            for (int target : node.edges()) {
                if (++work > limits.work()) throw refused("work budget");
                if (target < -1 || target >= n) throw refused("dangling edge");
            }
        }
        // Charge discovery plus remapping before allocating output.
        long remaining = 2L * n + 2L * edges;
        if (remaining > limits.work() - work) throw refused("work budget");
        int[] ids = new int[n];
        Arrays.fill(ids, -1);
        int[] order = new int[n];
        ids[root] = 0;
        order[0] = root;
        int count = 1;
        for (int cursor = 0; cursor < count; cursor++) {
            for (int target : table.get(order[cursor]).edges()) {
                if (target >= 0 && ids[target] == -1) {
                    ids[target] = count;
                    order[count++] = target;
                }
            }
        }
        if (count != n) throw refused("unreachable node");
        var canonical = new java.util.ArrayList<Node>(n);
        for (int source : order) {
            Node node = table.get(source);
            var mapped = new java.util.ArrayList<Integer>(node.edges().size());
            for (int target : node.edges()) mapped.add(target < 0 ? -1 : ids[target]);
            canonical.add(new Node(node.type(), node.value(), mapped));
        }
        return List.copyOf(canonical);
    }

    private static IllegalArgumentException refused(String reason) {
        return new IllegalArgumentException("E_GRAPH_TOPOLOGY: " + reason);
    }
}
