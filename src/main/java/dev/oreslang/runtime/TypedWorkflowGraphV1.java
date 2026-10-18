package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime-independent typed workflow graph contract.
 *
 * <p>Edges connect named outcome ports to nodes with exactly matching input
 * schema identifiers. Cycles are legal only when at least one edge inside each
 * cyclic strongly-connected component has a finite traversal budget.</p>
 */
public final class TypedWorkflowGraphV1 {

    public enum NodeKind {
        TRIGGER,
        ACTIVITY,
        WAIT,
        DECISION,
        APPROVAL,
        TERMINAL
    }

    public record Node(
            String id,
            NodeKind kind,
            String inputType,
            Map<String, String> outcomeTypes) {
        public Node {
            id = requireText(id, "node id");
            kind = Objects.requireNonNull(kind, "kind");
            inputType = requireText(inputType, "inputType");
            outcomeTypes = Map.copyOf(
                    Objects.requireNonNull(
                            outcomeTypes, "outcomeTypes"));
            for (var entry : outcomeTypes.entrySet()) {
                requireText(entry.getKey(), "outcome name");
                requireText(entry.getValue(), "outcome type");
            }
            if (kind == NodeKind.TERMINAL
                    && !outcomeTypes.isEmpty()) {
                throw new IllegalArgumentException(
                        "terminal nodes cannot emit outcomes");
            }
        }
    }

    public record Edge(
            String fromNode,
            String outcome,
            String toNode,
            Integer maxTraversals) {
        public Edge {
            fromNode = requireText(fromNode, "fromNode");
            outcome = requireText(outcome, "outcome");
            toNode = requireText(toNode, "toNode");
            if (maxTraversals != null && maxTraversals <= 0) {
                throw new IllegalArgumentException(
                        "maxTraversals must be positive when present");
            }
        }

        public boolean bounded() {
            return maxTraversals != null;
        }
    }

    private final String startNode;
    private final Map<String, Node> nodes;
    private final List<Edge> edges;

    public TypedWorkflowGraphV1(
            String startNode,
            List<Node> nodes,
            List<Edge> edges) {
        this.startNode = requireText(startNode, "startNode");
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(edges, "edges");

        LinkedHashMap<String, Node> indexed = new LinkedHashMap<>();
        for (Node node : nodes) {
            Node prior = indexed.putIfAbsent(node.id(), node);
            if (prior != null) {
                throw new IllegalArgumentException(
                        "duplicate workflow node " + node.id());
            }
        }
        this.nodes = Map.copyOf(indexed);
        this.edges = List.copyOf(edges);
        validate();
    }

    public String startNode() {
        return startNode;
    }

    public Map<String, Node> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return edges;
    }

    public List<Edge> outgoing(String nodeId) {
        requireText(nodeId, "nodeId");
        return edges.stream()
                .filter(edge -> edge.fromNode().equals(nodeId))
                .toList();
    }

    /**
     * Stable digest of the validated graph contract, independent of caller map
     * insertion order. Durable runs pin this digest before execution.
     */
    public String fingerprint() {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            nodes.values().stream()
                    .sorted(Comparator.comparing(Node::id))
                    .forEach(node -> {
                        update(digest, "node");
                        update(digest, node.id());
                        update(digest, node.kind().name());
                        update(digest, node.inputType());
                        node.outcomeTypes().entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .forEach(entry -> {
                                    update(digest, "outcome");
                                    update(digest, entry.getKey());
                                    update(digest, entry.getValue());
                                });
                    });

            edges.stream()
                    .sorted(Comparator
                            .comparing(Edge::fromNode)
                            .thenComparing(Edge::outcome)
                            .thenComparing(Edge::toNode))
                    .forEach(edge -> {
                        update(digest, "edge");
                        update(digest, edge.fromNode());
                        update(digest, edge.outcome());
                        update(digest, edge.toNode());
                        update(
                                digest,
                                edge.maxTraversals() == null
                                        ? "-"
                                        : edge.maxTraversals().toString());
                    });

            update(digest, "start");
            update(digest, startNode);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", impossible);
        }
    }

    private static void update(
            MessageDigest digest,
            String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private void validate() {
        Node start = nodes.get(startNode);
        if (start == null) {
            throw new IllegalArgumentException(
                    "workflow start node does not exist: " + startNode);
        }
        if (start.kind() != NodeKind.TRIGGER) {
            throw new IllegalArgumentException(
                    "workflow start node must be TRIGGER");
        }

        Set<String> usedOutcomes = new HashSet<>();
        for (Edge edge : edges) {
            Node from = requireNode(edge.fromNode());
            Node to = requireNode(edge.toNode());
            String outputType = from.outcomeTypes().get(edge.outcome());
            if (outputType == null) {
                throw new IllegalArgumentException(
                        "edge references missing outcome "
                                + edge.fromNode()
                                + "."
                                + edge.outcome());
            }
            if (!outputType.equals(to.inputType())) {
                throw new IllegalArgumentException(
                        "workflow type mismatch: "
                                + edge.fromNode()
                                + "."
                                + edge.outcome()
                                + " emits "
                                + outputType
                                + " but "
                                + edge.toNode()
                                + " accepts "
                                + to.inputType());
            }

            String port =
                    edge.fromNode() + "\u0000" + edge.outcome();
            if (!usedOutcomes.add(port)) {
                throw new IllegalArgumentException(
                        "outcome port has multiple transitions: "
                                + edge.fromNode()
                                + "."
                                + edge.outcome());
            }
        }

        Set<String> reachable = reachableFromStart();
        for (String node : nodes.keySet()) {
            if (!reachable.contains(node)) {
                throw new IllegalArgumentException(
                        "unreachable workflow node " + node);
            }
        }

        boolean terminalReachable = reachable.stream()
                .map(nodes::get)
                .anyMatch(node -> node.kind() == NodeKind.TERMINAL);
        if (!terminalReachable) {
            throw new IllegalArgumentException(
                    "workflow has no reachable terminal node");
        }

        validateCyclesAreBounded();
    }

    private Node requireNode(String id) {
        Node node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException(
                    "workflow edge references missing node " + id);
        }
        return node;
    }

    private Set<String> reachableFromStart() {
        Map<String, List<String>> adjacency = adjacency();
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(startNode);
        while (!pending.isEmpty()) {
            String node = pending.removeFirst();
            if (!visited.add(node)) continue;
            for (String next : adjacency.getOrDefault(
                    node, List.of())) {
                pending.addLast(next);
            }
        }
        return visited;
    }

    private Map<String, List<String>> adjacency() {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (Edge edge : edges) {
            adjacency.computeIfAbsent(
                    edge.fromNode(),
                    ignored -> new ArrayList<>())
                    .add(edge.toNode());
        }
        return adjacency;
    }

    private void validateCyclesAreBounded() {
        Map<String, List<String>> unboundedAdjacency =
                new HashMap<>();
        for (Edge edge : edges) {
            if (edge.bounded()) continue;
            unboundedAdjacency.computeIfAbsent(
                    edge.fromNode(),
                    ignored -> new ArrayList<>())
                    .add(edge.toNode());
        }

        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        ArrayDeque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        int[] nextIndex = {0};

        for (String node : nodes.keySet()) {
            if (!index.containsKey(node)) {
                tarjan(
                        node,
                        unboundedAdjacency,
                        index,
                        low,
                        stack,
                        onStack,
                        nextIndex);
            }
        }
    }

    private void tarjan(
            String node,
            Map<String, List<String>> adjacency,
            Map<String, Integer> index,
            Map<String, Integer> low,
            ArrayDeque<String> stack,
            Set<String> onStack,
            int[] nextIndex) {
        int current = nextIndex[0]++;
        index.put(node, current);
        low.put(node, current);
        stack.push(node);
        onStack.add(node);

        for (String next : adjacency.getOrDefault(node, List.of())) {
            if (!index.containsKey(next)) {
                tarjan(
                        next,
                        adjacency,
                        index,
                        low,
                        stack,
                        onStack,
                        nextIndex);
                low.put(
                        node,
                        Math.min(low.get(node), low.get(next)));
            } else if (onStack.contains(next)) {
                low.put(
                        node,
                        Math.min(low.get(node), index.get(next)));
            }
        }

        if (!low.get(node).equals(index.get(node))) {
            return;
        }

        LinkedHashSet<String> component = new LinkedHashSet<>();
        String member;
        do {
            member = stack.pop();
            onStack.remove(member);
            component.add(member);
        } while (!member.equals(node));

        boolean selfCycle = component.size() == 1
                && adjacency.getOrDefault(node, List.of())
                        .contains(node);
        if (component.size() == 1 && !selfCycle) {
            return;
        }

        throw new IllegalArgumentException(
                "workflow contains cycle composed entirely "
                        + "of unbounded edges: "
                        + component);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must not be blank");
        }
        return normalized;
    }
}
